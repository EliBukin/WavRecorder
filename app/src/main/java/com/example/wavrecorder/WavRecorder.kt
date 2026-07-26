package com.example.wavrecorder

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.documentfile.provider.DocumentFile
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.log10

/** Thin seam over [AudioRecord] so recording logic (segment rollover, error handling) can be
 * unit tested with a fake instead of a real microphone. */
interface AudioSource {
    fun startRecording()
    fun read(buffer: ByteArray, offset: Int, length: Int): Int
    fun stop()
    fun release()
}

private class SystemAudioSource(private val audioRecord: AudioRecord) : AudioSource {
    override fun startRecording() = audioRecord.startRecording()
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        audioRecord.read(buffer, offset, length)
    override fun stop() = audioRecord.stop()
    override fun release() = audioRecord.release()
}

/** Thin seam over the destination [FileChannel] for a segment, so partial-write handling can be
 * exercised with a fake/wrapper instead of relying on a real FileChannel ever actually being
 * short (which it almost never is for local files, making the failure mode hard to reproduce). */
interface SegmentWriter {
    fun write(buffer: ByteBuffer): Int
    fun position(): Long
    fun position(newPosition: Long)
    fun close()
}

private class FileChannelSegmentWriter(private val channel: FileChannel) : SegmentWriter {
    override fun write(buffer: ByteBuffer): Int = channel.write(buffer)
    override fun position(): Long = channel.position()
    override fun position(newPosition: Long) {
        channel.position(newPosition)
    }
    override fun close() = channel.close()
}

/**
 * Records raw PCM audio from the microphone via AudioRecord and writes it
 * straight to disk as standard 16-bit PCM WAV file(s). AudioRecord/MediaRecorder
 * have no built-in WAV muxer, so the 44-byte RIFF header is written by hand
 * (via [WavHeaderWriter]) and its size fields are patched in once a file is finished. Writes go
 * through a [SegmentWriter] so the same code path works for a plain File and for a SAF-picked
 * destination (ParcelFileDescriptor), both of which support seeking back to byte 0 to patch the
 * header.
 *
 * The microphone capture itself never stops or gaps: once a segment reaches
 * [segmentMaxSeconds] (60 minutes of audio by default), the current file is finalized and
 * a new one is opened via [NextTarget] for the next segment, transparently to the caller.
 */
