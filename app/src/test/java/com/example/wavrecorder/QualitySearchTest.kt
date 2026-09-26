package com.example.wavrecorder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [negotiateCapture]'s quality search: the winner is chosen by what the *device side* really
 * captures (`AudioRecordingConfiguration.getFormat()`), not by the client format AudioRecord agrees
 * to deliver. Pure JVM, in virtual time, with scripted candidates whose reported device side
 * depends on the client format requested -- as a real device's does.
 */
class QualitySearchTest {

    private val UNPROCESSED = NegotiatedAudio.AUDIO_SOURCE_UNPROCESSED
    private val MIC = NegotiatedAudio.AUDIO_SOURCE_MIC
    private val STEREO = PcmFormat.CHANNEL_IN_STEREO
    private val MONO = PcmFormat.CHANNEL_IN_MONO

    private val P16 = PcmEncoding.PCM_16
    private val P24 = PcmEncoding.PCM_24_PACKED
    private val P32 = PcmEncoding.PCM_32
    private val FLOAT = PcmEncoding.PCM_FLOAT

    private fun profile(encoding: PcmEncoding, vararg rates: Int, mask: Int = STEREO) =
        CapabilityProfile(encoding.androidEncoding, rates, intArrayOf(mask), IntArray(0))

    private fun usb(vararg profiles: CapabilityProfile) =
        CapturePhase(RouteTarget.External(USB_MIC), DeviceCapabilities(profiles = profiles.toList()))

    private fun device(rate: Int, encoding: PcmEncoding, channels: Int = 2) = DeviceSideFormat(rate, encoding.androidEncoding, channels)

    /** The device side is exactly what was asked for. */
    private val exactDevice: (PcmFormat) -> DeviceSideFormat = { device(it.sampleRate, it.encoding, it.channelCount) }

    private val ok = FakeCaptureCandidate.Behavior()

    /** Every candidate works; the device side reported for each is [deviceFor] its client format. */
    private fun factory(deviceFor: (PcmFormat) -> DeviceSideFormat?, tweak: (NegotiationAttempt, FakeCaptureCandidate.Behavior) -> FakeCaptureCandidate.Behavior? = { _, b -> b }) =
        FakeCandidateFactory { attempt -> tweak(attempt, ok.copy(deviceFormat = deviceFor(attempt.candidate.format))) }

    private fun negotiate(
        factory: FakeCandidateFactory,
        vararg phases: CapturePhase,
        timing: VirtualStartupTiming = VirtualStartupTiming(),
        limits: NegotiationLimits = NegotiationLimits()
    ) = negotiateCapture(phases.toList(), CAPTURE_AUDIO_SOURCES, 34, factory, StartupScope(timing), limits)

    private fun FakeCandidateFactory.instances(format: PcmFormat, source: Int) =
        opened.filter { it.attempt.candidate.format == format && it.attempt.audioSource == source }

    /** Every candidate but [winner] was stopped (if it was started) and released exactly once;
     * the winner was started once and is still running. */
    private fun FakeCandidateFactory.assertOnlyWinnerRunning(winner: FakeCaptureCandidate) {
        for (c in opened) {
            if (c === winner) {
                assertEquals("${c.name}: winner started once", 1, c.starts)
                assertEquals("${c.name}: winner not stopped", 0, c.stops)
                assertEquals("${c.name}: winner not released", 0, c.releases)
            } else {
                assertEquals("${c.name}: released exactly once", 1, c.releases)
                assertEquals("${c.name}: stopped once if started", if (c.starts > 0) 1 else 0, c.stops)
            }
        }
    }

    @Test
    fun `24-bit 192 kHz beats 32-bit 8 kHz`() {
        val f = factory(exactDevice)
        val outcome = negotiate(f, usb(profile(P32, 8000), profile(P24, 192000)))

        assertEquals(PcmFormat.of(192000, P24, 2), outcome.attempt.candidate.format)
        assertEquals(1, f.opened.size)
    }

    @Test
    fun `full-band capture beats a working telephony-rate one`() {
        // Only 32-bit at 8 kHz is advertised, but the 48 kHz 16-bit compatibility format works too.
        val f = factory(exactDevice)
        val outcome = negotiate(f, usb(profile(P32, 8000)))

        assertEquals(PcmFormat.pcm16Mono(48000), outcome.attempt.candidate.format)
        assertEquals(CaptureMode.MATCHED, outcome.mode)
        assertTrue("the telephony-rate format is never even opened", f.opened.none { it.attempt.candidate.format.sampleRate < 44100 })
    }

