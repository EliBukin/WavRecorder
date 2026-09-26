package com.example.wavrecorder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Route-aware negotiation ([negotiateCapture], [openNegotiatedCapture], [SystemAudioSource]): every
 * candidate is format-checked, routed to the selected device, started and verified on its actual
 * route before it counts, and every rejected one is cleaned up exactly once. Pure JVM, with
 * scripted [FakeCaptureCandidate]s.
 */
class CaptureNegotiationTest {

    private val UNPROCESSED = NegotiatedAudio.AUDIO_SOURCE_UNPROCESSED
    private val MIC = NegotiatedAudio.AUDIO_SOURCE_MIC
    private val STEREO = PcmFormat.CHANNEL_IN_STEREO

    /** A USB microphone advertising 24-bit/96 kHz and 16-bit/48 kHz stereo. Attempt order:
     * 24/96 UNPROCESSED, 24/96 MIC, 16/48 stereo UNPROCESSED, 16/48 stereo MIC, then the
     * 48 and 44.1 kHz 16-bit mono fallbacks with UNPROCESSED and MIC. */
    private val usbCaps = DeviceCapabilities(profiles = listOf(
        CapabilityProfile(PcmEncoding.PCM_24_PACKED.androidEncoding, intArrayOf(96000), intArrayOf(STEREO), IntArray(0)),
        CapabilityProfile(PcmEncoding.PCM_16.androidEncoding, intArrayOf(48000), intArrayOf(STEREO), IntArray(0))
    ))
    private val hiRes = PcmFormat.of(96000, PcmEncoding.PCM_24_PACKED, 2)
    private val cd = PcmFormat.of(48000, PcmEncoding.PCM_16, 2)
    private val fb48 = FormatNegotiation.COMPATIBILITY_FALLBACKS[0]
    private val fb44 = FormatNegotiation.COMPATIBILITY_FALLBACKS[1]

    private val usbPhase = CapturePhase(RouteTarget.External(USB_MIC), usbCaps)
    /** The phone microphone, as a typical built-in one advertises itself: 16-bit/48 kHz mono.
     * Attempts: 48 kHz then 44.1 kHz (the other compatibility format), each UNPROCESSED then MIC. */
    private val phoneCaps = DeviceCapabilities(profiles = listOf(
        CapabilityProfile(PcmEncoding.PCM_16.androidEncoding, intArrayOf(48000), intArrayOf(PcmFormat.CHANNEL_IN_MONO), IntArray(0))
    ))
    private val phoneFallbackPhase = CapturePhase(RouteTarget.PhoneMicInstead(PHONE_MIC, USB_MIC), phoneCaps)

    private val ok = FakeCaptureCandidate.Behavior()

    /** A device that reports its device side -- exactly the requested format, unless [model] says
     * otherwise -- so the first verified candidate is final, and a test here is only about routing
     * and cleanup. (Device sides that aren't reported, or differ: QualitySearchTest.) */
    private fun reporting(model: (NegotiationAttempt) -> FakeCaptureCandidate.Behavior?) = FakeCandidateFactory { attempt ->
        model(attempt)?.let { behavior ->
            if (behavior.deviceFormat != null) behavior
            else attempt.candidate.format.let { behavior.copy(deviceFormat = DeviceSideFormat(it.sampleRate, it.encoding.androidEncoding, it.channelCount)) }
        }
    }

    private fun negotiate(
        factory: FakeCandidateFactory,
        vararg phases: CapturePhase,
        timing: VirtualStartupTiming = VirtualStartupTiming(),
        limits: NegotiationLimits = NegotiationLimits()
    ) = negotiateCapture(phases.toList(), CAPTURE_AUDIO_SOURCES, 34, factory, StartupScope(timing), limits)

    /** A scope in virtual time: no test here ever really sleeps. */
    private fun scope() = StartupScope(VirtualStartupTiming())

    private fun FakeCaptureCandidate.assertRejectedCleanly(started: Boolean) {
        assertEquals("$name released exactly once", 1, releases)
        assertEquals("$name stopped ${if (started) "once" else "never"}", if (started) 1 else 0, stops)
    }

    private fun FakeCaptureCandidate.assertHandedOverRunning() {
        assertEquals("$name started exactly once", 1, starts)
        assertEquals("$name not stopped", 0, stops)
        assertEquals("$name not released", 0, releases)
    }