class WavRecorder(
    private val segmentMaxSeconds: Long = SEGMENT_MAX_SECONDS,
    private val threadJoinTimeoutMs: Long = THREAD_JOIN_TIMEOUT_MS,
    private val openAudioSource: () -> RecorderConfig = ::openBestAudioRecord,
    private val wrapChannel: (FileChannel) -> SegmentWriter = { FileChannelSegmentWriter(it) }
) {

    companion object {
        // Preferred sample rates in order, highest quality first. 48kHz is the native rate most
        // external USB/BT mics (including the Insta360 Mic Air) actually capture at, so recording
        // at 44.1kHz would force an extra resample step in the driver for no benefit; we fall back
        // only if a device genuinely can't init AudioRecord at 48kHz.
        // Visible (not private) so the top-level openBestAudioRecord() below can share them.
        val SAMPLE_RATE_CANDIDATES = intArrayOf(48000, 44100)
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val CHANNELS = 1
        private const val BITS_PER_SAMPLE = 16
        private const val BYTES_PER_SAMPLE = BITS_PER_SAMPLE / 8

        private const val SEGMENT_MAX_SECONDS = 60 * 60L // roll over to a new file every 60 minutes

        // Re-patch the header periodically so a mid-recording process kill (low memory, task
        // kill, crash) still leaves a valid, playable WAV file instead of one whose header
        // claims zero bytes of audio.
        private const val HEADER_FLUSH_INTERVAL_MS = 3000L

        // stop() releases the AudioSource before joining so a blocked mic read gets kicked loose
        // first; this timeout is just a backstop against the recording thread being stuck
        // somewhere else entirely (e.g. blocked file I/O), so stop() can't hang its caller forever.
        private const val THREAD_JOIN_TIMEOUT_MS = 2000L
    }

    /** Called each time a new segment file is needed: the first one, and every rollover after. */
    fun interface NextTarget {
        fun create(): OutputTarget
    }

    class RecorderConfig(val source: AudioSource, val sampleRate: Int, val bufferSize: Int)

    private var audioSource: AudioSource? = null
    private var recordingThread: Thread? = null
    private val isRecording = AtomicBoolean(false)

    // Bumped on every start(). recordLoop() captures the value current at its own launch and
    // checks it before mutating any shared state or firing a callback: if stop() ever has to give
    // up waiting on a wedged thread (see stop()'s join timeout) and a new session starts, that
    // stale thread resuming later must not be able to touch the new session's state or report
    // errors against it. Without this, a since-superseded thread could flip isRecording false,
    // release the new session's AudioSource, or call the new session's onError out from under it.
    private val generation = AtomicInteger(0)

    // Actual sample rate in use for the current recording session; only known once AudioRecord
    // has been successfully opened, since it may fall back from the preferred candidate.
    private var sampleRate = SAMPLE_RATE_CANDIDATES.first()
    private var segmentMaxBytes = 0L

    val isActive: Boolean get() = isRecording.get()

    @SuppressLint("MissingPermission")
    fun start(
        context: Context,
        nextTarget: NextTarget,
        onSegmentStarted: (OutputTarget) -> Unit,
        onAmplitude: (Float) -> Unit,
        onError: (Exception) -> Unit
    ) {
        if (isRecording.get()) return

        // AudioRecord setup can fail on real devices (mic in use by another app or a call,
        // hardware quirks) and throws rather than returning an error code, so this must not
        // be allowed to propagate as an uncaught exception on the caller's thread.
        val config: RecorderConfig
        try {
            config = openAudioSource()
        } catch (e: Exception) {
            onError(e)
            return
        }

        val source = config.source
        sampleRate = config.sampleRate
        segmentMaxBytes = sampleRate.toLong() * CHANNELS * BYTES_PER_SAMPLE * segmentMaxSeconds

        audioSource = source
        try {
            source.startRecording()
        } catch (e: Exception) {
            // AudioRecord can throw here even though it initialized successfully (mic seized by
            // another app/call between init and start, hardware quirks). Must not leave
            // isRecording true or the recorder held onto with nothing actually running.
            try { source.release() } catch (_: Exception) {}
            audioSource = null
            onError(e)
            return
        }

        val myGeneration = generation.incrementAndGet()
        isRecording.set(true)
        recordingThread = Thread({
            recordLoop(myGeneration, context, source, nextTarget, config.bufferSize, onSegmentStarted, onAmplitude, onError)
        }, "WavRecorderThread").apply { start() }
    }

    /**
     * Idempotent by design (guarded by nulling out [recordingThread]/[audioSource] rather than a
     * one-shot flag): [recordLoop] flips [isRecording] to false itself the moment it hits a fatal
     * error, before this is ever called, so callers reacting to an error callback can safely call
     * this to release the mic without it being a no-op. Must never be called from the recording
     * thread itself — joining it on the calling thread would deadlock.
     *
     * Order matters here: the AudioSource is stopped/released *before* joining the recording
     * thread. [AudioSource.read] can block (a hung driver, a disconnected USB mic), and stopping
     * the source is what unblocks it on most implementations — joining first would mean this
     * call (typically made from the UI thread) hangs until that blocked read eventually returns
     * on its own, which may be never.
     *
     * If the recording thread still doesn't finish within [threadJoinTimeoutMs] (a genuinely
     * wedged driver, not just a blocked read), this gives up waiting rather than hanging the
     * caller forever. That thread is then on its own: [generation] is what keeps it from
     * corrupting a subsequent session if/when it eventually does resume.
     */
    fun stop() {
        isRecording.set(false)
        audioSource?.let {
            try { it.stop() } catch (_: Exception) {}
            try { it.release() } catch (_: Exception) {}
        }
        audioSource = null

        val thread = recordingThread
        recordingThread = null
        if (thread != null) {
            thread.join(threadJoinTimeoutMs)
            if (thread.isAlive) {
                // Stopping the AudioSource didn't free it up (it's stuck somewhere else, e.g.
                // file I/O). There's no safe way to force a thread down from here; interrupting a
                // thread blocked in a native/file syscall generally doesn't unblock it either.
                // Give up waiting rather than hang the caller indefinitely — whatever the thread
                // is stuck on will eventually return, at which point it'll find (via
                // [generation]) that it's no longer the current session and back off quietly.
                thread.interrupt()
            }
        }
    }

    private fun recordLoop(
        myGeneration: Int,
        context: Context,
        source: AudioSource,
        nextTarget: NextTarget,
        bufferSize: Int,
        onSegmentStarted: (OutputTarget) -> Unit,
        onAmplitude: (Float) -> Unit,
        onError: (Exception) -> Unit
    ) {
        val handler = Handler(Looper.getMainLooper())
        val buffer = ByteArray(bufferSize)
        var segment = try {
            openSegment(myGeneration, context, nextTarget, handler, onSegmentStarted)
        } catch (e: Exception) {
            reportFatal(myGeneration, handler, onError, e)
            return
        }

        try {
            while (isRecording.get() && generation.get() == myGeneration) {
                // Everything here — the mic read, the file write, segment rollover — can throw
                // (dead AudioRecord, disk full, revoked SAF permission, a closed/broken
                // FileChannel). Previously only rollover's openSegment() was guarded, so a read()
                // or write() failure would kill this thread silently: isRecording would stay
                // true, the foreground notification would stick around, the mic would never be
                // released, and the user would see no error at all. Catching per-iteration keeps
                // the failure contained to this recording and routes it through the same cleanup
                // and onError path as every other fatal condition below.
                try {
                    val read = source.read(buffer, 0, buffer.size)
                    if (read < 0) {
                        // A negative result usually means a real AudioSource failure (another app
                        // seized the mic, a dead object, etc.) -- but stop() intentionally
                        // stops/releases the AudioSource *before* joining specifically to unblock
                        // a pending read(), so a blocked read returning negative right after an
                        // intentional stop is expected, not a failure. reportFatal() below tells
                        // the two apart by checking whether isRecording is still true.
                        throw IllegalStateException("Recording stopped unexpectedly (AudioRecord error $read)")
                    }
                    if (read == 0) continue

                    writeFully(segment.writer, ByteBuffer.wrap(buffer, 0, read))
                    segment.audioLen += read
                    val amplitude = peakAmplitude(buffer, read)
                    postIfCurrent(myGeneration, handler) { onAmplitude(amplitude) }

                    val now = System.currentTimeMillis()
                    if (now - segment.lastHeaderFlush >= HEADER_FLUSH_INTERVAL_MS) {
                        flushHeader(segment)
                        segment.lastHeaderFlush = now
                    }

                    if (segment.audioLen >= segmentMaxBytes) {
                        closeSegment(context, segment)
                        segment = openSegment(myGeneration, context, nextTarget, handler, onSegmentStarted)
                    }
                } catch (e: Exception) {
                    reportFatal(myGeneration, handler, onError, e)
                    break
                }
            }
        } finally {
            closeSegment(context, segment)
        }
    }

    /**
     * Reports a fatal recording-loop failure — unless it isn't really one. Two things must both
     * still be true for that to happen:
     *  - [generation] must still match [myGeneration]: otherwise a newer session has since
     *    started and this thread is stale (see [stop]'s doc); it must not touch that session's
     *    state or fire its callbacks.
     *  - [isRecording] must still be true: [stop] sets it false *before* stopping/releasing the
     *    AudioSource specifically to unblock a pending read(), so that read() then failing is the
     *    expected result of an intentional stop, not a real error, and must stay silent.
     */
    private fun reportFatal(myGeneration: Int, handler: Handler, onError: (Exception) -> Unit, e: Exception) {
        if (generation.get() == myGeneration && isRecording.compareAndSet(true, false)) {
            postIfCurrent(myGeneration, handler) { onError(e) }
        }
    }

    private fun postIfCurrent(myGeneration: Int, handler: Handler, action: () -> Unit) {
        if (generation.get() != myGeneration) return
        handler.post {
            if (generation.get() == myGeneration) action()
        }
    }

    private class Segment(val target: OutputTarget, val writer: SegmentWriter, val pfd: ParcelFileDescriptor?) {
        var audioLen = 0L
        var lastHeaderFlush = System.currentTimeMillis()
    }

    /** Best-effort: rewrites the header in place without disturbing the write position. */
    private fun flushHeader(segment: Segment) {
        try {
            val writePosition = segment.writer.position()
            patchWavHeader(segment.writer, segment.audioLen)
            segment.writer.position(writePosition)
        } catch (_: Exception) {
            // Not fatal: worst case, the next periodic flush (or the final close) catches up.
        }
    }

    private fun openSegment(
        myGeneration: Int,
        context: Context,
        nextTarget: NextTarget,
        handler: Handler,
        onSegmentStarted: (OutputTarget) -> Unit
    ): Segment {
        val target = nextTarget.create()
        var pfd: ParcelFileDescriptor? = null
        val channel = when (target) {
            is OutputTarget.FileTarget -> {
                val raf = RandomAccessFile(target.file, "rw")
                raf.setLength(0)
                raf.channel
            }
            is OutputTarget.SafTarget -> {
                val opened = context.contentResolver.openFileDescriptor(target.uri, "rw")
                    ?: throw IllegalStateException("Cannot open the selected destination")
                pfd = opened
                val fc = FileOutputStream(opened.fileDescriptor).channel
                fc.truncate(0)
                fc
            }
        }
        val writer = wrapChannel(channel)
        writeFully(writer, WavHeaderWriter.placeholder())
        postIfCurrent(myGeneration, handler) { onSegmentStarted(target) }
        return Segment(target, writer, pfd)
    }

    /**
     * A segment file is created (and [onSegmentStarted] fired) up front, before any audio has
     * actually been captured for it, so that rollover and the initial segment share one code
     * path. If the recording ends (rollover, stop, or a fatal error) before a single byte was
     * ever written to it — most commonly the still-open segment at the moment recording stops —
     * finalizing it would leave a silent, pointless 0-byte-audio WAV file behind in the user's
     * library. Delete it instead of patching a "valid" empty header onto it.
     */
    private fun closeSegment(context: Context, segment: Segment) {
        if (segment.audioLen > 0L) {
            try {
                patchWavHeader(segment.writer, segment.audioLen)
            } catch (_: Exception) {
                // Best-effort: the periodic flushHeader() calls during recording mean the header
                // is very likely already correct on disk even if this final patch fails.
            }
        }
        try { segment.writer.close() } catch (_: Exception) {}
        try { segment.pfd?.close() } catch (_: Exception) {}

        if (segment.audioLen <= 0L) {
            deleteSegmentFile(context, segment.target)
        }
    }

    private fun deleteSegmentFile(context: Context, target: OutputTarget) {
        try {
            when (target) {
                is OutputTarget.FileTarget -> target.file.delete()
                is OutputTarget.SafTarget -> DocumentFile.fromSingleUri(context, target.uri)?.delete()
            }
        } catch (_: Exception) {
            // Not fatal: worst case, an empty WAV lingers until the user notices and deletes it.
        }
    }

    private fun peakAmplitude(buffer: ByteArray, length: Int): Float {
        var max = 0
        var i = 0
        while (i + 1 < length) {
            val sample = ((buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)).toShort()
            val magnitude = abs(sample.toInt())
            if (magnitude > max) max = magnitude
            i += 2
        }
        if (max <= 0) return 0f

        // A straight linear peak ratio barely moves for normal speech, since speech rarely gets
        // anywhere near full scale (and using AudioSource.UNPROCESSED for recording quality means
        // there's no automatic gain control quietly boosting it either). Ears - and eyes watching
        // a level meter - perceive loudness on a log scale, so map dBFS onto a fixed floor..0dB
        // range instead, the way a real VU/peak meter does. -45dBFS is a normal quiet-room noise
        // floor; anything at or above 0dBFS (full scale) reads as maxed out.
        val linear = max / 32767f
        val dbfs = 20f * log10(linear)
        val floorDb = -45f
        return ((dbfs - floorDb) / -floorDb).coerceIn(0f, 1f)
    }

    private fun patchWavHeader(writer: SegmentWriter, totalAudioLen: Long) {
        val header = WavHeaderWriter.build(sampleRate, CHANNELS, BITS_PER_SAMPLE, totalAudioLen)
        writer.position(0)
        writeFully(writer, header)
    }
}

