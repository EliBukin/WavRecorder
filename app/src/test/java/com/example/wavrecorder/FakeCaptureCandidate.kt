package com.example.wavrecorder

import android.media.AudioDeviceInfo

internal val USB_MIC = InputDevice(id = 7, type = AudioDeviceInfo.TYPE_USB_DEVICE, label = "USB Mic")
internal val PHONE_MIC = InputDevice(id = 1, type = AudioDeviceInfo.TYPE_BUILTIN_MIC, label = "Phone microphone")

/**
 * A scripted [CaptureCandidate]: how one AudioRecord candidate behaves through negotiation's
 * lifecycle, with every call counted and logged (in order, to [events]) so tests can check exactly
 * what was requested, started, stopped and released.
 */
internal class FakeCaptureCandidate(
    val attempt: NegotiationAttempt,
    private val events: MutableList<String>,
    private val behavior: Behavior
) : CaptureCandidate {

    data class Behavior(
        /** What clientFormatProblem() reports (null: exactly the requested format). */
        val formatProblem: String? = null,
        /** What setPreferredDevice returns. */
        val grantDevice: Boolean = true,
        val startThrows: Boolean = false,
        /** False: start() returns normally but the platform never actually starts recording. */
        val startsRecording: Boolean = true,
        /** Where it ends up routed once started, given the device requested (null: none). */
        val route: (InputDevice?) -> InputDevice? = { it },
        /** How many routedDevice() calls report nothing yet before the route appears. */
        val routeReportDelay: Int = 0,
        /** When set, overrides both of the above: what the n-th routedDevice() call (1-based)
         * reports -- e.g. another device at first, then the intended one. */
        val routeAtQuery: ((Int) -> InputDevice?)? = null,
        val deviceFormat: DeviceSideFormat? = null,
        /** How many deviceSideFormat() calls report nothing yet before [deviceFormat] appears --
         * Android publishing the recording configuration a moment late. */
        val deviceFormatReportDelay: Int = 0
    )

    val name: String = attempt.candidate.format.let { f ->
        "${f.encoding}/${f.sampleRate}/${f.channelCount}ch/" +
            if (attempt.audioSource == NegotiatedAudio.AUDIO_SOURCE_UNPROCESSED) "UNPROCESSED" else "MIC"
    }

    override val bufferSize: Int = 4096
    val requested = mutableListOf<InputDevice>()
    var starts = 0; private set
    var stops = 0; private set
    var releases = 0; private set
    var reads = 0; private set
    var routeQueries = 0; private set
    private var recording = false

    /** The live route after start; tests may change it to simulate a reroute. */
    @Volatile var currentRoute: InputDevice? = null

    /** Bytes handed out by read(), in order; then empty reads. */
    val scriptedReads = ArrayDeque<ByteArray>()

    override fun clientFormatProblem(requested: PcmFormat): String? {
        events += "$name format"
        return behavior.formatProblem
    }

    override fun requestDevice(device: InputDevice): Boolean {
        requested += device
        events += "$name request ${device.label}"
        return behavior.grantDevice
    }

    override fun startRecording() {
        starts++
        events += "$name start"
        if (behavior.startThrows) throw IllegalStateException("startRecording failed")
        recording = behavior.startsRecording
        currentRoute = behavior.route(requested.lastOrNull())
    }

    override fun isRecording(): Boolean = recording

    override fun routedDevice(): InputDevice? {
        routeQueries++
        behavior.routeAtQuery?.let { return it(routeQueries) }
        return if (routeQueries <= behavior.routeReportDelay) null else currentRoute
    }

    var deviceFormatQueries = 0; private set

    override fun deviceSideFormat(): DeviceSideFormat? {
        deviceFormatQueries++
        return if (deviceFormatQueries <= behavior.deviceFormatReportDelay) null else behavior.deviceFormat
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        reads++
        val chunk = synchronized(scriptedReads) { scriptedReads.removeFirstOrNull() } ?: return 0
        System.arraycopy(chunk, 0, buffer, offset, chunk.size)
        return chunk.size
    }

    override fun stop() {
        stops++
        recording = false
        events += "$name stop"
    }

    override fun release() {
        releases++
        events += "$name release"
    }
}

/** Opens [FakeCaptureCandidate]s per a [model] of the device (return null to fail the build
 * itself, as AudioRecord.Builder.build() throwing would), keeping every one opened. */
internal class FakeCandidateFactory(
    private val model: (NegotiationAttempt) -> FakeCaptureCandidate.Behavior?
) : (NegotiationAttempt) -> FakeCaptureCandidate {
    val events = mutableListOf<String>()
    val opened = mutableListOf<FakeCaptureCandidate>()
    var buildFailures = 0; private set

    override fun invoke(attempt: NegotiationAttempt): FakeCaptureCandidate {
        val behavior = model(attempt)
        if (behavior == null) {
            buildFailures++
            throw UnsupportedOperationException("Cannot create AudioRecord")
        }
        return FakeCaptureCandidate(attempt, events, behavior).also { opened += it }
    }

    fun find(format: PcmFormat, source: Int): FakeCaptureCandidate? =
        opened.firstOrNull { it.attempt.candidate.format == format && it.attempt.audioSource == source }
}

/**
 * A real [CaptureCandidate] (a real AudioRecord, in Robolectric) with only routing stood in for:
 * Robolectric's AudioRecord can't route to a device and never reports a routed one, so the routing
 * request is granted (and recorded in [requests]) and the candidate then reports itself routed to
 * the device requested. Construction, client-format validation, start and reads stay the real ones.
 */
internal class RoutedAsRequested(
    real: CaptureCandidate,
    private val requests: MutableList<InputDevice>
) : CaptureCandidate by real {
    private var requested: InputDevice? = null

    override fun requestDevice(device: InputDevice): Boolean {
        requests += device
        requested = device
        return true
    }

    override fun routedDevice(): InputDevice? = requested
}

/** The production [openBestAudioRecord] with [RoutedAsRequested] standing in for routing. */
internal fun routedOpenBestAudioRecord(
    requests: MutableList<InputDevice> = mutableListOf()
): StartupScope.(android.content.Context) -> WavRecorder.RecorderConfig =
    { context -> openBestAudioRecord(this, context) { RoutedAsRequested(it, requests) } }