    @Test
    fun `setPreferredDevice is requested for every candidate, before it is started`() {
        // Only 16/48 stereo with MIC ends up on the USB microphone; everything else is routed to the phone.
        val factory = FakeCandidateFactory { attempt ->
            if (attempt.candidate.format == cd && attempt.audioSource == MIC) ok
            else ok.copy(route = { PHONE_MIC })
        }
        val outcome = negotiate(factory, usbPhase)

        assertEquals(cd, outcome.attempt.candidate.format)
        assertEquals(MIC, outcome.attempt.audioSource)
        assertEquals(4, factory.opened.size)
        for (candidate in factory.opened) {
            assertEquals(listOf(USB_MIC), candidate.requested)
            val events = factory.events.filter { it.startsWith(candidate.name + " ") }
            assertTrue(events.indexOf("${candidate.name} request USB Mic") < events.indexOf("${candidate.name} start"))
        }
        factory.opened.dropLast(1).forEach { it.assertRejectedCleanly(started = true) }
        outcome.candidate.assertHandedOverRunning()
        assertEquals(USB_MIC, outcome.routed)
    }

    @Test
    fun `a refused preferred device rejects and releases that candidate without starting it`() {
        val factory = FakeCandidateFactory { attempt ->
            if (attempt.candidate.format == hiRes && attempt.audioSource == UNPROCESSED) ok.copy(grantDevice = false) else ok
        }
        val outcome = negotiate(factory, usbPhase)

        val refused = factory.find(hiRes, UNPROCESSED)!!
        assertEquals(0, refused.starts)
        refused.assertRejectedCleanly(started = false)
        assertTrue(outcome.rejected.single().reason.contains("refused"))
        assertEquals(hiRes, outcome.attempt.candidate.format)
        assertEquals(MIC, outcome.attempt.audioSource)
        outcome.candidate.assertHandedOverRunning()
    }

    @Test
    fun `a candidate that initializes but fails to start falls back to the next`() {
        val factory = FakeCandidateFactory { attempt ->
            when {
                attempt.candidate.format != hiRes -> ok
                attempt.audioSource == UNPROCESSED -> ok.copy(startThrows = true)
                else -> ok.copy(startsRecording = false) // AudioRecord's silent start failure
            }
        }
        val outcome = negotiate(factory, usbPhase)

        assertEquals(cd, outcome.attempt.candidate.format)
        assertEquals(UNPROCESSED, outcome.attempt.audioSource)
        factory.find(hiRes, UNPROCESSED)!!.assertRejectedCleanly(started = true)
        factory.find(hiRes, MIC)!!.assertRejectedCleanly(started = true)
        assertEquals(listOf("startRecording failed", "did not start recording"), outcome.rejected.map { it.reason })
    }

    @Test
    fun `a candidate that starts on the wrong routed device is rejected`() {
        val factory = FakeCandidateFactory { attempt ->
            when {
                attempt.candidate.format != hiRes -> ok
                attempt.audioSource == UNPROCESSED -> ok.copy(route = { PHONE_MIC })
                else -> ok.copy(route = { null }) // Android can't say where it went
            }
        }
        val outcome = negotiate(factory, usbPhase)

        assertEquals(cd, outcome.attempt.candidate.format)
        factory.find(hiRes, UNPROCESSED)!!.assertRejectedCleanly(started = true)
        factory.find(hiRes, MIC)!!.assertRejectedCleanly(started = true)
        assertTrue(outcome.rejected[0].reason.contains("instead of USB Mic"))
        assertTrue(outcome.rejected[1].reason.contains("could not be confirmed"))
    }

    @Test
    fun `a candidate reporting the wrong client format is rejected before it is routed or started`() {
        val factory = FakeCandidateFactory { attempt ->
            if (attempt.candidate.format == hiRes) ok.copy(formatProblem = "delivers 48000 Hz") else ok
        }
        val outcome = negotiate(factory, usbPhase)

        for (source in CAPTURE_AUDIO_SOURCES) {
            val wrong = factory.find(hiRes, source)!!
            assertTrue(wrong.requested.isEmpty())
            assertEquals(0, wrong.starts)
            wrong.assertRejectedCleanly(started = false)
        }
        assertEquals(cd, outcome.attempt.candidate.format)
    }