/**
 * [SegmentWriter.write] (like [FileChannel.write]) isn't guaranteed to consume an entire buffer
 * in one call. For a local file this is rare in practice, but SAF destinations go through a
 * content provider (sometimes proxying to removable/network storage) where a short write is
 * genuinely possible. A partial write here would silently make [audioLen]/the WAV header claim
 * more bytes than the file actually holds, so this loops until the buffer is fully drained and
 * treats a call that makes no progress as a failure rather than spinning on it forever.
 */
private fun writeFully(writer: SegmentWriter, data: ByteBuffer) {
    while (data.hasRemaining()) {
        val written = writer.write(data)
        if (written <= 0) {
            throw IOException("Segment write made no progress (returned $written)")
        }
    }
}

/**
 * Picks the best-quality (source, sample rate) combination the device will actually
 * initialize AudioRecord with. AudioSource.UNPROCESSED disables the platform's automatic
 * gain control / noise suppression / echo cancellation, which otherwise run on top of
 * whatever the microphone (including a good external one) already captured — for a
 * dedicated mic that's pure downside. It's not guaranteed on every device, so this falls
 * back to the general-purpose MIC source if UNPROCESSED fails to initialize, and falls back
 * from 48kHz to 44.1kHz if the higher rate isn't supported.
 */
private fun openBestAudioRecord(): WavRecorder.RecorderConfig {
    val channelConfig = WavRecorder.CHANNEL_CONFIG
    val audioFormat = WavRecorder.AUDIO_FORMAT

    val sources = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) add(MediaRecorder.AudioSource.UNPROCESSED)
        add(MediaRecorder.AudioSource.MIC)
    }

    var lastError: Exception? = null
    for (source in sources) {
        for (rate in WavRecorder.SAMPLE_RATE_CANDIDATES) {
            var candidate: AudioRecord? = null
            try {
                val minBufferSize = AudioRecord.getMinBufferSize(rate, channelConfig, audioFormat)
                if (minBufferSize <= 0) continue
                val bufferSize = minBufferSize * 2
                candidate = AudioRecord(source, rate, channelConfig, audioFormat, bufferSize)
                if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                    return WavRecorder.RecorderConfig(SystemAudioSource(candidate), rate, bufferSize)
                }
                candidate.release()
            } catch (e: Exception) {
                candidate?.release()
                lastError = e
            }
        }
    }
    throw lastError ?: IllegalStateException("Microphone is unavailable (may be in use by another app)")
}
