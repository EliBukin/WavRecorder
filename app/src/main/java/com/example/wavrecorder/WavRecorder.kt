package com.example.wavrecorder

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
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

/** Describes the input device actually captured from, for display and for deciding whether
 * disconnect-monitoring is even possible. [verified] is false whenever the real routed device
 * couldn't be determined (an implementation/OS that doesn't support it, or a test fake) -- callers
 * must not read [isExternal] as a confident "yes" in that case, only as "unknown". */
data class MicrophoneInfo(
    val label: String,
    val isExternal: Boolean,
    val verified: Boolean
) {
    companion object {
        val UNVERIFIED = MicrophoneInfo(label = "Unverified input", isExternal = false, verified = false)
    }
}

/** Thrown when the input device an already-running recording verified itself to be using
 * disconnects mid-recording. Deliberately distinct from a generic I/O failure so the UI can show a
 * message specific to "unplug the mic" rather than a generic recording error. */
class MicrophoneDisconnectedException(message: String) : IOException(message)

/** Thrown when a periodic re-check finds the actually-routed capture device no longer matches the
 * one verified at recording start -- e.g. Android silently rerouting an active AudioRecord to the
 * built-in mic without ever reporting the original device as removed, which is the specific gap
 * [MicrophoneDisconnectedException] (driven entirely by AudioDeviceCallback.onAudioDevicesRemoved)
 * cannot catch on its own. Kept as a distinct type so the UI can report accurately: the external
 * mic may still be physically plugged in, it's just no longer the one actually being recorded. */
class MicrophoneRouteChangedException(message: String) : IOException(message)

/** Thrown by [WavRecorder.closeSegment] when the final WAV header patch or the writer's close
 * failed: the audio bytes already written are very likely intact (they were flushed incrementally
 * during recording; see HEADER_FLUSH_INTERVAL_MS), but the file's header may be stale or the last
 * few buffers unflushed, so it needs to be treated as "may need recovery", never as a normal save. */
class WavFinalizationException(val target: OutputTarget, cause: Exception) :
    IOException("Failed to finalize ${target.displayPath}: ${cause.message}", cause)

/** Thin seam over [AudioRecord] so recording logic (segment rollover, error handling) can be
 * unit tested with a fake instead of a real microphone. */
interface AudioSource {
    fun startRecording()
    fun read(buffer: ByteArray, offset: Int, length: Int): Int
    fun stop()
    fun release()

    /** Best-effort description of the device actually routed for capture, established once
     * [startRecording] has succeeded. Default [MicrophoneInfo.UNVERIFIED]: a fake/test source (or
     * a real one that can't determine routing) must never claim external-mic confidence it
     * doesn't have. */
    fun describeMicrophone(): MicrophoneInfo = MicrophoneInfo.UNVERIFIED

    /** True as long as the input device verified in [describeMicrophone] is still present. A real
     * implementation flips this to false the moment the OS reports that device removed -- default
     * true means "no disconnect monitoring available for this source", not "definitely connected". */
    fun isDeviceConnected(): Boolean = true

    /** True as long as the capture device actually routed right now is still the one verified in
     * [describeMicrophone]. Unlike [isDeviceConnected] (driven by an explicit OS removal
     * callback), a real implementation re-checks this directly on the same cadence, catching a
     * silent reroute (typically to the built-in mic) that never fires a removal callback at all
     * because, as far as the OS is concerned, nothing was removed. Default true: no route
     * re-verification available (a fake/test source, or a real one that never verified a route to
     * begin with). */
    fun isRouteUnchanged(): Boolean = true
}

/** Types of [AudioDeviceInfo] that represent a physically attached microphone rather than the
 * phone's own built-in hardware or a virtual/telephony source. Covers how the Insta360 Mic Air and
 * similar accessories actually enumerate (USB, or a USB/3.5mm adapter reporting as a wired
 * headset); Bluetooth SCO is included for a paired mic headset. */
internal fun isExternalInputType(type: Int): Boolean = when (type) {
    AudioDeviceInfo.TYPE_USB_DEVICE,
    AudioDeviceInfo.TYPE_USB_HEADSET,
    AudioDeviceInfo.TYPE_USB_ACCESSORY,
    AudioDeviceInfo.TYPE_WIRED_HEADSET,
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> true
    else -> false
}