    @Test
    fun `every rejected candidate is cleaned up exactly as far as it got, and the winner not at all`() {
        val factory = reporting { attempt ->
            val f = attempt.candidate.format
            val u = attempt.audioSource == UNPROCESSED
            when {
                f == hiRes && u -> null                                        // build throws
                f == hiRes -> ok.copy(formatProblem = "delivers 2 channels... not")
                f == cd && u -> ok.copy(grantDevice = false)
                f == cd -> ok.copy(startThrows = true)
                f == fb48 && u -> ok.copy(startsRecording = false)
                f == fb48 -> ok.copy(route = { PHONE_MIC })
                else -> ok                                                     // 44.1 kHz UNPROCESSED
            }
        }
        val outcome = negotiate(factory, usbPhase)

        assertEquals(fb44, outcome.attempt.candidate.format)
        assertEquals(UNPROCESSED, outcome.attempt.audioSource)
        assertEquals(1, factory.buildFailures)
        assertEquals(6, outcome.rejected.size)
        factory.find(hiRes, MIC)!!.assertRejectedCleanly(started = false)
        factory.find(cd, UNPROCESSED)!!.assertRejectedCleanly(started = false)
        factory.find(cd, MIC)!!.assertRejectedCleanly(started = true)
        factory.find(fb48, UNPROCESSED)!!.assertRejectedCleanly(started = true)
        factory.find(fb48, MIC)!!.assertRejectedCleanly(started = true)
        outcome.candidate.assertHandedOverRunning()
        assertNull("the 44.1 kHz MIC attempt is never even opened", factory.find(fb44, MIC))
    }

    @Test
    fun `a format that only works with MIC beats a lower format that works with UNPROCESSED`() {
        // Regression for the old source-first order, which tried every format with UNPROCESSED
        // before any with MIC and so settled for 16-bit here.
        val factory = FakeCandidateFactory { attempt ->
            if (attempt.candidate.format == hiRes && attempt.audioSource == UNPROCESSED) ok.copy(startThrows = true) else ok
        }
        val outcome = negotiate(factory, usbPhase)

        assertEquals(hiRes, outcome.attempt.candidate.format)
        assertEquals(MIC, outcome.attempt.audioSource)
        assertNull("the lower format is never opened", factory.find(cd, UNPROCESSED))
    }

    @Test
    fun `when nothing can be verified on the external mic, the phone mic is requested and verified instead`() {
        val factory = FakeCandidateFactory { ok.copy(route = { requested -> if (requested == USB_MIC) PHONE_MIC else requested }) }
        val outcome = negotiate(factory, usbPhase, phoneFallbackPhase)

        assertTrue(outcome.target is RouteTarget.PhoneMicInstead)
        assertEquals(fb48, outcome.attempt.candidate.format)
        assertEquals(listOf(PHONE_MIC), outcome.candidate.requested)
        assertEquals(PHONE_MIC, outcome.routed)
        assertEquals("all 8 USB attempts are listed", 8, outcome.rejected.size)
        factory.opened.filter { it !== outcome.candidate }.forEach { it.assertRejectedCleanly(started = true) }

        val config = openNegotiatedCapture(listOf(usbPhase, phoneFallbackPhase), 34, true,
            FakeCandidateFactory { ok.copy(route = { requested -> if (requested == USB_MIC) PHONE_MIC else requested }) }, null, scope())
        assertEquals(
            "reported truthfully, so the Record screen can say the external mic isn't in use",
            MicrophoneInfo(label = "Phone microphone", isExternal = false, verified = true),
            config.source.describeMicrophone()
        )
    }

    @Test
    fun `the phone-mic fallback never accepts a route back to an external input`() {
        val factory = FakeCandidateFactory { ok.copy(route = { USB_MIC.copy(id = 99, label = "Other USB") }) }
        val error = assertThrows(FormatNegotiationException::class.java) { negotiate(factory, usbPhase, phoneFallbackPhase) }

        assertEquals(8 + 4, error.rejected.size)
        assertTrue(error.rejected.takeLast(4).all { it.reason.contains("not the phone microphone") })
        factory.opened.forEach { it.assertRejectedCleanly(started = true) }
        assertTrue(error.message!!.contains("all 12 attempts failed"))
    }

