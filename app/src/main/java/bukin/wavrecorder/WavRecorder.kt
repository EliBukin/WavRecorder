package bukin.wavrecorder

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

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

/** The microphone stopped delivering audio -- not even silence -- for longer than the recorder's
 * no-progress limit: a driver whose reads never return, or a device that keeps returning nothing.
 * Distinct from silent audio, which is still audio (a muted microphone, or another app's call
 * taking priority, delivers zero-valued samples and keeps recording). */
class MicrophoneStalledException(message: String) : IOException(message)

/** Storage fell so far behind that the bounded capture queue filled up. Recording stops rather
 * than drop audio silently; everything captured before that point is written and saved. */
class StorageTooSlowException(message: String) : IOException(message)

/** Writing to storage failed before all captured audio reached the file -- during the recording or
 * while the audio already captured was still being written after Stop. The file is a valid WAV,
 * but holds the audio only up to the failure: [FinalizeResult.Incomplete]'s cause. */
class IncompleteRecordingException(cause: Exception) :
    IOException("Writing to storage failed, so the recording holds audio only up to that point: ${cause.message}", cause)

/** Writing to storage failed before any of the session's audio reached it, so nothing was saved:
 * the file it had started received no audio and was deleted. [FinalizeResult.NothingSaved]'s cause. */
class RecordingNotSavedException(cause: Exception) :
    IOException("Writing to storage failed before any audio was saved, so nothing was recorded: ${cause.message}", cause)

/** A recording was started expecting an external microphone (one was attached, and the user hadn't
 * chosen the phone microphone), but the microphone negotiation verified was not an external one --
 * typically because the external microphone couldn't be used in any format. Nothing was recorded;
 * the Record screen offers to retry or to continue with the phone microphone. */
class ExternalMicrophoneNotInUseException(message: String) : IOException(message)

/** Thrown by [WavRecorder.closeSegment] when the final WAV header patch or the writer's close
 * failed: the audio bytes already written are very likely intact (they were flushed incrementally
 * during recording; see HEADER_FLUSH_INTERVAL_MS), but the file's header may be stale or the last
 * few buffers unflushed, so it needs to be treated as "may need recovery", never as a normal save. */
class WavFinalizationException(val target: OutputTarget, cause: Exception) :
    IOException("Failed to finalize ${target.displayPath}: ${cause.message}", cause)

/** An audio write to [target] failed, and the file may hold more audio than the writer confirmed
 * -- its size shows whole frames past the confirmed audio, or can't be read. The file is kept as
 * it is -- never deleted or truncated -- with its crash-recovery record, so recovery settles it
 * from what is physically there; until then its header describes the confirmed audio only.
 * Distinct from [WavFinalizationException] (a header patch or close that failed): [cause] is the
 * write failure itself. [FinalizeResult.Failed]'s cause. */
class UnconfirmedAudioException(val target: OutputTarget, cause: Exception) :
    IOException("Writing to storage failed, and ${target.displayPath} may hold more audio than was confirmed; " +
        "it is kept for recovery: ${cause.message}", cause)

/** Thin seam over [AudioRecord] so recording logic (segment rollover, error handling) can be
 * unit tested with a fake instead of a real microphone. The production implementation
 * ([SystemAudioSource]) arrives already started -- see [WavRecorder.RecorderConfig.alreadyStarted]. */
interface AudioSource {
    fun startRecording()

    /** Reads up to [length] bytes of little-endian samples into `buffer[offset ...]`: the byte
     * count, 0 when nothing was available, or a negative AudioRecord error code. */
    fun read(buffer: ByteArray, offset: Int, length: Int): Int
    fun stop()
    fun release()