    @Test
    fun `a 32-bit 192 kHz stream the device captures at 24-bit 48 kHz is not accepted as the maximum - the exact format is`() {
        val f = factory({ device(48000, P24) })
        val hiRes = PcmFormat.of(192000, P32, 2)
        val exact = PcmFormat.of(48000, P24, 2)
        val outcome = negotiate(f, usb(profile(P32, 192000), profile(P16, 48000)))

        // The inflated client format was tried first, found converted, and not kept...
        val first = f.opened.first()
        assertEquals(hiRes, first.attempt.candidate.format)
        assertEquals(1, first.releases)
        // ...the exact client format for what the device really captures was tried next, and won.
        assertEquals(exact, outcome.attempt.candidate.format)
        assertEquals(CandidateOrigin.DEVICE_REPORTED, outcome.attempt.candidate.origin)
        assertEquals(CaptureMode.MATCHED, outcome.mode)
        assertEquals(device(48000, P24), outcome.deviceFormat)
        f.assertOnlyWinnerRunning(outcome.candidate)
    }

    @Test
    fun `a matched 24-bit capture beats a converted 32-bit client stream`() {
        val f = factory({ device(48000, P24) })
        val outcome = negotiate(f, usb(profile(P32, 48000), profile(P24, 48000)))

        assertEquals(PcmFormat.of(48000, P24, 2), outcome.attempt.candidate.format)
        assertEquals(CaptureMode.MATCHED, outcome.mode)
        f.assertOnlyWinnerRunning(outcome.candidate)
    }

    @Test
    fun `a 32-bit format wins when the device side is reported in exactly that format`() {
        val f = factory(exactDevice)
        val outcome = negotiate(f, usb(profile(P32, 48000), profile(P24, 48000)))

        assertEquals(PcmFormat.of(48000, P32, 2), outcome.attempt.candidate.format)
        assertEquals(CaptureMode.MATCHED, outcome.mode)
        assertEquals("matched and final: nothing else is even opened", 1, f.opened.size)
        assertFalse(outcome.reopened)
    }

    @Test
    fun `matched float is chosen, and a float stream converted from 24-bit is not`() {
        val matched = negotiate(factory(exactDevice), usb(profile(FLOAT, 48000), profile(P24, 48000)))
        assertEquals(PcmFormat.of(48000, FLOAT, 2), matched.attempt.candidate.format)
        assertEquals(CaptureMode.MATCHED, matched.mode)

        val converted = negotiate(factory({ device(48000, P24) }), usb(profile(FLOAT, 48000), profile(P24, 48000)))
        assertEquals(PcmFormat.of(48000, P24, 2), converted.attempt.candidate.format)
        assertEquals(CaptureMode.MATCHED, converted.mode)
    }

    @Test
    fun `with no exact path, the best converted candidate is reopened, fully verified, and reported as converted`() {
        val deviceSide = device(96000, P24)
        val f = factory({ deviceSide }) { attempt, b -> if (attempt.candidate.origin == CandidateOrigin.DEVICE_REPORTED) null else b }
        val best = PcmFormat.of(96000, P32, 2)
        val outcome = negotiate(f, usb(profile(P32, 96000), profile(P16, 48000)))

        assertEquals(best, outcome.attempt.candidate.format)
        assertEquals(UNPROCESSED, outcome.attempt.audioSource)
        assertEquals(CaptureMode.CONVERTED, outcome.mode)
        assertEquals(deviceSide, outcome.deviceFormat)
        assertTrue(outcome.reopened)
        // Stopped while alternatives were compared, then opened again from scratch: a new
        // candidate, with its own device request, start, route check and device-side read.
        val (earlier, reopened) = f.instances(best, UNPROCESSED)
        assertNotSame(earlier, reopened)
        assertTrue(reopened === outcome.candidate)
        assertEquals(1, earlier.releases)
        assertEquals(listOf(USB_MIC), reopened.requested)
        assertTrue("its route was read afresh", reopened.routeQueries >= 1)
        f.assertOnlyWinnerRunning(outcome.candidate)
    }