private fun genericLabelForType(type: Int): String = when (type) {
    AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Phone microphone"
    AudioDeviceInfo.TYPE_USB_DEVICE -> "USB microphone"
    AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset microphone"
    AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB accessory microphone"
    AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset microphone"
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth microphone"
    else -> "External microphone"
}

/** Prefers the device's own reported product name (an Insta360 Mic Air identifies itself this way
 * over USB) and only falls back to a generic type-based label when the OS doesn't supply one. */
internal fun friendlyDeviceLabel(device: AudioDeviceInfo): String {
    val product = device.productName?.toString()?.trim().orEmpty()
    return product.ifEmpty { genericLabelForType(device.type) }
}

/** An attached input device's reported product name is checked against this to prefer an actual
 * Insta360 accessory (e.g. "Insta360 Mic Air") over some other external input when both are
 * attached at once -- this app exists specifically for Insta360 field recording, so when there's
 * a choice, that's the one that should win. */
internal fun isInsta360Device(device: AudioDeviceInfo): Boolean =
    device.productName?.toString()?.contains("insta360", ignoreCase = true) == true

/** The best attached input device that looks like a real external microphone rather than the
 * phone's own hardware: an Insta360 accessory is preferred by product name over any other external
 * input when several are attached at once, falling back to the first external input found. Only a
 * preference either way: if it later turns out unusable, [AudioRecord] itself falls back to
 * default routing, which [SystemAudioSource] then verifies (never assumes) via
 * [AudioRecord.getRoutedDevice]. */
private fun pickExternalInputDevice(audioManager: AudioManager): AudioDeviceInfo? {
    val externalInputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        ?.filter { isExternalInputType(it.type) }
        ?: return null
    return externalInputs.firstOrNull(::isInsta360Device) ?: externalInputs.firstOrNull()
}