    @Test
    fun `with no external input, the phone mic keeps default routing and nothing is requested`() {
        val unverified = FakeCandidateFactory { ok }
        val outcome = negotiate(unverified, CapturePhase(RouteTarget.DefaultInput, DeviceCapabilities.UNKNOWN))
        assertTrue(unverified.opened.all { it.requested.isEmpty() })
        assertNull(outcome.routed)
        outcome.candidate.assertHandedOverRunning()

        val routed = FakeCandidateFactory { ok.copy(route = { PHONE_MIC }) }
        val config = openNegotiatedCapture(listOf(CapturePhase(RouteTarget.DefaultInput, DeviceCapabilities.UNKNOWN)), 34, false, routed, null, scope())
        assertEquals(MicrophoneInfo("Phone microphone", isExternal = false, verified = true), config.source.describeMicrophone())
        assertEquals(fb48, config.format)
    }

    @Test
    fun `a route and device-side format not reported yet are polled briefly, then read`() {
        // Native (the device side is exactly the client format), so the first candidate is final.
        val deviceFormat = DeviceSideFormat(96000, PcmEncoding.PCM_24_PACKED.androidEncoding, 2)
        val factory = FakeCandidateFactory { ok.copy(routeReportDelay = 2, deviceFormat = deviceFormat) }
        val timing = VirtualStartupTiming()
        val outcome = negotiate(factory, usbPhase, timing = timing)

        assertEquals(USB_MIC, outcome.routed)
        assertEquals("two polls, then the route was there", listOf(20L, 20L), timing.pauses)
        assertEquals(deviceFormat, outcome.deviceFormat)
    }

    @Test
    fun `the first candidate never reports a route, and the second is accepted once its route appears`() {
        val factory = FakeCandidateFactory { attempt ->
            if (attempt.audioSource == UNPROCESSED) ok.copy(route = { null }) // never reported
            else ok.copy(routeReportDelay = 3)                                 // reported on the 4th read
        }
        val timing = VirtualStartupTiming()
        val outcome = negotiate(factory, usbPhase, timing = timing)

        val first = factory.find(hiRes, UNPROCESSED)!!
        first.assertRejectedCleanly(started = true)
        assertEquals("its whole window was polled: 1 read + 25 polls of 20 ms", 26, first.routeQueries)
        assertTrue(outcome.rejected.single().reason.contains("could not be confirmed"))

        assertEquals(hiRes, outcome.attempt.candidate.format)
        assertEquals(MIC, outcome.attempt.audioSource)
        assertEquals(USB_MIC, outcome.routed)
        outcome.candidate.assertHandedOverRunning()
        assertEquals(4, outcome.candidate.routeQueries)
        assertEquals(
            "500 ms for the first; 60 ms for the second's route, then -- the first verified " +
                "candidate -- its 200 ms device-format window (this fake never reports one)",
            760L, timing.now
        )
    }

    @Test
    fun `every started candidate gets its own full route window`() {
        val factory = FakeCandidateFactory { ok.copy(route = { null }) }
        val timing = VirtualStartupTiming()
        val error = assertThrows(FormatNegotiationException::class.java) { negotiate(factory, usbPhase, timing = timing) }

        assertEquals(8, factory.opened.size)
        assertTrue("no candidate is cut short by an earlier one's missing route", factory.opened.all { it.routeQueries == 26 })
        assertEquals(8 * 500L, timing.now)
        factory.opened.forEach { it.assertRejectedCleanly(started = true) }
        assertEquals(8, error.rejected.size)
    }

    @Test
    fun `a route that first points elsewhere is accepted once it becomes the intended microphone`() {
        val factory = FakeCandidateFactory { ok.copy(routeAtQuery = { n -> if (n <= 2) PHONE_MIC else USB_MIC }) }
        val outcome = negotiate(factory, usbPhase)

        assertEquals(hiRes, outcome.attempt.candidate.format)
        assertEquals(USB_MIC, outcome.routed)
        assertTrue(outcome.rejected.isEmpty())
    }

    @Test
    fun `cancelling while a candidate waits for its route releases it and attempts nothing further`() {
        lateinit var scope: StartupScope
        val timing = VirtualStartupTiming { now -> if (now >= 60) scope.cancel() }
        scope = StartupScope(timing)
        val factory = FakeCandidateFactory { ok.copy(route = { null }) }

        assertThrows(StartupCancelledException::class.java) {
            negotiateCapture(listOf(usbPhase, phoneFallbackPhase), CAPTURE_AUDIO_SOURCES, 34, factory, scope)
        }

        val only = factory.opened.single()
        only.assertRejectedCleanly(started = true)
        assertEquals("the wait ended at the cancellation, not the window's end", 60L, timing.now)
    }