    @Test
    fun `a reopened winner that is now routed elsewhere is rejected, and the next best reopened instead`() {
        val best = PcmFormat.of(96000, P32, 2)
        var bestOpens = 0
        val f = factory({ device(96000, P24) }) { attempt, b ->
            when {
                attempt.candidate.origin == CandidateOrigin.DEVICE_REPORTED -> null
                attempt.candidate.format == best && attempt.audioSource == UNPROCESSED && ++bestOpens == 2 -> b.copy(route = { PHONE_MIC })
                else -> b
            }
        }
        val outcome = negotiate(f, usb(profile(P32, 96000)))

        assertEquals(best, outcome.attempt.candidate.format)
        assertEquals("the equally good MIC candidate, reopened", MIC, outcome.attempt.audioSource)
        assertTrue(outcome.reopened)
        assertTrue(outcome.rejected.any { it.reason.startsWith("on reopening") && it.reason.contains("instead of USB Mic") })
        f.assertOnlyWinnerRunning(outcome.candidate)
        assertEquals("the best found wasn't the one used", SearchStop.BEST_NOT_REOPENED, outcome.search.stop)
        assertEquals(SearchCoverage.INCOMPLETE, outcome.search.coverage)
    }

    @Test
    fun `when the device side is never reported, the ranking decides and the result is unreported`() {
        val f = factory({ null })
        val outcome = negotiate(f, usb(profile(P32, 192000), profile(P24, 192000)))

        assertEquals("unreported 32-bit counts only as 24-bit, and the smaller 24-bit stream is preferred",
            PcmFormat.of(192000, P24, 2), outcome.attempt.candidate.format)
        assertEquals(CaptureMode.UNREPORTED, outcome.mode)
        assertNull(outcome.deviceFormat)
        f.assertOnlyWinnerRunning(outcome.candidate)
        // Missing reports rule nothing out: the MIC variants, which could have reported, were
        // tried too -- so the search is complete, and the winner was reopened after them.
        assertTrue(f.instances(PcmFormat.of(192000, P24, 2), MIC).isNotEmpty())
        assertTrue(outcome.reopened)
        assertEquals(SearchCoverage.COMPLETE, outcome.search.coverage)

        val config = openNegotiatedCapture(listOf(usb(profile(P32, 192000), profile(P24, 192000))), 34, true,
            factory({ null }), null, StartupScope(VirtualStartupTiming()))
        assertEquals(CaptureMode.UNREPORTED, config.negotiation!!.captureMode)
        assertNull(config.source.deviceSideFormat())
    }

    @Test
    fun `cancelling during the extended search releases the candidate in hand and tries nothing further`() {
        lateinit var scope: StartupScope
        val timing = VirtualStartupTiming { scope.cancel() }
        scope = StartupScope(timing)
        // The first candidate is converted at once; the exact one is slow to report its route,
        // and the startup is cancelled while it waits.
        val f = factory({ device(48000, P24) }) { attempt, b ->
            if (attempt.candidate.origin == CandidateOrigin.DEVICE_REPORTED) b.copy(routeReportDelay = 3) else b
        }

        assertThrows(StartupCancelledException::class.java) {
            negotiateCapture(listOf(usb(profile(P32, 192000))), CAPTURE_AUDIO_SOURCES, 34, f, scope)
        }

        assertEquals("the converted candidate, then the exact one -- nothing after", 2, f.opened.size)
        f.opened.forEach {
            assertEquals("${it.name} released once", 1, it.releases)
            assertEquals("${it.name} stopped once", 1, it.stops)
        }
    }

    /** 8 rates x 2 bit depths, stereo: 32 formats, 64 attempts. */
    private val manyFormats = usb(
        profile(P24, 8000, 11025, 16000, 22050, 32000, 44100, 48000, 96000),
        profile(P32, 8000, 11025, 16000, 22050, 32000, 44100, 48000, 96000)
    )