    /** Best-effort description of the device actually routed for capture, established once the
     * source is recording. Default [MicrophoneInfo.UNVERIFIED]: a fake/test source (or
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

    /** The format Android is capturing from the device in, once the source is recording, when the
     * platform can say (the active recording configuration); null when unknown. Diagnostic only:
     * compared against the client format written to the file to reveal an Android conversion. */
    fun deviceSideFormat(): DeviceSideFormat? = null
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
 * preference: [openBestAudioRecord] accepts a candidate for it only once [AudioRecord.getRoutedDevice]
 * confirms capture is actually routed there. */
private fun pickExternalInputDevice(inputs: List<AudioDeviceInfo>): AudioDeviceInfo? {
    val externalInputs = inputs.filter { isExternalInputType(it.type) }
    return externalInputs.firstOrNull(::isInsta360Device) ?: externalInputs.firstOrNull()
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
 * Records raw PCM audio from the microphone via AudioRecord and writes it straight to disk as
 * standard WAV file(s), in whatever lossless format [openBestAudioRecord] negotiated for the
 * session (16/24/32-bit integer or 32-bit float, any genuine channel count -- see [PcmFormat]).
 * AudioRecord/MediaRecorder have no built-in WAV muxer, so the header is written by hand (via
 * [WavHeaderWriter]; its size depends on the format) and its size fields are patched in as the
 * file grows and once it's finished. Writes go
 * through a [SegmentWriter] so the same code path works for a plain File and for a SAF-picked
 * destination (ParcelFileDescriptor), both of which support seeking back to byte 0 to patch the
 * header.
 *
 * The microphone capture itself never stops or gaps: once a segment reaches the session's
 * segment length (passed to [start]; [segmentMaxSeconds] -- 60 minutes of audio -- when the caller
 * doesn't specify one), the current file is finalized and a new one is opened via [NextTarget] for
 * the next segment, transparently to the caller.
 */
internal class WavRecorder(
    private val segmentMaxSeconds: Long = SEGMENT_MAX_SECONDS,
    private val threadJoinTimeoutMs: Long = THREAD_JOIN_TIMEOUT_MS,
    // Configurable (like the two above) purely so tests can shrink the real-time cost of
    // exercising the header-flush/disconnect-check cadence instead of waiting out the production
    // default every time.
    private val headerFlushIntervalMs: Long = HEADER_FLUSH_INTERVAL_MS,
    // Opens (negotiates) the audio source. Always called on the startup worker (see [startup]),
    // never on the caller's thread; the [StartupScope] receiver is its cancellation and timing.
    private val openAudioSource: StartupScope.(Context) -> RecorderConfig = { openBestAudioRecord(this, it) },
    private val wrapChannel: (FileChannel) -> SegmentWriter = { FileChannelSegmentWriter(it) },
    // Seam so tests can substitute a journal that fails on command, or inspect exactly what was
    // persisted/cleared without a real Context-backed SharedPreferences file.
    private val journalFor: (Context) -> ActiveSegmentJournal = { ActiveSegmentJournal(it) },
    // Seam so tests can exercise the WAV-size-limit rollover with a tiny limit instead of writing
    // gigabytes; production always uses the real plan for the session's format.
    private val splitPlanFor: (PcmFormat, Long, Int) -> SegmentSplitPlan = { format, seconds, buffer -> SegmentSplitPlan.of(format, seconds, buffer) },
    // Where opening the source runs and how its result comes back: production negotiates on the
    // shared audio-startup worker and delivers on the main thread; tests may run both inline.
    private val startup: AudioStartup = AudioStartup.shared,
    // Monotonic milliseconds for every interval the capture and writer loops measure (header
    // flushes, forced writes, health checks, the no-progress limit) -- never the wall clock, which
    // the user or the network can move backwards or forwards. Injectable for tests.
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    // How long capture may go without a single frame (not even silence) before the session is
    // ended as stalled; see NO_PROGRESS_TIMEOUT_MS.
    private val noProgressTimeoutMs: Long = NO_PROGRESS_TIMEOUT_MS,
    // Overrides the capture queue's size, in buffers (0: sized from the format; see
    // CAPTURE_QUEUE_SECONDS). Tests use a tiny queue to exercise overflow.
    private val maxQueuedBuffers: Int = 0,
    // A file's size in bytes, or null if it can't be determined -- how finalization tells whether a
    // failed write may have stored more than it confirmed. Seam so tests can make it unknown.
    private val targetSize: (Context, OutputTarget) -> Long? = ::sizeOf
) {

    companion object {
        private const val TAG = "WavRecorder"

        // Fallback segment length for a start() call that doesn't pass its own; RecordingService
        // always passes the user's chosen RecordingSplitDuration explicitly.
        private val SEGMENT_MAX_SECONDS = RecordingSplitDuration.DEFAULT.seconds

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

        // No audio at all -- not even silence -- for this long ends the session as stalled
        // (MicrophoneStalledException). Reads normally return every 20-100 ms, and capture that
        // Android silences (a call, the microphone privacy toggle) still returns zero-valued
        // samples, which count as audio. Ten seconds is far outside any transient reroute or HAL
        // restart, so a working recording is never ended by it, yet a dead one is reported well
        // before it could be mistaken for a working recording.
        private const val NO_PROGRESS_TIMEOUT_MS = 10_000L

        // The capture queue between the capture thread and the writer holds up to this much audio,
        // so storage may stall this long without a single frame being lost -- capped in bytes for
        // very high formats (10 s of 192 kHz 32-bit stereo is about 15 MB of the 48 MiB). Buffers
        // are allocated only as the queue actually fills, so memory grows only while storage lags.
        private const val CAPTURE_QUEUE_SECONDS = 10L
        private const val CAPTURE_QUEUE_MAX_BYTES = 48L * 1024 * 1024
        private const val MIN_QUEUE_BUFFERS = 4

        // How often the writer wakes with nothing to write: to notice a stop or a stalled capture.
        private const val WRITER_POLL_MS = 20L

        // The live level is posted to the UI at most this often -- the loudest peak since the last
        // update, so nothing is missed -- rather than once per read: a read can be far shorter
        // than a frame of the display, and each post is main-thread work.
        private const val AMPLITUDE_POST_INTERVAL_MS = 50L
    }

    /** Called each time a new segment file is needed: the first one, and every rollover after. */
    fun interface NextTarget {
        fun create(): OutputTarget
    }

    /**
     * An opened audio source and the one immutable [format] its whole session records in.
     * [bufferSize] is the read size; it's rounded to whole frames (and at least two) before use.
     * [negotiation] describes how the format was chosen (null for a test/fake source).
     *
     * [alreadyStarted] is the source's lifecycle state. True for [openBestAudioRecord]'s source:
     * negotiation starts each candidate to verify the device it is actually routed to, and hands
     * over the accepted one still recording -- its owner must use it as it is and never call
     * [AudioSource.startRecording], since stopping and restarting it could land on another route
     * without that check. False (test fakes): the owner starts it, as before.
     */
    class RecorderConfig(
        val source: AudioSource,
        val format: PcmFormat,
        val bufferSize: Int,
        val negotiation: NegotiatedAudio? = null,
        val alreadyStarted: Boolean = false
    ) {
        /** A 16-bit mono source -- the only format earlier versions recorded; used by tests. */
        constructor(source: AudioSource, sampleRate: Int, bufferSize: Int) :
            this(source, PcmFormat.pcm16Mono(sampleRate), bufferSize)

        val sampleRate: Int get() = format.sampleRate

        /** [bufferSize] rounded down to whole frames, never less than two frames. */
        internal val frameAlignedBufferSize: Int
            get() = maxOf(2 * format.bytesPerFrame, bufferSize - bufferSize % format.bytesPerFrame)
    }

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
        /** Every file closed cleanly and is a valid WAV, but writing failed before all the audio
         * captured reached it -- while recording, or while the audio already captured was still
         * being written after Stop -- so the session holds audio only up to the failure. [target]
         * is the last of its files that holds audio: the one the failure cut short, or an earlier
         * one when the failure came before any audio reached the file after it (which, empty, is
         * deleted). A valid partial save, not a complete one, and not one a header repair could
         * complete. [cause] is an [IncompleteRecordingException]. */
        data class Incomplete(val target: OutputTarget, val cause: Exception) : FinalizeResult()
        /** Every file closed cleanly, but writing failed before any of the session's audio reached
         * storage: the file it had started received none and was deleted, so nothing was saved.
         * [cause] is a [RecordingNotSavedException]. */
        data class NothingSaved(val cause: Exception) : FinalizeResult()
    }

    /** The recorder's lifecycle. IDLE -> STARTING ([start]: the source is being negotiated off
     * the main thread) -> RECORDING (accepted: the recording thread runs) -> STOPPING ([requestStop]
     * while the recording thread is still finishing its last file) -> IDLE. A stop during STARTING
     * cancels the startup and returns straight to IDLE: nothing was recorded. */
    enum class State { IDLE, STARTING, RECORDING, STOPPING }

    private var audioSource: AudioSource? = null
    private var recordingThread: Thread? = null
    private val isRecording = AtomicBoolean(false)

    // The startup in flight (STARTING), or null. Written only on the owner's (main) thread -- by
    // start(), requestStop() and the startup's own delivery -- so whichever of "accept the opened
    // source" and "cancel" happens first there wins, and the other sees the outcome: a delivery
    // for a scope that is no longer this one (cancelled or superseded) is declined, and the source
    // it carries is released on the startup worker, never used.
    @Volatile private var pendingStartup: StartupScope? = null

    // The recording thread a requestStop() left finishing its last file, for [state] (STOPPING).
    @Volatile private var finishingThread: Thread? = null
    @Volatile private var lastFinalizeResult: FinalizeResult = FinalizeResult.Ok

    /** How much audio the current (or most recently finished) session actually wrote, across all
     * of its files, in milliseconds -- counted from written frames, never from the wall clock.
     * Final once [awaitFinalization] has returned a joined result. */
    @Volatile var lastRecordedMillis: Long = 0L
        private set

    /** The last file of the current (or most recently finished) session that was finalized with
     * audio in it -- what the session saved, as opposed to the last file it opened, which may have
     * received no audio and been deleted. Null when nothing was saved. Final once
     * [awaitFinalization] has returned a joined result. */
    @Volatile var lastSavedTarget: OutputTarget? = null
        private set

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

    /** True while RECORDING. */
    val isActive: Boolean get() = isRecording.get()

    /** True while STARTING: a start is negotiating its audio source and hasn't been accepted yet. */
    val isStarting: Boolean get() = pendingStartup != null

    val state: State
        get() = when {
            pendingStartup != null -> State.STARTING
            isRecording.get() -> State.RECORDING
            finishingThread?.isAlive == true -> State.STOPPING
            else -> State.IDLE
        }

    /** The format of the current (or most recently started) session -- exactly what its WAV files
     * contain -- known once its audio source has opened; null before that, or if the most recent
     * start attempt failed to open one. Never changes for the rest of that session. */
    @Volatile var sessionFormat: PcmFormat? = null
        private set

    /** How [sessionFormat] was negotiated (null for a test/fake source). */
    @Volatile var sessionNegotiation: NegotiatedAudio? = null
        private set

    /** Where the current session splits files: the chosen duration, or earlier when the format
     * would otherwise outgrow a WAV file (see [SegmentSplitPlan]). */
    @Volatile var sessionSplitPlan: SegmentSplitPlan? = null
        private set

    /** The format Android captures from the device in, when known -- see [AudioSource.deviceSideFormat]. */
    @Volatile var sessionDeviceFormat: DeviceSideFormat? = null
        private set

    /** How many times the current session has split early because of the WAV size limit. */
    @Volatile var riffLimitedSplits: Int = 0
        private set

    /**
     * Begins a session: returns at once, in STARTING, while the audio source is negotiated and
     * started on the startup worker (see [AudioStartup]) -- the caller's (main) thread never waits
     * for it. Once it's ready, on the caller's thread again:
     *  - [acceptMicrophone] may refuse the verified microphone (returning the error to report), in
     *    which case the source is released and no file is ever created;
     *  - otherwise the session becomes RECORDING: the recording thread starts (and creates the
     *    first file), then [onMicrophoneInfo] reports the verified microphone. It fires last, so a
     *    caller may stop the session from inside it.
     * A failed startup reports [onError] without ever creating a file -- including one the audio
     * worker refuses because an earlier release is stalled ([MicrophoneBusyException]) and one its
     * watchdog ends ([MicrophoneStartTimeoutException]): STARTING never lasts longer than
     * [StartupLimits.startupTimeoutMs], and a source that opens after that is released, never
     * recorded from. A [requestStop] before the source is ready cancels the startup: nothing
     * further is reported for it, and whatever it had opened is released on the worker. Only one
     * start can be in flight; a start while STARTING or RECORDING is ignored -- so the format is
     * negotiated only here, never while a session is recording.
     *
     * [segmentMaxSeconds] is how much audio each file of *this* session may hold before rolling
     * over. It's converted to a byte limit exactly once, when the source is ready, and handed to
     * this session's own recording thread -- nothing can change it for the rest of the session. A
     * non-positive value is ignored in favor of the constructor's default rather than producing a
     * zero-length split.
     */
    @SuppressLint("MissingPermission")
    fun start(
        context: Context,
        nextTarget: NextTarget,
        onSegmentStarted: (OutputTarget) -> Unit,
        onAmplitude: (Float) -> Unit,
        onError: (Exception) -> Unit,
        onMicrophoneInfo: (MicrophoneInfo) -> Unit = {},
        segmentMaxSeconds: Long = this.segmentMaxSeconds,
        acceptMicrophone: (MicrophoneInfo) -> Exception? = { null }
    ) {
        if (isRecording.get() || pendingStartup != null) return

        // Every start() *attempt* -- successful or not -- gets its own session id and a freshly
        // reset result, before any fallible step below runs. This is what makes finalization
        // state properly session-scoped: a startup failure (mic busy, hardware init rejected --
        // before a recording thread, and therefore any real finalization attempt, ever exists)
        // must never let stop() hand back a *previous* session's stale FinalizeResult/target.
        // Bumping sessionCounter here (not only on success, as before) also closes the gap where
        // a genuinely wedged thread from an earlier, timed-out stop() could otherwise still match
        // the session counter and overwrite this fresh reset out from under a later, unrelated
        // failure.
        val mySession = sessionCounter.incrementAndGet()
        lastFinalizeResult = FinalizeResult.Ok
        lastRecordedMillis = 0L
        lastSavedTarget = null
        sessionFormat = null
        sessionNegotiation = null
        sessionSplitPlan = null
        sessionDeviceFormat = null
        riffLimitedSplits = 0
        val sessionSegmentSeconds = if (segmentMaxSeconds > 0) segmentMaxSeconds else this.segmentMaxSeconds

        // Recorded as current before launching: the result may be delivered at any point after,
        // even before launch() returns (an inline test executor).
        val scope = startup.newScope()
        pendingStartup = scope
        // AudioRecord setup can fail on real devices (mic in use by another app or a call,
        // hardware quirks), and negotiation may try many candidates: all of it happens on the
        // startup worker, and a failure arrives here as onFailed rather than as an exception on
        // the caller's thread.
        startup.launch(
            scope, context, openAudioSource,
            onReady = { config ->
                if (pendingStartup !== scope) {
                    // Cancelled or superseded meanwhile: AudioStartup releases the source.
                    AudioStartup.Decision.Discard()
                } else {
                    activate(
                        scope, config, mySession, context, nextTarget, sessionSegmentSeconds,
                        onSegmentStarted, onAmplitude, onError, onMicrophoneInfo, acceptMicrophone
                    )
                }
            },
            onFailed = { e ->
                if (pendingStartup === scope) {
                    pendingStartup = null
                    onError(e)
                }
            }
        )
    }

    /**
     * The accepted source becomes this session: runs on the caller's thread when the startup
     * delivers. When the session can't use it, the source is handed back
     * ([AudioStartup.Decision.Discard]) to be released on the startup worker; the session stays
     * STARTING until that release has finished, and only then is the reason reported -- unless a
     * stop cancelled the start meanwhile, in which case nothing is.
     */
    private fun activate(
        scope: StartupScope,
        config: RecorderConfig,
        mySession: Int,
        context: Context,
        nextTarget: NextTarget,
        sessionSegmentSeconds: Long,
        onSegmentStarted: (OutputTarget) -> Unit,
        onAmplitude: (Float) -> Unit,
        onError: (Exception) -> Unit,
        onMicrophoneInfo: (MicrophoneInfo) -> Unit,
        acceptMicrophone: (MicrophoneInfo) -> Exception?
    ): AudioStartup.Decision {
        val source = config.source
        // Captured once: this session's format (and therefore every segment's header, byte
        // counter and split point) can't change after this line.
        val format = config.format
        val bufferSize = config.frameAlignedBufferSize
        // Reported once the declined source's release has finished (see activate()'s doc).
        fun declined(report: () -> Unit) = AudioStartup.Decision.Discard {
            if (pendingStartup === scope) {
                pendingStartup = null
                report()
            }
        }
        val splitPlan = try {
            splitPlanFor(format, sessionSegmentSeconds, bufferSize)
        } catch (e: Exception) {
            return declined { onError(e) }
        }

        val microphone = source.describeMicrophone()
        acceptMicrophone(microphone)?.let { refusal ->
            // Not this session's microphone (see RecordingService.startRecording's
            // requireExternalMic): reported, never recorded from -- no thread, no file.
            return declined {
                onMicrophoneInfo(microphone)
                onError(refusal)
            }
        }

        pendingStartup = null
        sessionFormat = format
        sessionNegotiation = config.negotiation
        sessionSplitPlan = splitPlan
        if (splitPlan.limitedByRiff) {
            Log.i(TAG, "Files will split every ${"%.1f".format(splitPlan.effectiveSeconds / 60)} min instead " +
                "of the chosen ${sessionSegmentSeconds / 60} min: $format would otherwise exceed the WAV size limit")
        }
        sessionDeviceFormat = source.deviceSideFormat()
        sessionDeviceFormat?.let { device ->
            if (device.differsFrom(format)) {
                Log.i(TAG, "Android converts this capture: device side $device, saved as $format")
            }
        }

        audioSource = source
        val myGeneration = generation.incrementAndGet()
        isRecording.set(true)
        recordingThread = Thread({
            recordLoop(
                myGeneration, mySession, context, source, nextTarget, bufferSize, format,
                splitPlan, onSegmentStarted, onAmplitude, onError
            )
        }, "WavRecorderThread").apply { start() }

        // Last, once the session is fully RECORDING: a caller reacting to the verified microphone
        // may stop it right here, and nothing after this line touches the session.
        onMicrophoneInfo(microphone)
        return AudioStartup.Decision.Keep
    }

    /**
     * What [requestStop] set in motion, for [awaitFinalization]: the recording thread still
     * finishing its last file (null if none was running), and the source's stop-and-release on the
     * audio worker (null if there was no active source).
     */
    class PendingStop internal constructor(val thread: Thread?, val sourceReleased: AudioStartup.Completion?)

    /**
     * Stops the session and *blocks* until it has finalized: [requestStop] then [awaitFinalization].
     * Must never be called on the main thread (it waits for threads and native calls; production
     * code calls the two halves separately, waiting on a background thread) nor from the recording
     * thread itself (joining it on itself would deadlock).
     *
     * Returns whether the just-stopped session's final segment actually finalized cleanly.
     * [recordLoop]'s finally block runs entirely on the recording thread before it terminates, so
     * by the time [Thread.join] returns having actually joined (not timed out), that result is
     * already known -- callers use this to decide whether it's safe to report a plain "saved" or
     * whether the file may need recovery, instead of always assuming success.
     */
    fun stop(): FinalizeResult = awaitFinalization(requestStop())

    /**
     * The non-blocking half of [stop], safe on the main thread: it never waits on a lock, latch,
     * thread or native call. It changes the session's logical state at once -- [isRecording]
     * false, [generation] bumped (so nothing already posted by the stopped session is delivered),
     * a STARTING start cancelled -- and hands the active source to the audio worker
     * ([AudioStartup.shutdown]) to be stopped and released there: native `AudioRecord.stop()` and
     * `release()` can stall on a misbehaving USB driver or audio HAL, and that must stall the
     * worker, not the UI. Stopping the source is also what unblocks the recording thread if it is
     * waiting in [AudioSource.read]. Because every startup runs on that same worker, a new session
     * can't open a microphone until this one's release has finished.
     *
     * Idempotent: [recordLoop] flips [isRecording] false itself on a fatal error, before this is
     * called, and this still releases the source. A subsequent [start] may begin as soon as this
     * returns. Returns what [awaitFinalization] waits for.
     */
    fun requestStop(): PendingStop {
        // A start still negotiating is cancelled: its delivery will be declined and whatever it
        // opened released on the startup worker. Nothing was recorded, so the session's finalize
        // result stays the Ok that start() reset it to, with no file and no thread to wait for.
        pendingStartup?.let {
            startup.cancel(it)
            pendingStartup = null
        }
        isRecording.set(false)
        // Invalidate this session's generation too: a callback the recording thread already
        // posted to the main looper before observing isRecording=false (e.g. the amplitude
        // update for the read right before this stop()) would otherwise still be delivered,
        // updating the UI for a session that's already stopped. This doesn't affect the
        // recording thread's own finalization of its current segment, which never consults
        // generation -- only the *dispatch* of callbacks does.
        generation.incrementAndGet()
        val released = audioSource?.let { startup.shutdown(it) }
        audioSource = null

        val thread = recordingThread
        recordingThread = null
        if (thread != null) finishingThread = thread
        return PendingStop(thread, released)
    }

    /**
     * The blocking half of [stop] -- background threads only: waits up to [threadJoinTimeoutMs]
     * for the recording thread (from [requestStop]) to finish its last file, then, within what's
     * left of that time, for the source's release on the audio worker; returns the finalize result
     * exactly as before.
     *
     * The result describes the *file*: a recording thread that didn't finish in time is
     * [FinalizeResult.Unknown] (it may still be running on its own; [generation] keeps it from
     * touching a later session). A release that is still pending once the thread has finished
     * doesn't change the file's result -- it is logged, and the worker finishes it on its own; no
     * new microphone can be opened before it does.
     */
    fun awaitFinalization(pending: PendingStop): FinalizeResult {
        val deadline = System.nanoTime() + threadJoinTimeoutMs * 1_000_000
        pending.thread?.let { thread ->
            thread.join(threadJoinTimeoutMs)
            if (thread.isAlive) {
                // Stopping the AudioSource didn't free it up (it's stuck somewhere else, e.g.
                // file I/O, or the stop itself is stalled). There's no safe way to force a thread
                // down from here; interrupting a thread blocked in a native/file syscall generally
                // doesn't unblock it either. Give up waiting rather than hang the caller
                // indefinitely -- whatever the thread is stuck on will eventually return, at which
                // point it'll find (via [generation]) that it's no longer the current session and
                // back off quietly.
                thread.interrupt()
                return FinalizeResult.Unknown
            }
        }
        pending.sourceReleased?.let { released ->
            val remainingMs = maxOf(0L, (deadline - System.nanoTime()) / 1_000_000)
            if (!released.await(remainingMs)) {
                Log.w(TAG, "The microphone is still being released by the audio system; the recording itself is finalized")
            }
        }
        return lastFinalizeResult
    }

    /** One captured chunk of whole frames, handed from the capture thread to the writer. */
    private class Chunk(val bytes: ByteArray) {
        var length = 0
    }

    /**
     * The bounded, reusable buffers between a session's capture thread and its writer. At most
     * [capacity] buffers ever exist, allocated only as the queue actually fills; the capture thread
     * never waits for one -- running out is an overflow it reports ([StorageTooSlowException]),
     * never audio dropped silently, and never unbounded memory.
     */
    private class CaptureQueue(private val bufferSize: Int, private val capacity: Int) {
        private val free = ArrayBlockingQueue<Chunk>(capacity)
        // Chunks (at most [capacity] of them) and one END marker: never blocks the capture thread.
        private val filled = LinkedBlockingQueue<Any>()
        private var created = 0 // capture thread only

        /** A buffer to read into, or null when every buffer is queued for the writer (overflow). */
        fun acquire(): Chunk? = free.poll() ?: if (created < capacity) Chunk(ByteArray(bufferSize)).also { created++ } else null

        fun recycle(chunk: Chunk) {
            free.offer(chunk)
        }

        fun submit(chunk: Chunk) = filled.put(chunk)

        /** The capture thread is done: nothing more will be submitted. */
        fun end() = filled.put(END)

        fun take(timeoutMs: Long): Any? = filled.poll(timeoutMs, TimeUnit.MILLISECONDS)

        fun takeNow(): Any? = filled.poll()

        companion object {
            val END = Any()
        }
    }

    /** When the capture thread last accepted audio (monotonic ms): what the writer's no-progress
     * check watches -- updated while storage lags, frozen while the microphone delivers nothing. */
    private class CaptureProgress(start: Long) {
        @Volatile var lastAudioAt = start
    }

    private fun queueCapacity(format: PcmFormat, bufferSize: Int): Int {
        if (maxQueuedBuffers > 0) return maxQueuedBuffers
        val bytes = minOf(format.byteRate * CAPTURE_QUEUE_SECONDS, CAPTURE_QUEUE_MAX_BYTES)
        return maxOf(MIN_QUEUE_BUFFERS.toLong(), bytes / bufferSize).toInt()
    }

    /**
     * The session's writer, on its recording thread: the only thread that touches its files --
     * opening segments, writing PCM, header flushes and forced writes, rollover, the journal,
     * finalization. The microphone is read by a separate capture thread ([captureLoop]) into a
     * bounded queue, so slow storage (a stalled write, a slow provider creating the next file, a
     * forced flush) never stops the microphone being drained.
     *
     * Stopping: once the session is stopped (or fails), every chunk the capture thread accepted is
     * still written before the file is finalized. The capture thread normally ends at once (the
     * stop unblocks its read); if its read never returns, the writer waits at most half the stop
     * join timeout for it, then writes what was accepted and finalizes regardless -- a stuck native
     * read can't keep the file unfinalized.
     */
    // segmentFinalized's `true` assignment below is flagged as an unused value: the compiler's
    // dataflow analysis doesn't model openSegment() throwing between it and the following `false`
    // reset, but that exception path is exactly why the flag exists -- see its declaration.
    @Suppress("UNUSED_VALUE")
    private fun recordLoop(
        myGeneration: Int,
        mySession: Int,
        context: Context,
        source: AudioSource,
        nextTarget: NextTarget,
        bufferSize: Int,
        format: PcmFormat,
        splitPlan: SegmentSplitPlan,
        onSegmentStarted: (OutputTarget) -> Unit,
        onAmplitude: (Float) -> Unit,
        onError: (Exception) -> Unit
    ) {
        val handler = Handler(Looper.getMainLooper())
        var segment = try {
            // previousToken = null: this is the very first segment of the session, so the journal
            // must currently be empty -- see persistActive()'s expectedPreviousToken doc.
            openSegment(myGeneration, context, nextTarget, handler, onSegmentStarted, format, previousToken = null)
        } catch (e: Exception) {
            reportFatal(myGeneration, handler, onError, e)
            return
        }
        // True exactly when `segment` has already been finalized (closeSegment succeeded or
        // threw) and must not be finalized again. Rollover finalizes the current segment and then
        // opens the next one as two separate steps; if opening the next one fails, `segment`
        // still refers to the just-finalized (already closed) one -- without this flag, the
        // outer `finally` below would call closeSegment on it a second time, patching a header
        // onto an already-closed writer and misreporting a perfectly healthy, already-saved file
        // as needing recovery.
        var segmentFinalized = false
        var recordedBytes = 0L
        // The last file finalized with audio in it: what the session has saved so far. A file that
        // received no audio is deleted when it is closed, so it is never what was saved.
        var savedTarget: OutputTarget? = null
        // A file whose finalization failed (header patch or writer close): it may need recovery,
        // which outranks anything else the result could say.
        var finalizationFailure: FinalizeResult.Failed? = null
        // A failure closing a file that held no audio and was then confirmed deleted: it cost no
        // audio, so it only explains a session that saved nothing (see the result below).
        var discardFailure: Exception? = null

        /** Finalizes the current file, exactly once: records it as saved if it holds audio, or the
         * failure if finalizing it failed -- and then rethrows that. */
        fun finalizeCurrent() {
            segmentFinalized = true
            val end = try {
                closeSegment(context, segment, format)
            } catch (e: Exception) {
                finalizationFailure = FinalizeResult.Failed(segment.target, e)
                // Finalizing closed the file, whatever failed, and nothing -- not this thread, not
                // the capture thread, which never touches files -- will write to it again: its
                // record moves to the recovery queue, so recovery still finds it, and it doesn't
                // block the next recording. (A writer still stuck in here never gets this far, so its
                // record is never handed over early.)
                settleJournalRecord(context, segment, ActiveSegmentJournal.Settle.RECOVER)
                throw e
            }
            when (end) {
                SegmentEnd.Saved -> savedTarget = segment.target
                is SegmentEnd.NoAudio -> end.closeFailure?.let { if (discardFailure == null) discardFailure = it }
            }
        }

        fun rollover() {
            val justClosedToken = segment.token
            // closeSegment() throws on a genuine finalization failure (header patch or writer
            // close): that propagates to the caller as fatal, instead of silently opening a new
            // segment and continuing as if nothing happened.
            finalizeCurrent()
            // previousToken = the just-closed segment's own token: whether or not settling its
            // record actually succeeded, this new segment is the only thing allowed to
            // legitimately supersede it in the ACTIVE slot.
            segment = openSegment(myGeneration, context, nextTarget, handler, onSegmentStarted, format, previousToken = justClosedToken)
            segmentFinalized = false
        }

        fun write(chunk: Chunk) {
            val whole = chunk.length
            // Belt and braces for the WAV size limit: splitPlan's threshold already keeps a margin
            // far larger than any read, so this never triggers in practice -- but no write may ever
            // take a segment past what its header can describe.
            if (segment.audioLen > 0 && segment.audioLen + whole > splitPlan.hardLimitBytes) rollover()
            val pcm = ByteBuffer.wrap(chunk.bytes, 0, whole)
            try {
                writeFully(segment.writer, pcm)
            } catch (e: Exception) {
                // The whole frames the writer accepted before failing are in the file, right after
                // the audio before them: they are audio, finalized with the rest -- never discarded
                // as if nothing had been written. Past them, the file's contents are unconfirmed
                // (see settleWithoutAudio).
                val accepted = format.alignToFrame(pcm.position().toLong())
                segment.audioLen += accepted
                recordedBytes += accepted
                if (segment.failedWrite == null) segment.failedWrite = e
                throw e
            }
            segment.audioLen += whole
            recordedBytes += whole
            val now = clock()
            if (now - segment.lastHeaderFlush >= headerFlushIntervalMs) {
                flushHeader(segment, format)
                segment.lastHeaderFlush = now
            }
            if (now - segment.lastForce >= DATA_FORCE_INTERVAL_MS) {
                forceSegment(segment)
                segment.lastForce = now
            }
            if (segment.audioLen >= splitPlan.effectiveLimitBytes) {
                if (splitPlan.limitedByRiff) {
                    riffLimitedSplits++
                    Log.i(TAG, "Starting a new file early at ${segment.audioLen} bytes: the WAV size " +
                        "limit for $format comes before the chosen split duration")
                }
                rollover()
            }
        }

        val queue = CaptureQueue(bufferSize, queueCapacity(format, bufferSize))
        val progress = CaptureProgress(clock())
        // Capture starts only once the first file is ready, so nothing is read with nowhere to go.
        Thread({
            captureLoop(myGeneration, source, format, queue, progress, handler, onAmplitude, onError)
        }, "WavRecorderCapture").start()
        val readerGraceMs = maxOf(1L, threadJoinTimeoutMs / 2)
        var stopNoticedAt = -1L
        // The first write that failed -- disk full, a revoked SAF permission, a closed channel, a
        // rollover that can't finalize or open. Kept for this session whatever happens next: after
        // Stop there is no live callback left to report it to, yet audio that was captured is then
        // missing from the file, which the result must say (FinalizeResult.Incomplete).
        var writeFailure: Exception? = null

        /** Writes [chunk]; false (the failure recorded and reported) if that failed. */
        fun writeOrFail(chunk: Chunk): Boolean = try {
            write(chunk)
            true
        } catch (e: Exception) {
            if (writeFailure == null) writeFailure = e
            reportFatal(myGeneration, handler, onError, e)
            false
        } finally {
            queue.recycle(chunk)
        }

        /** Writes whatever capture has already queued, without waiting for more. */
        fun drainQueued() {
            while (true) {
                val rest = queue.takeNow() as? Chunk ?: return
                if (!writeOrFail(rest)) return
            }
        }

        try {
            while (true) {
                val item = try {
                    queue.take(WRITER_POLL_MS)
                } catch (_: InterruptedException) {
                    // Only a stop that gave up waiting for this thread interrupts it: write what
                    // is already queued, then finalize.
                    drainQueued()
                    break
                }
                if (item === CaptureQueue.END) break
                if (item is Chunk && !writeOrFail(item)) break
                val now = clock()
                if (isRecording.get() && generation.get() == myGeneration) {
                    // A microphone that delivers nothing at all -- a read that never returns, or
                    // one that keeps returning no data -- ends the session rather than leaving it
                    // "recording" nothing. Measured from what capture accepted, so slow storage
                    // here never counts against the microphone.
                    if (now - progress.lastAudioAt >= noProgressTimeoutMs) {
                        reportFatal(
                            myGeneration, handler, onError,
                            MicrophoneStalledException("The microphone delivered no audio for ${noProgressTimeoutMs / 1000} s")
                        )
                    }
                } else if (stopNoticedAt < 0) {
                    stopNoticedAt = now
                } else if (now - stopNoticedAt >= readerGraceMs) {
                    // The capture thread hasn't ended (its read never returned): write what it had
                    // already accepted, and finalize without it.
                    drainQueued()
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
                // See segmentFinalized's doc above: `segment` was already finalized by the
                // rollover branch above -- whether that succeeded, or failed and was recorded --
                // so it must not be finalized (and its journal record cleared) a second time here.
                if (!segmentFinalized) finalizeCurrent()
            } catch (_: Exception) {
                // Recorded in finalizationFailure.
            }
            if (sessionCounter.get() == mySession) {
                // A finalization failure may need recovery, whatever else happened. Otherwise
                // cleanly closed files are a complete save only if nothing failed to reach them;
                // if something did, the result names the last file that actually holds audio --
                // or says nothing was saved, rather than point at a file deleted for being empty.
                val saved = savedTarget
                // A failure closing an empty, deleted file cost no audio: it only matters when
                // nothing at all was saved -- then it is why.
                val failure = writeFailure ?: discardFailure?.takeIf { saved == null }
                if (failure == null && discardFailure != null) {
                    Log.w(TAG, "A file that held no audio failed to close, then was deleted; no audio was lost", discardFailure)
                }
                lastFinalizeResult = finalizationFailure ?: when {
                    failure == null -> FinalizeResult.Ok
                    saved != null -> FinalizeResult.Incomplete(saved, IncompleteRecordingException(failure))
                    else -> FinalizeResult.NothingSaved(RecordingNotSavedException(failure))
                }
                lastSavedTarget = saved
                lastRecordedMillis = recordedBytes * 1000 / format.byteRate
            }
        }
    }

    /**
     * The session's capture thread: reads the microphone into [queue] as fast as it delivers,
     * whole frames only, and never touches a file -- so storage latency can't stop it. It also
     * runs the route and disconnect checks, on their own monotonic cadence whatever the reads
     * return, so a source that keeps returning nothing is still checked.
     */
    private fun captureLoop(
        myGeneration: Int,
        source: AudioSource,
        format: PcmFormat,
        queue: CaptureQueue,
        progress: CaptureProgress,
        handler: Handler,
        onAmplitude: (Float) -> Unit,
        onError: (Exception) -> Unit
    ) {
        // An incomplete trailing frame from the previous read, put back at the start of the next
        // buffer: only whole frames are ever queued, so every byte count, header and split point
        // stays frame-aligned even if a read ever ends mid-frame.
        val carry = ByteArray(format.bytesPerFrame)
        var carryLen = 0
        val emptyReads = EmptyReadBackoff()
        var lastHealthCheck = clock()
        var peakSincePost = 0f
        var lastAmplitudePost = Long.MIN_VALUE

        try {
            while (isRecording.get() && generation.get() == myGeneration) {
                var chunk: Chunk? = null
                try {
                    val now = clock()
                    if (now - lastHealthCheck >= headerFlushIntervalMs) {
                        lastHealthCheck = now
                        // A real AudioRecord can keep handing back samples for a short while after
                        // its routed device is actually gone (buffered/stale data, or silently
                        // re-routed to the phone mic), so this doesn't need read()-level
                        // responsiveness -- it just must not go unnoticed for long.
                        if (!source.isDeviceConnected()) {
                            throw MicrophoneDisconnectedException("External microphone disconnected during recording")
                        }
                        // Checked in addition to (not instead of) isDeviceConnected(): a silent
                        // reroute away from the verified device may never fire a removal callback.
                        if (!source.isRouteUnchanged()) {
                            throw MicrophoneRouteChangedException(
                                "Recording input changed unexpectedly; no longer using the verified external microphone"
                            )
                        }
                    }
                    // Never waits for the writer: with every buffer still queued, capture would
                    // otherwise stop draining the microphone, whose own buffer would then overrun
                    // unseen. A full queue is reported instead -- whatever the source's apparent
                    // pace, since an audio clock may drift from the system clock.
                    chunk = queue.acquire() ?: throw StorageTooSlowException(
                        "Storage fell more than the capture queue behind; recording stopped rather than lose audio"
                    )
                    if (carryLen > 0) System.arraycopy(carry, 0, chunk.bytes, 0, carryLen)
                    val read = source.read(chunk.bytes, carryLen, chunk.bytes.size - carryLen)

                    // source.read() can block for an arbitrary time (a hung driver, a disconnected
                    // mic). If the session was stopped meanwhile -- or superseded by a newer one --
                    // this data belongs to nobody: drop it quietly, before it touches any state or
                    // callback.
                    if (generation.get() != myGeneration || !isRecording.get()) break

                    if (read < 0) {
                        // A negative result usually means a real AudioSource failure (another app
                        // seized the mic, a dead object, etc.); after an intentional stop the check
                        // above already ended this loop quietly.
                        throw IllegalStateException("Recording stopped unexpectedly (AudioRecord error $read)")
                    }
                    if (read == 0) {
                        // Retried after a short, growing pause rather than immediately: see
                        // EmptyReadBackoff. The writer's no-progress check ends the session if this
                        // goes on too long. (An interrupt while waiting lands in the catch below.)
                        emptyReads.onEmpty()
                        continue
                    }
                    emptyReads.onData()
                    progress.lastAudioAt = clock()

                    val available = carryLen + read
                    val whole = available - available % format.bytesPerFrame
                    val leftover = available - whole
                    if (whole == 0) {
                        System.arraycopy(chunk.bytes, 0, carry, 0, available)
                        carryLen = available
                        continue
                    }
                    if (leftover > 0) System.arraycopy(chunk.bytes, whole, carry, 0, leftover)
                    carryLen = leftover
                    chunk.length = whole
                    peakSincePost = maxOf(peakSincePost, peakAmplitude(chunk.bytes, whole, format))
                    queue.submit(chunk)
                    chunk = null // now the writer's
                    val postedAt = clock()
                    if (lastAmplitudePost == Long.MIN_VALUE || postedAt - lastAmplitudePost >= AMPLITUDE_POST_INTERVAL_MS) {
                        val amplitude = peakSincePost
                        peakSincePost = 0f
                        lastAmplitudePost = postedAt
                        postIfCurrent(myGeneration, handler) { onAmplitude(amplitude) }
                    }
                } catch (e: Exception) {
                    reportFatal(myGeneration, handler, onError, e)
                    break
                } finally {
                    chunk?.let(queue::recycle)
                }
            }
        } finally {
            queue.end()
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

    private class Segment(
        val target: OutputTarget,
        val writer: SegmentWriter,
        val pfd: ParcelFileDescriptor?,
        /** Uniquely identifies this segment for [ActiveSegmentJournal.settleFinishedSegment] -- a
         * random UUID (not a resettable in-process counter), so it can never collide with a
         * still-outstanding record from an *earlier* process -- see [ActiveSegmentJournal]'s own
         * doc for why that matters. */
        val token: String,
        /** Keeps the file from being deleted until it is finalized (see [RecordingOwnership]). */
        val claim: RecordingOwnership.Claim,
        /** When it was opened, in [clock] time: the header-flush and forced-write cadence start here. */
        openedAt: Long
    ) {
        /** Audio confirmed written: whole frames the writer accepted. */
        var audioLen = 0L
        var lastHeaderFlush = openedAt
        var lastForce = openedAt
        /** The first audio write to this file that failed. Past [audioLen], the file's contents
         * are then unconfirmed: the writer may have stored more than it reported. */
        var failedWrite: Exception? = null
    }

    /** What finalizing a segment left behind; a failure to finalize one is thrown instead. */
    private sealed class SegmentEnd {
        /** Finalized cleanly, holding audio. */
        object Saved : SegmentEnd()
        /** It held no audio, and nothing is left to recover: deleted -- or, if that couldn't be
         * confirmed, a valid WAV with no audio. [closeFailure] is a failure closing it that cost no
         * audio, as the file was confirmed deleted afterward. */
        class NoAudio(val closeFailure: Exception?) : SegmentEnd()
    }

    /**
     * Rewrites the header in place. Must never return having left the writer positioned anywhere
     * other than [Segment]'s pre-flush append point: a header-patch failure alone (the seek to 0
     * succeeding but the header bytes failing to write, or vice versa) is recoverable as long as
     * the append position is confirmed restored afterward -- the header is just stale until the
     * next periodic flush or the final close patches it correctly, which is not fatal on its own.
     * But if restoring that position itself fails, there is no way to know where the writer
     * actually ended up, and letting the caller keep appending PCM data from an unconfirmed
     * position could silently overwrite the header or already-written audio. That case is not
     * swallowed: it's rethrown so the caller (recordLoop) routes it through the normal
     * fatal-error path, which still finalizes (or reports needing recovery) via closeSegment,
     * exactly like any other fatal failure -- never left to just keep writing from an unsafe spot.
     */
    private fun flushHeader(segment: Segment, format: PcmFormat) {
        val writePosition = segment.writer.position()
        try {
            // A periodic flush never counts a pad byte: the file doesn't have one until the
            // segment is finalized (see closeSegment).
            patchWavHeader(segment.writer, segment.audioLen, format, includePadByte = false)
        } catch (_: Exception) {
            // Deliberately not rethrown here: whether this succeeded or not is irrelevant to
            // safety on its own -- only whether the position restore below succeeds is.
        }
        try {
            segment.writer.position(writePosition)
        } catch (e: Exception) {
            throw IOException(
                "Failed to restore the write position after a header flush; cannot safely " +
                    "continue recording without risking overwriting audio already on disk",
                e
            )
        }
    }

    private fun openSegment(
        myGeneration: Int,
        context: Context,
        nextTarget: NextTarget,
        handler: Handler,
        onSegmentStarted: (OutputTarget) -> Unit,
        format: PcmFormat,
        // The token this segment's own journal write is allowed to legitimately supersede -- see
        // ActiveSegmentJournal.persistActive()'s expectedPreviousToken doc. null for the very
        // first segment of a session (expecting the ACTIVE slot to already be empty); the
        // just-closed segment's own token for every rollover after.
        previousToken: String?
    ): Segment {
        // Created, claimed, then checked to still exist: a delete from the Library that races the
        // creation makes this create another file, and once claimed nothing can delete it -- see
        // RecordingOwnership.createClaimed. (Opening a deleted local file below would silently
        // recreate it, hence the explicit check.)
        val (target, claim) = RecordingOwnership.createClaimed(
            RecordingOwnership.Use.CAPTURING,
            exists = { created -> targetExists(context, created) }
        ) { nextTarget.create() }
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
            // A valid, zero-length header -- not the old 44-zero-byte placeholder -- so that a
            // process death before this segment's very first periodic header flush (up to
            // headerFlushIntervalMs away) still leaves a parseable, playable (silent) WAV file
            // rather than an unreadable one. See WavRecoveryManager for the complementary
            // durable-journal-driven recovery of everything captured *after* this point.
            writeFully(writer, WavHeaderWriter.build(format, 0L))
            val token = UUID.randomUUID().toString()
            // Fail-before-capture policy: if this app cannot durably establish the crash-recovery
            // guarantee for this segment, it must not proceed to capture PCM believing that
            // guarantee exists. A silently-ignored journal write here previously meant recording
            // could carry on with zero crash protection and no indication to anyone. The catch
            // block below (shared with every other openSegment() failure) cleans up and deletes
            // this partially-opened segment exactly like any other open failure.
            val persisted = journalFor(context).persistActive(token, previousToken, target, format)
            if (!persisted) {
                throw IOException(
                    "Could not durably record this segment for crash recovery; refusing to start " +
                        "capturing audio without that guarantee -- either the durable write itself " +
                        "failed, or an unrelated, still-unclaimed segment record already occupies " +
                        "the active slot"
                )
            }
            postIfCurrent(myGeneration, handler) { onSegmentStarted(target) }
            return Segment(target, writer, pfd, token, claim, clock())
        } catch (e: Exception) {
            // Without this, any of the failures above would leak the FileChannel/RandomAccessFile
            // (or, for SAF, the ParcelFileDescriptor's underlying fd) and leave a partial/empty
            // file behind that closeSegment() never gets a chance to clean up, since a Segment was
            // never successfully constructed.
            try { channel?.close() } catch (_: Exception) {}
            try { raf?.close() } catch (_: Exception) {}
            try { pfd?.close() } catch (_: Exception) {}
            deleteSegmentFile(context, target)
            claim.release()
            throw e
        }
    }

    /**
     * A segment file is created (and [onSegmentStarted] fired) up front, before any audio has
     * actually been captured for it, so that rollover and the initial segment share one code
     * path. If the recording ends (rollover, stop, or a fatal error) before any audio was
     * written to it -- most commonly the still-open segment at the moment recording stops --
     * finalizing it would leave a silent, pointless 0-byte-audio WAV file behind in the user's
     * library, so it is deleted instead, once that is known to be safe (see [settleWithoutAudio]).
     *
     * A failure patching the final header or closing the writer of a file holding audio used to be
     * swallowed here, which meant a caller could report "Saved" for a file whose header never
     * actually got the right size written to it (or whose last buffered bytes never made it past
     * the writer). Both are now collected and thrown as a [WavFinalizationException] instead, and
     * the file's journal record is kept for recovery -- every other cleanup step (the fd close)
     * still runs first regardless, so this only ever reports a failure *after* doing everything it
     * can to leave the file in the best state possible.
     */
    private fun closeSegment(context: Context, segment: Segment, format: PcmFormat): SegmentEnd {
        // Still not deletable while it is finalized; released (deletable, like any finished
        // recording) once that is done, whatever the outcome.
        segment.claim.markFinalizing()
        try {
            return finalizeSegment(context, segment, format)
        } finally {
            segment.claim.release()
        }
    }

    private fun finalizeSegment(context: Context, segment: Segment, format: PcmFormat): SegmentEnd {
        // After a failed audio write the file may hold more than the writer confirmed: checked
        // first, before anything is written at the end of the confirmed audio -- where a pad byte
        // would overwrite such audio.
        val unconfirmed = segment.failedWrite?.takeIf { mayHoldUnconfirmedAudio(context, segment, format) }
        var failure: Exception? = null
        if (segment.audioLen > 0L) {
            try {
                // RIFF chunks are word-aligned: odd-length audio (possible with 3-byte samples)
                // gets the zero pad byte the specification requires, right after the data, which
                // the finished header then counts in the RIFF size -- unless audio may follow the
                // confirmed audio: then nothing is written there, and the header claims no pad.
                val padded = unconfirmed == null && segment.audioLen % 2 == 1L
                if (padded) {
                    segment.writer.position(WavHeaderWriter.headerSize(format) + segment.audioLen)
                    writeFully(segment.writer, ByteBuffer.wrap(byteArrayOf(0)))
                }
                patchWavHeader(segment.writer, segment.audioLen, format, includePadByte = unconfirmed == null)
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

        if (unconfirmed != null) {
            // Kept as it is -- never deleted or truncated -- with its journal record, for recovery to
            // settle from what is physically there. Any header or close failure rides along.
            Log.w(TAG, "${segment.target.displayPath} may hold audio past the ${segment.audioLen} bytes its " +
                "writer confirmed; kept for recovery")
            throw UnconfirmedAudioException(segment.target, unconfirmed).apply { failure?.let(::addSuppressed) }
        }
        if (segment.audioLen <= 0L) return settleWithoutAudio(context, segment, failure)
        // A file holding audio whose header patch or close failed may be truncated or carry a
        // stale header: its journal record is kept for recovery (see finalizeCurrent), so a
        // recovery pass (see WavRecoveryManager) still finds it and can repair it.
        if (failure != null) throw WavFinalizationException(segment.target, failure)
        settleJournalRecord(context, segment, ActiveSegmentJournal.Settle.CLEAR)
        return SegmentEnd.Saved
    }

    /**
     * Whether a file whose audio write failed may hold whole frames past the audio its writer
     * confirmed: a failed write may store more than it reports. True when its size shows at least
     * one whole frame past the header and the confirmed audio -- or can't be determined, or is
     * smaller than what was confirmed (then it can't be right). Less than a frame past it is an
     * incomplete frame, which is not audio.
     */
    private fun mayHoldUnconfirmedAudio(context: Context, segment: Segment, format: PcmFormat): Boolean {
        val confirmedEnd = WavHeaderWriter.headerSize(format) + segment.audioLen
        val size = try { targetSize(context, segment.target) } catch (_: Exception) { null }
        return size == null || size < confirmedEnd || size - confirmedEnd >= format.bytesPerFrame
    }

    /**
     * Settles a segment no audio was confirmed written to -- normally deleting it, but only once it
     * is certain there is nothing in it to keep, and never reporting a file for recovery that is
     * gone. (One that may hold audio a failed write never confirmed never gets here: it is kept for
     * recovery -- see [finalizeSegment].)
     *  - Deleted and confirmed gone: nothing is left to recover, so the journal record is cleared,
     *    even if closing it had failed ([closeFailure] then comes back in [SegmentEnd.NoAudio]: it
     *    cost no audio).
     *  - Not confirmed gone after a failed close: whether a file remains, and in what state, is
     *    unknown -- so the failure is thrown and the record kept, as for any file that failed to
     *    finalize.
     *  - Not confirmed gone after a clean close: what remains is a valid WAV with no audio, so
     *    there is nothing to recover.
     */
    private fun settleWithoutAudio(context: Context, segment: Segment, closeFailure: Exception?): SegmentEnd {
        val target = segment.target
        val gone = deleteSegmentFile(context, target)
        if (!gone && closeFailure != null) throw WavFinalizationException(target, closeFailure)
        if (!gone) Log.w(TAG, "Could not confirm that ${target.displayPath}, which holds no audio, was deleted")
        settleJournalRecord(context, segment, ActiveSegmentJournal.Settle.CLEAR)
        return SegmentEnd.NoAudio(closeFailure)
    }

    /**
     * Settles [segment]'s durable "this segment might need recovery" record once this writer has
     * finished with the file for good (see [ActiveSegmentJournal.settleFinishedSegment]): cleared
     * when nothing is left to recover, moved to the recovery queue when the file may need
     * recovery. Token-specific, so a stale thread from an already-superseded session finishing late
     * never touches a *newer* segment's record.
     *
     * A settle that fails to reach storage is deliberately *not* escalated to a finalization
     * failure: the file itself is already settled at this point, and the record is left exactly as
     * it was -- still recoverable -- and settled again before the next segment is recorded. Still
     * surfaced (never silently dropped), per the honest-degraded-state policy documented on
     * ActiveSegmentJournal.
     */
    private fun settleJournalRecord(context: Context, segment: Segment, settle: ActiveSegmentJournal.Settle) {
        // A stop that gave up waiting interrupts this thread to unblock its file work (see
        // awaitFinalization); that work is over now, and the interrupt mustn't fail the durable
        // write that follows (SharedPreferences.commit() gives up when interrupted). Set aside
        // for the settle, then restored.
        val interrupted = Thread.interrupted()
        val settled = try {
            journalFor(context).settleFinishedSegment(segment.token, settle)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to settle the active-segment journal record for ${segment.target.displayPath}", e)
            false
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
        if (!settled) {
            Log.w(TAG, "Active-segment journal record for ${segment.target.displayPath} could not be " +
                "settled ($settle) yet; it stays recoverable, and is settled before the next recording")
        }
    }

    private fun targetExists(context: Context, target: OutputTarget): Boolean = existenceOf(context, target) == true

    /** Whether [target] exists: null when that can't be determined (a provider query that fails
     * says nothing either way). A document provider reports a missing document by throwing
     * [FileNotFoundException]. */
    private fun existenceOf(context: Context, target: OutputTarget): Boolean? = try {
        when (target) {
            is OutputTarget.FileTarget -> target.file.exists()
            is OutputTarget.SafTarget -> context.contentResolver.query(
                target.uri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null
            )?.use { it.count > 0 }
        }
    } catch (_: FileNotFoundException) {
        false
    } catch (_: Exception) {
        null
    }

    /** Deletes [target]; true only when it is confirmed gone -- the deletion reported success, or
     * the file is confirmed missing afterward. False when it may still exist. */
    private fun deleteSegmentFile(context: Context, target: OutputTarget): Boolean {
        val deleted = try {
            when (target) {
                is OutputTarget.FileTarget -> target.file.delete()
                is OutputTarget.SafTarget -> DocumentFile.fromSingleUri(context, target.uri)?.delete() == true
            }
        } catch (_: Exception) {
            false
        }
        return deleted || existenceOf(context, target) == false
    }

    private fun patchWavHeader(writer: SegmentWriter, totalAudioLen: Long, format: PcmFormat, includePadByte: Boolean) {
        val header = WavHeaderWriter.build(format, totalAudioLen, includePadByte)
        writer.position(0)
        writeFully(writer, header)
    }
}

/** [target]'s size in bytes, or null if it can't be determined (a provider that doesn't report
 * it, or a query that fails -- never read as "empty"). */
private fun sizeOf(context: Context, target: OutputTarget): Long? = try {
    when (target) {
        is OutputTarget.FileTarget -> target.file.takeIf { it.exists() }?.length()
        is OutputTarget.SafTarget -> context.contentResolver.query(
            target.uri, arrayOf(DocumentsContract.Document.COLUMN_SIZE), null, null, null
        )?.use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null }
    }
} catch (_: Exception) {
    null
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
 * Runs both cleanup steps of [SystemAudioSource.release] such that [releaseSource] (the actual
 * AudioRecord release -- the one that must never be skipped, since it's what actually frees the
 * underlying microphone hardware) still runs even if [unregisterCallback] throws.
 * AudioManager.unregisterAudioDeviceCallback isn't documented as exception-free, and before this
 * a failure there would skip audioRecord.release() entirely, leaking it. Pulled out as a small,
 * Android-independent function (rather than inlined in [SystemAudioSource.release]) so this
 * ordering guarantee is directly unit-testable without a real AudioRecord/AudioManager.
 */
internal fun releaseAudioSourceSafely(unregisterCallback: () -> Unit, releaseSource: () -> Unit) {
    try {
        unregisterCallback()
    } finally {
        releaseSource()
    }
}

/**
 * Opens the selected microphone in the best lossless capture it can find -- scored by what Android
 * reports the device side captures ([CaptureQuality]) -- for both real recording ([WavRecorder])
 * and the microphone test ([MicTestSession]). This is the one implementation of input-device
 * selection, format negotiation and route verification, so the two can never drift.
 *
 * 1. Device: an attached external input (an Insta360 accessory preferred by product name), or the
 *    built-in microphone when there is none. Its capabilities -- correlated AudioProfiles on API
 *    31+, the independent capability arrays before that -- go to [FormatNegotiation]: advertised
 *    formats as advertised, a small exploratory set only where a capability is unspecified, plus
 *    the long-standing 48/44.1 kHz 16-bit mono formats, best possible first.
 * 2. Attempts, in [FormatNegotiation.attemptOrder]'s order: each format with UNPROCESSED and then
 *    MIC before the next format. UNPROCESSED is meant to skip the platform's automatic gain
 *    control, noise suppression and echo cancellation -- pure downside for a dedicated
 *    microphone -- but a device may not support it (or only claim to), so MIC follows for the
 *    same format, and the diagnostics say which applied and whether the platform declares
 *    unprocessed support.
 * 3. Every candidate goes through [negotiateCapture]'s lifecycle: exact client format, then
 *    `setPreferredDevice` for the selected device (a refusal rejects it), then started, then the
 *    device it is *actually* routed to (`getRoutedDevice()`) must be the selected external device,
 *    then its device-side format is read. The search continues while an untried candidate could
 *    still score higher, within its time budget, and reports whether it finished; a converted
 *    result tries the exact device-side format next; a stopped winner is reopened and fully
 *    re-verified. Every other candidate is stopped and released; the winner is returned still
 *    recording ([WavRecorder.RecorderConfig.alreadyStarted]).
 * 4. If an external microphone is attached but no candidate could be verified on it, the phone's
 *    built-in microphone is negotiated the same way ([RouteTarget.PhoneMicInstead]); the Record
 *    screen then reports that the external microphone isn't in use and lets the user retry or
 *    continue with the phone microphone, and the microphone test stops with its mismatch message.
 *
 * The saved format is the client format AudioRecord confirmed; the device-side format and the
 * routed device are kept separately ([AudioSource.deviceSideFormat], [AudioSource.describeMicrophone]),
 * since Android may convert between the device and the app.
 *
 * Blocking -- it may build, start, poll and release many AudioRecords -- so it only ever runs on
 * the audio-startup worker ([AudioStartup]); [scope] is that startup's cancellation and time.
 *
 * [adaptCandidate] is applied to each real candidate -- for tests only: Robolectric's AudioRecord
 * can't route to a device, so a test wraps the real candidate to stand in for routing alone,
 * keeping real AudioRecord construction, validation, start and reads.
 */
@SuppressLint("MissingPermission")
internal fun openBestAudioRecord(
    scope: StartupScope,
    context: Context,
    adaptCandidate: (CaptureCandidate) -> CaptureCandidate = { it }
): WavRecorder.RecorderConfig {
    // A required system service; only nullable in the framework's generic getSystemService(Class)
    // signature. Without it there's no device list, so nothing to route to, verify or monitor:
    // default routing, reported as unverified.
    val audioManager = context.getSystemService(AudioManager::class.java)
    val inputs = try {
        audioManager?.getDevices(AudioManager.GET_DEVICES_INPUTS)?.toList().orEmpty()
    } catch (_: Exception) {
        emptyList()
    }
    val external = pickExternalInputDevice(inputs)
    val builtIn = inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
    val phases = if (external != null) {
        val externalDevice = inputDeviceOf(external)
        listOf(
            CapturePhase(RouteTarget.External(externalDevice), AudioInputCapabilities.of(external)),
            CapturePhase(RouteTarget.PhoneMicInstead(builtIn?.let(::inputDeviceOf), externalDevice), AudioInputCapabilities.of(builtIn))
        )
    } else {
        listOf(CapturePhase(RouteTarget.DefaultInput, AudioInputCapabilities.of(builtIn)))
    }
    val unprocessedSupported = try {
        audioManager?.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
    } catch (_: Exception) {
        false
    }
    return openNegotiatedCapture(
        phases = phases,
        sdkInt = Build.VERSION.SDK_INT,
        unprocessedSupported = unprocessedSupported,
        openCandidate = { attempt -> adaptCandidate(AudioRecordCandidate.open(attempt, inputs, audioManager)) },
        removalWatcher = audioManager?.let(::AudioManagerRemovalWatcher),
        scope = scope
    )
}

internal fun inputDeviceOf(device: AudioDeviceInfo) = InputDevice(device.id, device.type, friendlyDeviceLabel(device))

/** A real [AudioRecord] as a negotiation [CaptureCandidate]. Reads go through [DirectPcmReader]:
 * `AudioRecord.read(ByteBuffer, size, READ_BLOCKING)`, valid for every encoding, including float. */
private class AudioRecordCandidate(
    private val record: AudioRecord,
    format: PcmFormat,
    override val bufferSize: Int,
    private val inputs: List<AudioDeviceInfo>,
    private val audioManager: AudioManager?
) : CaptureCandidate {
    private val reader = DirectPcmReader(format, bufferSize) { buffer, size ->
        record.read(buffer, size, AudioRecord.READ_BLOCKING)
    }

    override fun clientFormatProblem(requested: PcmFormat): String? = validateAudioRecord(record, requested)

    override fun requestDevice(device: InputDevice): Boolean {
        val info = inputs.firstOrNull { it.id == device.id } ?: return false
        return record.setPreferredDevice(info)
    }

    override fun startRecording() = record.startRecording()

    override fun isRecording(): Boolean = record.recordingState == AudioRecord.RECORDSTATE_RECORDING

    override fun routedDevice(): InputDevice? = record.routedDevice?.let(::inputDeviceOf)

    override fun deviceSideFormat(): DeviceSideFormat? = readDeviceSideFormat(record, audioManager)

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = reader.read(buffer, offset, length)

    override fun stop() = record.stop()

    override fun release() = record.release()

    companion object {
        fun open(attempt: NegotiationAttempt, inputs: List<AudioDeviceInfo>, audioManager: AudioManager?): AudioRecordCandidate {
            val opened = buildAudioRecord(attempt)
            return AudioRecordCandidate(opened.record, attempt.candidate.format, opened.bufferSize, inputs, audioManager)
        }
    }
}

/** The device-side capture format of a started [record], from its active recording configuration
 * (API 29+ directly; before that, found among the active configurations by session id), or null
 * when the platform can't say. Never fatal: this only feeds diagnostics and the "converted by
 * Android" note. */
private fun readDeviceSideFormat(record: AudioRecord, audioManager: AudioManager?): DeviceSideFormat? = try {
    val configuration = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        record.activeRecordingConfiguration
    } else {
        audioManager?.activeRecordingConfigurations?.firstOrNull { it.clientAudioSessionId == record.audioSessionId }
    }
    configuration?.format?.let { DeviceSideFormat(it.sampleRate, it.encoding, it.channelCount) }
} catch (_: Exception) {
    null
}

/** [DeviceRemovalWatcher] over [AudioManager]'s device callbacks. Callbacks land on the main looper
 * (handler null); all they do is flip a flag the capture loop checks at its own pace. */
private class AudioManagerRemovalWatcher(private val audioManager: AudioManager) : DeviceRemovalWatcher {
    override fun watch(deviceId: Int, onRemoved: () -> Unit): AutoCloseable {
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) {
                if (removedDevices.any { it.id == deviceId }) onRemoved()
            }
        }
        audioManager.registerAudioDeviceCallback(callback, null)
        return AutoCloseable { audioManager.unregisterAudioDeviceCallback(callback) }
    }
}

private class OpenedRecord(val record: AudioRecord, val bufferSize: Int)

/** Longest a single read may be: bounds memory and keeps rollover overshoot tiny (see
 * [SegmentSplitPlan]) even for very high-rate, many-channel formats. */
private const val MAX_READ_BUFFER_BYTES = 1024 * 1024

@SuppressLint("MissingPermission")
private fun buildAudioRecord(attempt: NegotiationAttempt): OpenedRecord {
    val format = attempt.candidate.format
    val encoding = format.encoding.androidEncoding
    // getMinBufferSize only understands positional masks; for an index-mask layout, size the
    // buffer from the frame size directly (Android documents no minimum query for index masks).
    val minBufferSize = if (format.channelMask != 0) {
        AudioRecord.getMinBufferSize(format.sampleRate, format.channelMask, encoding)
    } else {
        format.bytesPerFrame * (format.sampleRate / 50) // 20 ms
    }
    if (minBufferSize <= 0) throw IllegalArgumentException("unsupported by AudioRecord (min buffer size $minBufferSize)")
    // Twice the minimum, and at least ~100 ms, in whole frames.
    val target = maxOf(minBufferSize * 2L, format.byteRate / 10).coerceAtMost(MAX_READ_BUFFER_BYTES.toLong())
    val bufferSize = maxOf(format.alignToFrame(target).toInt(), 2 * format.bytesPerFrame)

    val audioFormat = AudioFormat.Builder()
        .setEncoding(encoding)
        .setSampleRate(format.sampleRate)
        .apply {
            if (format.channelMask != 0) setChannelMask(format.channelMask) else setChannelIndexMask(format.channelIndexMask)
        }
        .build()
    val record = AudioRecord.Builder()
        .setAudioSource(attempt.audioSource)
        .setAudioFormat(audioFormat)
        .setBufferSizeInBytes(bufferSize)
        .build()
    return OpenedRecord(record, bufferSize)
}

/** Null when [record] is usable as exactly [requested]; otherwise why not. A record that silently
 * delivers something else (another rate, encoding or channel count) is rejected rather than
 * recorded under the wrong description. */
private fun validateAudioRecord(record: AudioRecord, requested: PcmFormat): String? = when {
    record.state != AudioRecord.STATE_INITIALIZED -> "AudioRecord did not initialize"
    record.sampleRate != requested.sampleRate -> "delivers ${record.sampleRate} Hz"
    record.audioFormat != requested.encoding.androidEncoding -> "delivers encoding ${record.audioFormat}"
    record.channelCount != requested.channelCount -> "delivers ${record.channelCount} channels"
    else -> null
}