private class SystemAudioSource(
    private val audioRecord: AudioRecord,
    private val audioManager: AudioManager
) : AudioSource {
    @Volatile private var micInfo: MicrophoneInfo = MicrophoneInfo.UNVERIFIED
    @Volatile private var deviceConnected: Boolean = true
    private var routedDeviceId: Int? = null
    private var routedDeviceWasExternal: Boolean = false
    private var deviceCallback: AudioDeviceCallback? = null

    override fun startRecording() {
        audioRecord.startRecording()

        // Only knowable once recording has actually started -- routing isn't established before
        // that, so checking any earlier would either return null or a stale/default guess. This
        // is the "verify, don't assume" step: UNPROCESSED/MIC being the chosen AudioSource type
        // says nothing about *which physical device* ended up routed.
        val routed = audioRecord.routedDevice ?: return
        routedDeviceId = routed.id
        routedDeviceWasExternal = isExternalInputType(routed.type)
        micInfo = MicrophoneInfo(
            label = friendlyDeviceLabel(routed),
            isExternal = routedDeviceWasExternal,
            verified = true
        )

        // Only arms disconnect-watching for a verified *external* device: the built-in mic can't
        // "disconnect", and without a verified routedDeviceId there's nothing concrete to match a
        // removal event against.
        if (isExternalInputType(routed.type)) {
            val callback = object : AudioDeviceCallback() {
                override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) {
                    if (removedDevices.any { it.id == routedDeviceId }) {
                        deviceConnected = false
                    }
                }
            }
            deviceCallback = callback
            // Callbacks land on the caller's own looper (main, here) when handler is null; that's
            // fine since all this does is flip a volatile flag -- recordLoop() is what actually
            // acts on it, from the recording thread, at its own pace (see isDeviceConnected()).
            audioManager.registerAudioDeviceCallback(callback, null)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        audioRecord.read(buffer, offset, length)
    override fun stop() = audioRecord.stop()
    override fun release() {
        deviceCallback?.let { audioManager.unregisterAudioDeviceCallback(it) }
        deviceCallback = null
        audioRecord.release()
    }
    override fun describeMicrophone(): MicrophoneInfo = micInfo

    override fun isDeviceConnected(): Boolean = deviceConnected

    // Independent of the AudioDeviceCallback-driven deviceConnected flag above: this re-checks
    // AudioRecord's own live routing directly, so a reroute the OS never reports as a "removal" at
    // all (most commonly a silent fallback to the built-in mic while the external device is still
    // physically attached, e.g. a transient USB glitch) still gets caught, on the same cadence.
    override fun isRouteUnchanged(): Boolean {
        val verifiedId = routedDeviceId ?: return true
        val routed = audioRecord.routedDevice ?: return false
        if (routed.id != verifiedId) return false
        if (routedDeviceWasExternal && !isExternalInputType(routed.type)) return false
        return true
    }
}

/** Fallback for the (practically never expected) case where [AudioManager] itself isn't
 * available: recording still proceeds using default routing, it's just unverifiable, so this
 * relies entirely on [AudioSource]'s default "unverified"/"always connected" behavior rather than
 * claiming external-mic confidence -- or disconnect-monitoring -- it has no way to back up. */
private class UnmonitorableAudioSource(private val audioRecord: AudioRecord) : AudioSource {
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

    /** Best-effort: push already-written bytes past the OS page cache to physical storage, so
     * captured audio survives a hard crash/power loss rather than just an app-level one (which the
     * page cache alone already covers). Default no-op for writers that can't do this (e.g. tests). */
    fun force() {}
}

private class FileChannelSegmentWriter(private val channel: FileChannel) : SegmentWriter {
    override fun write(buffer: ByteBuffer): Int = channel.write(buffer)
    override fun position(): Long = channel.position()
    override fun position(newPosition: Long) {
        channel.position(newPosition)
    }
    override fun close() = channel.close()
    override fun force() = channel.force(false) // false: skip syncing metadata/timestamps, just data
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
    // Configurable (like the two above) purely so tests can shrink the real-time cost of
    // exercising the header-flush/disconnect-check cadence instead of waiting out the production
    // default every time.
    private val headerFlushIntervalMs: Long = HEADER_FLUSH_INTERVAL_MS,
    private val openAudioSource: (Context) -> RecorderConfig = ::openBestAudioRecord,
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
        // claims zero bytes of audio. Disconnect-monitoring piggybacks on the same cadence (see
        // recordLoop()) rather than being checked on every single read, for the same reason.
        private const val HEADER_FLUSH_INTERVAL_MS = 3000L

        // Forcing every write to physical storage would be needless I/O/battery cost; this only
        // needs to bound how much audio a hard crash/power loss (not just an app-level failure,
        // which the OS page cache already survives) could lose, so a much coarser interval is fine.
        private const val DATA_FORCE_INTERVAL_MS = 30_000L

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

    /** Result of the final segment's finalization, known by the time [stop] returns (assuming the
     * recording thread actually joined within [threadJoinTimeoutMs]). */
    sealed class FinalizeResult {
        /** The last segment's header/writer closed cleanly. */
        object Ok : FinalizeResult()
        /** The recording thread didn't finish within the join timeout, so whether the last
         * segment finalized cleanly is unknown -- it may still be running on its own. */
        object Unknown : FinalizeResult()
        /** The last segment's header patch or writer close failed; [target] (if known) may be
         * truncated or carry a stale header and should be treated as needing recovery. */
        data class Failed(val target: OutputTarget?, val cause: Exception) : FinalizeResult()
    }

    private var audioSource: AudioSource? = null
    private var recordingThread: Thread? = null
    private val isRecording = AtomicBoolean(false)
    @Volatile private var lastFinalizeResult: FinalizeResult = FinalizeResult.Ok

    // Bumped on every start() *and* on every stop(). recordLoop() captures the value current at
    // its own launch and rechecks it (together with isRecording) before mutating any shared
    // state or firing a callback: if stop() ever has to give up waiting on a wedged thread (see
    // stop()'s join timeout) and a new session starts, that stale thread resuming later must not
    // be able to touch the new session's state or report errors against it. Without this, a
    // since-superseded thread could flip isRecording false, release the new session's
    // AudioSource, or call the new session's onError out from under it. Bumping it in stop() too
    // means even callbacks already queued on the main looper by the just-stopped session (but not
    // yet delivered) get suppressed when they run, rather than only future ones.
    private val generation = AtomicInteger(0)

    // Bumped only by start() -- never by stop() -- purely so a recording thread's finally block
    // can tell whether a *newer* session has since begun by the time it actually runs, before
    // writing lastFinalizeResult. generation can't be reused for this: stop() always bumps it as
    // part of a session's own normal shutdown, moments before that same session's finally block
    // even runs, which would make a session unable to ever report its own finalization result.
    private val sessionCounter = AtomicInteger(0)

    val isActive: Boolean get() = isRecording.get()

    @SuppressLint("MissingPermission")
    fun start(
        context: Context,
        nextTarget: NextTarget,
        onSegmentStarted: (OutputTarget) -> Unit,
        onAmplitude: (Float) -> Unit,
        onError: (Exception) -> Unit,
        onMicrophoneInfo: (MicrophoneInfo) -> Unit = {}
    ) {
        if (isRecording.get()) return

        // AudioRecord setup can fail on real devices (mic in use by another app or a call,
        // hardware quirks) and throws rather than returning an error code, so this must not
        // be allowed to propagate as an uncaught exception on the caller's thread.
        val config: RecorderConfig
        try {
            config = openAudioSource(context)
        } catch (e: Exception) {
            onError(e)
            return
        }

        val source = config.source
        val sampleRate = config.sampleRate
        val segmentMaxBytes = sampleRate.toLong() * CHANNELS * BYTES_PER_SAMPLE * segmentMaxSeconds

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

        // Established synchronously (this runs on the caller's thread, before the recording
        // thread even exists) so a UI observing this call can show the active input immediately,
        // not after the first segment/amplitude callback arrives from the background thread.
        onMicrophoneInfo(source.describeMicrophone())

        lastFinalizeResult = FinalizeResult.Ok
        val myGeneration = generation.incrementAndGet()
        val mySession = sessionCounter.incrementAndGet()
        isRecording.set(true)
        recordingThread = Thread({
            recordLoop(
                myGeneration, mySession, context, source, nextTarget, config.bufferSize, sampleRate,
                segmentMaxBytes, onSegmentStarted, onAmplitude, onError
            )
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
     *
     * Returns whether the just-stopped session's final segment actually finalized cleanly.
     * [recordLoop]'s finally block runs entirely on the recording thread before it terminates, so
     * by the time [Thread.join] returns having actually joined (not timed out), that result is
     * already known -- callers use this to decide whether it's safe to report a plain "saved" or
     * whether the file may need recovery, instead of always assuming success.
     */
    fun stop(): FinalizeResult {
        isRecording.set(false)
        // Invalidate this session's generation too: a callback the recording thread already
        // posted to the main looper before observing isRecording=false (e.g. the amplitude
        // update for the read right before this stop()) would otherwise still be delivered,
        // updating the UI for a session that's already stopped. This doesn't affect the
        // recording thread's own finalization of its current segment, which never consults
        // generation -- only the *dispatch* of callbacks does.
        generation.incrementAndGet()
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
                return FinalizeResult.Unknown
            }
        }
        return lastFinalizeResult
    }

    private fun recordLoop(
        myGeneration: Int,
        mySession: Int,
        context: Context,
        source: AudioSource,
        nextTarget: NextTarget,
        bufferSize: Int,
        sampleRate: Int,
        segmentMaxBytes: Long,
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

                    // source.read() can block for an arbitrary time (a hung driver, a
                    // disconnected mic) and stop() only waits up to threadJoinTimeoutMs for it
                    // before giving up (see stop()'s doc). If this thread is still stuck in that
                    // read() when a newer session starts, the read may eventually return *valid*
                    // data instead of an error -- recheck right away, before this data touches
                    // any state, and quietly bail if this thread is no longer current. Otherwise
                    // this would write stale audio into a segment that's already being finalized
                    // (or gone), roll it over into a segment nothing else knows about, and/or
                    // finalize using a newer session's sampleRate/segmentMaxBytes.
                    if (generation.get() != myGeneration || !isRecording.get()) break

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
                    if (now - segment.lastHeaderFlush >= headerFlushIntervalMs) {
                        // Piggybacks on the header-flush cadence rather than being checked on
                        // every read: a real AudioRecord can keep handing back samples for a
                        // short while after its routed device is actually gone (buffered/stale
                        // data, or silently re-routed to the phone mic), so this doesn't need
                        // read()-level responsiveness -- it just must not go unnoticed for long.
                        if (!source.isDeviceConnected()) {
                            throw MicrophoneDisconnectedException(
                                "External microphone disconnected during recording"
                            )
                        }
                        // Checked in addition to (not instead of) isDeviceConnected() above: a
                        // silent reroute away from the verified device may never fire a removal
                        // callback at all, so relying on that alone would let recording continue
                        // unnoticed on the phone mic.
                        if (!source.isRouteUnchanged()) {
                            throw MicrophoneRouteChangedException(
                                "Recording input changed unexpectedly; no longer using the " +
                                    "verified external microphone"
                            )
                        }
                        flushHeader(segment, sampleRate)
                        segment.lastHeaderFlush = now
                    }
                    if (now - segment.lastForce >= DATA_FORCE_INTERVAL_MS) {
                        forceSegment(segment)
                        segment.lastForce = now
                    }

                    if (segment.audioLen >= segmentMaxBytes) {
                        // closeSegment() now throws on a genuine finalization failure (header
                        // patch or writer close) instead of swallowing it -- letting that
                        // propagate here means a rollover that fails to finalize is treated as
                        // fatal via the same catch below, instead of silently opening a new
                        // segment and continuing as if nothing happened.
                        closeSegment(context, segment, sampleRate)
                        segment = openSegment(myGeneration, context, nextTarget, handler, onSegmentStarted)
                    }
                } catch (e: Exception) {
                    reportFatal(myGeneration, handler, onError, e)
                    break
                }
            }
        } finally {
            // Deliberately not left to propagate uncaught: this runs in the recording thread's
            // own finally block with nothing above it on the call stack to catch it, so an
            // uncaught failure here would silently kill the thread while isRecording stayed true
            // forever -- the exact silent-hang bug finalization errors must not cause. The result
            // is instead handed to stop() (see lastFinalizeResult/FinalizeResult) so the caller
            // can tell a genuine save apart from a segment that needs recovery.
            try {
                closeSegment(context, segment, sampleRate)
                if (sessionCounter.get() == mySession) lastFinalizeResult = FinalizeResult.Ok
            } catch (e: Exception) {
                if (sessionCounter.get() == mySession) {
                    lastFinalizeResult = FinalizeResult.Failed(segment.target, e)
                }
            }
        }
    }

    /** Best-effort: not fatal on its own if it fails, since it's purely a durability nicety (see
     * DATA_FORCE_INTERVAL_MS) -- the audio isn't lost, it just hasn't been proactively pushed past
     * the OS page cache yet, and the next periodic force (or the final close) will catch up. */
    private fun forceSegment(segment: Segment) {
        try { segment.writer.force() } catch (_: Exception) {}
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
        var lastForce = System.currentTimeMillis()
    }

    /** Best-effort: rewrites the header in place without disturbing the write position. */
    private fun flushHeader(segment: Segment, sampleRate: Int) {
        try {
            val writePosition = segment.writer.position()
            patchWavHeader(segment.writer, segment.audioLen, sampleRate)
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
        // Declared outside the try so a failure partway through construction (a bad truncate, a
        // wrapChannel seam throwing, a disk-full header write) still leaves the catch block a
        // reference to whatever was actually opened before the failure -- each is assigned
        // immediately after its constructor/open call succeeds, before the next fallible step.
        var raf: RandomAccessFile? = null
        var pfd: ParcelFileDescriptor? = null
        var channel: FileChannel? = null
        try {
            when (target) {
                is OutputTarget.FileTarget -> {
                    val r = RandomAccessFile(target.file, "rw")
                    raf = r
                    channel = r.channel
                    r.setLength(0)
                }
                is OutputTarget.SafTarget -> {
                    val opened = context.contentResolver.openFileDescriptor(target.uri, "rw")
                        ?: throw IllegalStateException("Cannot open the selected destination")
                    pfd = opened
                    val fc = FileOutputStream(opened.fileDescriptor).channel
                    channel = fc
                    fc.truncate(0)
                }
            }
            val writer = wrapChannel(channel!!)
            writeFully(writer, WavHeaderWriter.placeholder())
            postIfCurrent(myGeneration, handler) { onSegmentStarted(target) }
            return Segment(target, writer, pfd)
        } catch (e: Exception) {
            // Without this, any of the failures above would leak the FileChannel/RandomAccessFile
            // (or, for SAF, the ParcelFileDescriptor's underlying fd) and leave a partial/empty
            // file behind that closeSegment() never gets a chance to clean up, since a Segment was
            // never successfully constructed.
            try { channel?.close() } catch (_: Exception) {}
            try { raf?.close() } catch (_: Exception) {}
            try { pfd?.close() } catch (_: Exception) {}
            deleteSegmentFile(context, target)
            throw e
        }
    }

    /**
     * A segment file is created (and [onSegmentStarted] fired) up front, before any audio has
     * actually been captured for it, so that rollover and the initial segment share one code
     * path. If the recording ends (rollover, stop, or a fatal error) before a single byte was
     * ever written to it — most commonly the still-open segment at the moment recording stops —
     * finalizing it would leave a silent, pointless 0-byte-audio WAV file behind in the user's
     * library. Delete it instead of patching a "valid" empty header onto it.
     *
     * A failure patching the final header or closing the writer used to be swallowed here, which
     * meant a caller could report "Saved" for a file whose header never actually got the right
     * size written to it (or whose last buffered bytes never made it past the writer). Both are
     * now collected and thrown as a [WavFinalizationException] instead -- every other cleanup step
     * (the fd close, deleting an empty segment) still runs first regardless, so this only ever
     * reports a failure *after* doing everything it can to leave the file in the best state
     * possible; it never skips cleanup just because it's about to report a problem.
     */
    private fun closeSegment(context: Context, segment: Segment, sampleRate: Int) {
        var failure: Exception? = null
        if (segment.audioLen > 0L) {
            try {
                patchWavHeader(segment.writer, segment.audioLen, sampleRate)
            } catch (e: Exception) {
                failure = e
            }
        }
        try {
            segment.writer.close()
        } catch (e: Exception) {
            // The header patch above is what's least likely to have actually reached disk (it's a
            // tiny seek-and-rewrite at the very end), so if it already failed, that's the more
            // informative cause to surface; a close() failure on top is very often a downstream
            // symptom of the same broken channel/destination, not a separate root cause.
            if (failure == null) failure = e
        }
        try {
            segment.pfd?.close()
        } catch (_: Exception) {
            // Secondary fd release for a SAF destination; the writer/channel close above is what
            // actually flushes/finalizes the data, so a failure here alone isn't treated as fatal.
        }

        if (segment.audioLen <= 0L) {
            deleteSegmentFile(context, segment.target)
        }

        if (failure != null) {
            throw WavFinalizationException(segment.target, failure)
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

    private fun patchWavHeader(writer: SegmentWriter, totalAudioLen: Long, sampleRate: Int) {
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
 *
 * Neither AudioSource type says anything about *which physical input device* actually ends up
 * routed, though: on a phone with an external mic attached, the OS still decides that routing on
 * its own. [AudioRecord.Builder.setPreferredDevice] is only a request -- it can silently fall
 * back to the built-in mic if the preferred device becomes unusable between selection and start
 * -- so [SystemAudioSource] verifies the *actual* routed device once recording begins rather than
 * trusting this preference; see [SystemAudioSource.startRecording].
 */
@SuppressLint("MissingPermission")
private fun openBestAudioRecord(context: Context): WavRecorder.RecorderConfig {
    val channelConfig = WavRecorder.CHANNEL_CONFIG
    val audioFormat = WavRecorder.AUDIO_FORMAT
    // A required system service; only nullable in the framework's generic getSystemService(Class)
    // signature. Without it there's no way to verify or monitor routing at all, so this falls
    // back to the same "unverified" path a device lacking explicit-routing support would take.
    val audioManager = context.getSystemService(AudioManager::class.java)
    val preferredDevice = audioManager?.let(::pickExternalInputDevice)

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
                candidate = AudioRecord.Builder()
                    .setAudioSource(source)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(audioFormat)
                            .setSampleRate(rate)
                            .setChannelMask(channelConfig)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferSize)
                    .build()
                if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                    // setPreferredDevice() is an AudioRouting instance method (API 28+), not a
                    // Builder step -- and only a *request* either way, which is exactly why
                    // SystemAudioSource still verifies the device that actually ends up routed
                    // afterward rather than trusting this was honored.
                    if (preferredDevice != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        candidate.setPreferredDevice(preferredDevice)
                    }
                    val audioSource = if (audioManager != null) {
                        SystemAudioSource(candidate, audioManager)
                    } else {
                        UnmonitorableAudioSource(candidate)
                    }
                    return WavRecorder.RecorderConfig(audioSource, rate, bufferSize)
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