    @Test
    fun `cancelling before a candidate is built opens nothing`() {
        val scope = StartupScope(VirtualStartupTiming()).apply { cancel() }
        val factory = FakeCandidateFactory { ok }
        assertThrows(StartupCancelledException::class.java) {
            negotiateCapture(listOf(usbPhase), CAPTURE_AUDIO_SOURCES, 34, factory, scope)
        }
        assertTrue(factory.opened.isEmpty())
    }

    /** 8 rates x 2 bit depths x mono/stereo = 32 formats: 64 attempts with the two sources. */
    private val manyFormats = DeviceCapabilities(profiles = listOf(PcmEncoding.PCM_24_PACKED, PcmEncoding.PCM_16).map {
        CapabilityProfile(
            it.androidEncoding, intArrayOf(8000, 11025, 16000, 22050, 32000, 44100, 48000, 96000),
            intArrayOf(PcmFormat.CHANNEL_IN_MONO, STEREO), IntArray(0)
        )
    })

    @Test
    fun `the overall deadline ends a long candidate list and cleans up every candidate it started`() {
        val factory = FakeCandidateFactory { ok.copy(route = { null }) }
        val timing = VirtualStartupTiming()
        val error = assertThrows(FormatNegotiationException::class.java) {
            negotiate(factory, CapturePhase(RouteTarget.External(USB_MIC), manyFormats), timing = timing)
        }

        assertEquals("8 s / 500 ms per candidate", 16, factory.opened.size)
        assertEquals(8_000L, timing.now)
        factory.opened.forEach { it.assertRejectedCleanly(started = true) }
        assertTrue(error.message!!, error.message!!.contains("time limit"))
    }

    @Test
    fun `the phone-microphone fallback keeps its reserved time after a slow external microphone`() {
        val factory = FakeCandidateFactory { ok.copy(route = { requested -> if (requested == USB_MIC) null else requested }) }
        val timing = VirtualStartupTiming()
        val outcome = negotiate(factory, CapturePhase(RouteTarget.External(USB_MIC), manyFormats), phoneFallbackPhase, timing = timing)

        assertTrue(outcome.target is RouteTarget.PhoneMicInstead)
        assertEquals("the external phase stopped at 8 s - 2 s", 12, outcome.rejected.size)
        factory.opened.filter { it !== outcome.candidate }.forEach { it.assertRejectedCleanly(started = true) }
        assertTrue(timing.now <= 8_000L)
    }

    @Test
    fun `a route window never runs past the negotiation deadline`() {
        val factory = FakeCandidateFactory { ok.copy(route = { null }) }
        val timing = VirtualStartupTiming()
        assertThrows(FormatNegotiationException::class.java) {
            negotiate(factory, usbPhase, timing = timing, limits = NegotiationLimits(totalMs = 1_200))
        }
        assertEquals("500 + 500 + the last 200 ms", 1_200L, timing.now)
        assertEquals(3, factory.opened.size)
        factory.opened.forEach { it.assertRejectedCleanly(started = true) }
    }

    @Test
    fun `the accepted source arrives already recording and refuses a second start`() {
        val factory = reporting { ok }
        val config = openNegotiatedCapture(listOf(usbPhase), 34, true, factory, null, scope())

        assertTrue(config.alreadyStarted)
        val accepted = factory.opened.single()
        accepted.assertHandedOverRunning()
        assertThrows(IllegalStateException::class.java) { config.source.startRecording() }
        assertEquals("the refused restart never reached the candidate", 1, accepted.starts)
        assertEquals(hiRes, config.format)
        assertEquals(accepted.bufferSize, config.bufferSize)
        assertEquals(UNPROCESSED, config.negotiation!!.audioSource)
    }

