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
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.log10

/**
 * Records raw PCM audio from the microphone via AudioRecord and writes it
 * straight to disk as standard 16-bit PCM WAV file(s). AudioRecord/MediaRecorder
 * have no built-in WAV muxer, so the 44-byte RIFF header is written by hand
 * and its size fields are patched in once a file is finished. Writes go through
 * a FileChannel so the same code path works for a plain File and for a
 * SAF-picked destination (ParcelFileDescriptor), both of which support seeking
 * back to byte 0 to patch the header.
 *
 * The microphone capture itself never stops or gaps: once a segment reaches
 * [SEGMENT_MAX_BYTES] (60 minutes of audio), the current file is finalized and
 * a new one is opened via [NextTarget] for the next segment, transparently to
 * the caller.
 */
class WavRecorder {

    companion object {
        // Preferred sample rates in order, highest quality first. 48kHz is the native rate most
        // external USB/BT mics (including the Insta360 Mic Air) actually capture at, so recording
        // at 44.1kHz would force an extra resample step in the driver for no benefit; we fall back
        // only if a device genuinely can't init AudioRecord at 48kHz.
        private val SAMPLE_RATE_CANDIDATES = intArrayOf(48000, 44100)
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val CHANNELS = 1
        private const val BITS_PER_SAMPLE = 16
        private const val BYTES_PER_SAMPLE = BITS_PER_SAMPLE / 8

        private const val SEGMENT_MAX_SECONDS = 60 * 60L // roll over to a new file every 60 minutes

        // Re-patch the header periodically so a mid-recording process kill (low memory, task
        // kill, crash) still leaves a valid, playable WAV file instead of one whose header
        // claims zero bytes of audio.
        private const val HEADER_FLUSH_INTERVAL_MS = 3000L
    }

    /** Called each time a new segment file is needed: the first one, and every rollover after. */
    fun interface NextTarget {
        fun create(): OutputTarget
    }