    @Test
    fun `the overall deadline limits the whole search, reopen included, and the result says it is incomplete`() {
        val f = factory({ device(16000, P16) }) { attempt, b ->
            if (attempt.candidate.origin == CandidateOrigin.DEVICE_REPORTED) null else b.copy(routeReportDelay = 5)
        }
        val timing = VirtualStartupTiming()
        val outcome = negotiate(f, manyFormats, timing = timing, limits = NegotiationLimits(searchBudgetMs = 60_000, totalMs = 2_000))

        assertTrue("finished within the deadline: ${timing.now}", timing.now <= 2_000)
        assertTrue(outcome.reopened)
        assertEquals(CaptureMode.CONVERTED, outcome.mode)
        assertEquals(SearchCoverage.INCOMPLETE, outcome.search.coverage)
        assertEquals(SearchStop.DEADLINE, outcome.search.stop)
        f.assertOnlyWinnerRunning(outcome.candidate)
    }

    /** Six full-band 24-bit rates, stereo: the device side is 16-bit at 44.1 kHz for every one of
     * them except 24-bit/44.1 kHz itself -- the lowest of the six, so the one tried last. */
    private val oneGenuineModeAtTheBottom = usb(profile(P24, 192000, 176400, 96000, 88200, 48000, 44100))
    private val genuine = PcmFormat.of(44100, P24, 2)
    private val deviceForOneGenuineMode: (PcmFormat) -> DeviceSideFormat = { if (it == genuine) device(44100, P24) else device(44100, P16) }

    @Test
    fun `a better candidate beyond the old four-extra-probes cutoff is still found`() {
        val f = factory(deviceForOneGenuineMode)
        val outcome = negotiate(f, oneGenuineModeAtTheBottom)

        assertEquals(genuine, outcome.attempt.candidate.format)
        assertEquals(CaptureMode.MATCHED, outcome.mode)
        assertTrue("found after ${f.opened.size} candidates -- the old search stopped after 5", f.opened.size > 5)
        assertEquals(SearchCoverage.COMPLETE, outcome.search.coverage)
        f.assertOnlyWinnerRunning(outcome.candidate)
    }

    @Test
    fun `a search its time budget cuts short returns the best found, explicitly incomplete, and starts nothing after it`() {
        val timing = VirtualStartupTiming()
        val opensAt = mutableListOf<Long>()
        // Every candidate takes 100 ms to report its route.
        val f = factory(deviceForOneGenuineMode) { _, b -> opensAt += timing.now; b.copy(routeReportDelay = 5) }
        val limits = NegotiationLimits(searchBudgetMs = 500)
        val outcome = negotiate(f, oneGenuineModeAtTheBottom, timing = timing, limits = limits)

        assertEquals("the best found: the exact 16-bit mode", PcmFormat.of(44100, P16, 2), outcome.attempt.candidate.format)
        assertEquals(CaptureMode.MATCHED, outcome.mode)
        assertEquals(SearchCoverage.INCOMPLETE, outcome.search.coverage)
        assertEquals(SearchStop.SEARCH_BUDGET, outcome.search.stop)
        assertTrue(outcome.search.untriedImprovers > 0)
        assertTrue("a higher format was left untried", outcome.search.untriedHigherFormats > 0)
        // The first usable candidate was verified at 100 ms; nothing was started after the budget
        // but the winner's reopen.
        val searchOpens = opensAt.dropLast(if (outcome.reopened) 1 else 0)
        assertTrue("opened at $searchOpens", searchOpens.all { it < 100 + limits.searchBudgetMs })
        f.assertOnlyWinnerRunning(outcome.candidate)

        val labels = openNegotiatedCapture(listOf(oneGenuineModeAtTheBottom), 34, true,
            factory(deviceForOneGenuineMode) { _, b -> b.copy(routeReportDelay = 5) }, null,
            StartupScope(VirtualStartupTiming()), limits).negotiation!!
        assertEquals("carried to what the session reports", SearchCoverage.INCOMPLETE, labels.search!!.coverage)
    }

    @Test
    fun `a completed search is reported complete, independently of its format evidence`() {
        val complete = negotiate(factory(exactDevice), usb(profile(P24, 48000)))
        assertEquals(SearchCoverage.COMPLETE, complete.search.coverage)
        assertEquals(SearchStop.NOTHING_BETTER_POSSIBLE, complete.search.stop)
        assertEquals(0, complete.search.untriedImprovers)

        // Complete, and converted: coverage and evidence are separate things.
        val converted = negotiate(factory({ device(48000, P16) }) { a, b -> if (a.candidate.origin == CandidateOrigin.DEVICE_REPORTED) null else b },
            usb(profile(P24, 48000)))
        assertEquals(CaptureMode.CONVERTED, converted.mode)
        assertEquals(SearchCoverage.COMPLETE, converted.search.coverage)
    }