    @Test
    fun `the file format, the device-side format and the routed microphone are kept apart`() {
        // The device converts everything to float/48 kHz, and its exact float format can't be
        // opened: the best converted candidate is used, described as exactly what it is.
        val deviceFormat = DeviceSideFormat(48000, PcmEncoding.PCM_FLOAT.androidEncoding, 2)
        val config = openNegotiatedCapture(listOf(usbPhase), 34, true, FakeCandidateFactory { attempt ->
            if (attempt.candidate.origin == CandidateOrigin.DEVICE_REPORTED) null else ok.copy(deviceFormat = deviceFormat)
        }, null, scope())

        assertEquals(hiRes, config.format)
        assertEquals(hiRes, config.negotiation!!.format)
        assertEquals(CaptureMode.CONVERTED, config.negotiation!!.captureMode)
        assertEquals(deviceFormat, config.source.deviceSideFormat())
        assertEquals(deviceFormat, config.negotiation!!.deviceFormat)
        assertTrue(deviceFormat.differsFrom(config.format))
        assertEquals(MicrophoneInfo("USB Mic", isExternal = true, verified = true), config.source.describeMicrophone())
    }

    @Test
    fun `reads come from the accepted candidate, and a later reroute is detected`() {
        val factory = reporting { ok }
        val config = openNegotiatedCapture(listOf(usbPhase), 34, true, factory, null, scope())
        val accepted = factory.opened.single()
        accepted.scriptedReads += byteArrayOf(1, 2, 3, 4, 5, 6)

        val buffer = ByteArray(12)
        assertEquals(6, config.source.read(buffer, 3, 9))
        assertEquals(listOf<Byte>(0, 0, 0, 1, 2, 3, 4, 5, 6, 0, 0, 0), buffer.toList())

        assertTrue(config.source.isRouteUnchanged())
        accepted.currentRoute = PHONE_MIC
        assertFalse(config.source.isRouteUnchanged())
        accepted.currentRoute = null
        assertFalse(config.source.isRouteUnchanged())
        accepted.currentRoute = USB_MIC
        assertTrue(config.source.isRouteUnchanged())
    }

    private class FakeWatcher(private val fail: Boolean = false) : DeviceRemovalWatcher {
        val watched = mutableListOf<Int>()
        var closed = 0
        var onRemoved: (() -> Unit)? = null
        override fun watch(deviceId: Int, onRemoved: () -> Unit): AutoCloseable {
            if (fail) throw IllegalStateException("callback registration failed")
            watched += deviceId
            this.onRemoved = onRemoved
            return AutoCloseable { closed++ }
        }
    }

    @Test
    fun `only the accepted external device is watched for removal, and the watch ends on release`() {
        val watcher = FakeWatcher()
        val factory = FakeCandidateFactory { attempt -> if (attempt.candidate.format == hiRes) ok.copy(route = { PHONE_MIC }) else ok }
        val config = openNegotiatedCapture(listOf(usbPhase), 34, true, factory, watcher, scope())

        assertEquals("no rejected candidate registered a callback", listOf(USB_MIC.id), watcher.watched)
        assertTrue(config.source.isDeviceConnected())
        watcher.onRemoved!!()
        assertFalse(config.source.isDeviceConnected())

        config.source.stop()
        config.source.release()
        assertEquals(1, watcher.closed)
        assertEquals(1, factory.opened.last().releases)
    }

    @Test
    fun `the phone mic is never watched for removal`() {
        val watcher = FakeWatcher()
        openNegotiatedCapture(listOf(CapturePhase(RouteTarget.DefaultInput, DeviceCapabilities.UNKNOWN)), 34, false,
            FakeCandidateFactory { ok.copy(route = { PHONE_MIC }) }, watcher, scope())
        assertTrue(watcher.watched.isEmpty())
    }

    @Test
    fun `if the removal watch can't be set up, the started candidate is stopped and released`() {
        val factory = reporting { ok }
        assertThrows(IllegalStateException::class.java) {
            openNegotiatedCapture(listOf(usbPhase), 34, true, factory, FakeWatcher(fail = true), scope())
        }
        val accepted = factory.opened.single()
        assertEquals(1, accepted.stops)
        assertEquals(1, accepted.releases)
    }

    @Test
    fun `when every attempt fails, every opened candidate is released and nothing is left running`() {
        val factory = FakeCandidateFactory { attempt -> if (attempt.audioSource == MIC) null else ok.copy(route = { PHONE_MIC }) }
        val error = assertThrows(FormatNegotiationException::class.java) { negotiate(factory, usbPhase) }

        assertEquals(8, error.rejected.size)
        factory.opened.forEach { it.assertRejectedCleanly(started = true) }
        assertEquals(4, factory.buildFailures)
    }
}