    private class RecorderConfig(val recorder: AudioRecord, val sampleRate: Int, val bufferSize: Int)

    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private val isRecording = AtomicBoolean(false)

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
            config = openBestAudioRecord()
        } catch (e: Exception) {
            onError(e)
            return
        }

        val recorder = config.recorder
        sampleRate = config.sampleRate
        segmentMaxBytes = sampleRate.toLong() * CHANNELS * BYTES_PER_SAMPLE * SEGMENT_MAX_SECONDS

        audioRecord = recorder
        isRecording.set(true)
        recorder.startRecording()

        recordingThread = Thread({
            recordLoop(context, recorder, nextTarget, config.bufferSize, onSegmentStarted, onAmplitude, onError)
        }, "WavRecorderThread").apply { start() }
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
    private fun openBestAudioRecord(): RecorderConfig {
        val sources = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) add(MediaRecorder.AudioSource.UNPROCESSED)
            add(MediaRecorder.AudioSource.MIC)
        }

        var lastError: Exception? = null
        for (source in sources) {
            for (rate in SAMPLE_RATE_CANDIDATES) {
                var candidate: AudioRecord? = null
                try {
                    val minBufferSize = AudioRecord.getMinBufferSize(rate, CHANNEL_CONFIG, AUDIO_FORMAT)
                    if (minBufferSize <= 0) continue
                    val bufferSize = minBufferSize * 2
                    candidate = AudioRecord(source, rate, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize)
                    if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                        return RecorderConfig(candidate, rate, bufferSize)
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

    /**
     * Idempotent by design (guarded by nulling out [recordingThread]/[audioRecord] rather than a
     * one-shot flag): [recordLoop] flips [isRecording] to false itself the moment it hits a fatal
     * error, before this is ever called, so callers reacting to an error callback can safely call
     * this to release the mic without it being a no-op. Must never be called from the recording
     * thread itself — [recordingThread]?.join() on the calling thread would deadlock.
     */
    fun stop() {
        isRecording.set(false)
        recordingThread?.join()
        recordingThread = null
        audioRecord?.let {
            try { it.stop() } catch (_: Exception) {}
            try { it.release() } catch (_: Exception) {}
        }
        audioRecord = null
    }

    private fun recordLoop(
        context: Context,
        recorder: AudioRecord,
        nextTarget: NextTarget,
        bufferSize: Int,
        onSegmentStarted: (OutputTarget) -> Unit,
        onAmplitude: (Float) -> Unit,
        onError: (Exception) -> Unit
    ) {
        val handler = Handler(Looper.getMainLooper())
        val buffer = ByteArray(bufferSize)
        var segment = try {
            openSegment(context, nextTarget, handler, onSegmentStarted)
        } catch (e: Exception) {
            isRecording.set(false)
            handler.post { onError(e) }
            return
        }

        try {
            while (isRecording.get()) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read < 0) {
                    // A negative result is a real AudioRecord failure (another app seized the
                    // mic, a dead object, etc.), not a transient empty read. Looping on it would
                    // peg this thread at 100% forever while silently recording nothing, so treat
                    // it as fatal instead of retrying.
                    isRecording.set(false)
                    handler.post { onError(IllegalStateException("Recording stopped unexpectedly (AudioRecord error $read)")) }
                    break
                }
                if (read == 0) continue

                segment.channel.write(ByteBuffer.wrap(buffer, 0, read))
                segment.audioLen += read
                val amplitude = peakAmplitude(buffer, read)
                handler.post { onAmplitude(amplitude) }

                val now = System.currentTimeMillis()
                if (now - segment.lastHeaderFlush >= HEADER_FLUSH_INTERVAL_MS) {
                    flushHeader(segment)
                    segment.lastHeaderFlush = now
                }

                if (segment.audioLen >= segmentMaxBytes) {
                    closeSegment(segment)
                    segment = try {
                        openSegment(context, nextTarget, handler, onSegmentStarted)
                    } catch (e: Exception) {
                        isRecording.set(false)
                        handler.post { onError(e) }
                        return
                    }
                }
            }
        } finally {
            closeSegment(segment)
        }
    }

    private class Segment(val channel: FileChannel, val pfd: ParcelFileDescriptor?) {
        var audioLen = 0L
        var lastHeaderFlush = System.currentTimeMillis()
    }

    /** Best-effort: rewrites the header in place without disturbing the write position. */
    private fun flushHeader(segment: Segment) {
        try {
            val writePosition = segment.channel.position()
            patchWavHeader(segment.channel, segment.audioLen)
            segment.channel.position(writePosition)
        } catch (_: Exception) {
            // Not fatal: worst case, the next periodic flush (or the final close) catches up.
        }
    }

    private fun openSegment(
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
        writeWavHeaderPlaceholder(channel)
        handler.post { onSegmentStarted(target) }
        return Segment(channel, pfd)
    }

    private fun closeSegment(segment: Segment) {
        try {
            patchWavHeader(segment.channel, segment.audioLen)
        } catch (_: Exception) {
        } finally {
            try { segment.channel.close() } catch (_: Exception) {}
            try { segment.pfd?.close() } catch (_: Exception) {}
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

    private fun writeWavHeaderPlaceholder(channel: FileChannel) {
        channel.write(ByteBuffer.allocate(44))
    }

    private fun patchWavHeader(channel: FileChannel, totalAudioLen: Long) {
        val byteRate = sampleRate * CHANNELS * BITS_PER_SAMPLE / 8
        val blockAlign = CHANNELS * BITS_PER_SAMPLE / 8
        val totalDataLen = totalAudioLen + 36

        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt(totalDataLen.toInt())
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16) // PCM sub-chunk size
        header.putShort(1) // audio format = PCM
        header.putShort(CHANNELS.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(blockAlign.toShort())
        header.putShort(BITS_PER_SAMPLE.toShort())
        header.put("data".toByteArray())
        header.putInt(totalAudioLen.toInt())
        header.flip()

        channel.position(0)
        channel.write(header)
    }
}