    @Test
    fun `a device side missing for the first candidate is still read for the next, and counts`() {
        val top = PcmFormat.of(96000, P24, 2)
        val f = factory(exactDevice) { attempt, b ->
            if (attempt.audioSource == UNPROCESSED) b.copy(deviceFormat = null)       // never reported for this one
            else b.copy(deviceFormatReportDelay = 2)                                 // reported, 40 ms late
        }
        val outcome = negotiate(f, usb(profile(P24, 96000, 48000)))

        assertEquals(top, outcome.attempt.candidate.format)
        assertEquals("the later candidate's report was waited for and used", MIC, outcome.attempt.audioSource)
        assertEquals(device(96000, P24), outcome.deviceFormat)
        assertEquals(CaptureMode.MATCHED, outcome.mode)
        assertEquals(SearchCoverage.COMPLETE, outcome.search.coverage)
    }

    /** A device that reports no capabilities at all: its device side is the request when the request
     * is one of its modes (24-bit at 96 or 48 kHz, stereo), otherwise its default, 24-bit/48 kHz. */
    private val undescribedDevice: (PcmFormat) -> DeviceSideFormat = {
        if (it.encoding == P24 && it.channelCount == 2 && (it.sampleRate == 96000 || it.sampleRate == 48000)) device(it.sampleRate, P24)
        else device(48000, P24)
    }

    @Test
    fun `a device that leaves its capabilities unspecified has a higher genuine format discovered`() {
        val f = factory(undescribedDevice)
        val phase = CapturePhase(RouteTarget.External(USB_MIC), DeviceCapabilities.UNKNOWN)
        val outcome = negotiate(f, phase)

        assertEquals(PcmFormat.of(96000, P24, 2), outcome.attempt.candidate.format)
        assertEquals(CandidateOrigin.EXPLORATORY, outcome.attempt.candidate.origin)
        assertEquals("credited because the device side confirms it", CaptureMode.MATCHED, outcome.mode)
        assertEquals(SearchCoverage.COMPLETE, outcome.search.coverage)
        f.assertOnlyWinnerRunning(outcome.candidate)

        // The same device, never reporting its device side: exploratory formats earn nothing, and
        // the long-standing compatibility format is used -- no invented precision or rate.
        val silent = factory({ null })
        val fallback = negotiate(silent, phase)
        assertEquals(PcmFormat.pcm16Mono(48000), fallback.attempt.candidate.format)
        assertEquals(CaptureMode.UNREPORTED, fallback.mode)
        silent.assertOnlyWinnerRunning(fallback.candidate)
    }

    @Test
    fun `format first and UNPROCESSED first still decide between equivalent candidates`() {
        val matchedBoth = factory(exactDevice)
        val matched = negotiate(matchedBoth, usb(profile(P24, 48000)))
        assertEquals(UNPROCESSED, matched.attempt.audioSource)
        assertTrue("MIC isn't opened once UNPROCESSED is matched", matchedBoth.instances(PcmFormat.of(48000, P24, 2), MIC).isEmpty())

        // Both sources equally converted: the first tried -- UNPROCESSED -- is the one kept.
        val converted = factory({ device(48000, P16) }) { attempt, b -> if (attempt.candidate.origin == CandidateOrigin.DEVICE_REPORTED) null else b }
        val outcome = negotiate(converted, usb(profile(P24, 48000)))
        assertEquals(PcmFormat.of(48000, P24, 2), outcome.attempt.candidate.format)
        assertEquals(UNPROCESSED, outcome.attempt.audioSource)
        converted.assertOnlyWinnerRunning(outcome.candidate)

        // And a format that only works with MIC still beats any lower one with UNPROCESSED.
        val micOnly = factory(exactDevice) { attempt, b ->
            if (attempt.candidate.format.encoding == P32 && attempt.audioSource == UNPROCESSED) b.copy(startThrows = true) else b
        }
        assertEquals(MIC, negotiate(micOnly, usb(profile(P32, 96000), profile(P24, 96000))).attempt.audioSource)
    }
}
