package com.example.wavrecorder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Candidate generation and ordering -- pure JVM. (The search over them: CaptureNegotiationTest.) */
class FormatNegotiationTest {

    private val PCM16 = PcmEncoding.PCM_16.androidEncoding
    private val PCM24 = PcmEncoding.PCM_24_PACKED.androidEncoding
    private val PCM32 = PcmEncoding.PCM_32.androidEncoding
    private val FLOAT = PcmEncoding.PCM_FLOAT.androidEncoding
    private val MONO = PcmFormat.CHANNEL_IN_MONO
    private val STEREO = PcmFormat.CHANNEL_IN_STEREO
    private val UNPROCESSED = NegotiatedAudio.AUDIO_SOURCE_UNPROCESSED
    private val MIC = NegotiatedAudio.AUDIO_SOURCE_MIC

    private fun profile(encoding: Int, rates: IntArray, masks: IntArray = intArrayOf(MONO), indexMasks: IntArray = IntArray(0)) =
        CapabilityProfile(encoding, rates, masks, indexMasks)

    private fun formats(caps: DeviceCapabilities, sdk: Int = 34) = FormatNegotiation.candidates(caps, sdk).map { it.format }

    private val fallbacks = FormatNegotiation.COMPATIBILITY_FALLBACKS

    @Test
    fun `nothing reported means the compatibility formats plus the documented exploratory set, marked as such`() {
        val candidates = FormatNegotiation.candidates(DeviceCapabilities.UNKNOWN, 34)
        val exploratory = candidates.filter { it.origin == CandidateOrigin.EXPLORATORY }.map { it.format }.toSet()
        val expected = mutableSetOf<PcmFormat>()
        for (encoding in listOf(PcmEncoding.PCM_16, PcmEncoding.PCM_24_PACKED))
            for (rate in listOf(192000, 96000, 48000, 44100)) for (channels in 1..2) expected += PcmFormat.of(rate, encoding, channels)
        expected -= fallbacks.toSet()
        assertEquals("16-bit or 24-bit, 44.1-192 kHz, mono or stereo: 14 beyond the compatibility formats", expected, exploratory)
        assertEquals(14, exploratory.size)
        assertEquals(fallbacks, candidates.filter { it.origin == CandidateOrigin.COMPATIBILITY_FALLBACK }.map { it.format })
        assertEquals("best possible first", PcmFormat.of(192000, PcmEncoding.PCM_24_PACKED, 2), candidates.first().format)
        assertEquals(16, candidates.size)
    }

    @Test
    fun `the exploratory encoding is the high-precision one AudioRecord takes on the API level`() {
        val below31 = FormatNegotiation.candidates(DeviceCapabilities.UNKNOWN, 30).filter { it.origin == CandidateOrigin.EXPLORATORY }
        assertEquals(setOf(PcmEncoding.PCM_16, PcmEncoding.PCM_FLOAT), below31.map { it.format.encoding }.toSet())
        assertEquals(PcmEncoding.PCM_24_PACKED, FormatNegotiation.exploratoryEncoding(31))
        assertEquals(PcmEncoding.PCM_FLOAT, FormatNegotiation.exploratoryEncoding(24))
    }

    @Test
    fun `explicitly reported but unsupported capabilities are never treated as unspecified`() {
        val unsupported = listOf(
            "only a compressed encoding" to DeviceCapabilities(encodings = intArrayOf(5 /* AC3 */)),
            "only encapsulated profiles" to DeviceCapabilities(profiles = listOf(
                CapabilityProfile(PCM16, intArrayOf(48000), intArrayOf(MONO), IntArray(0), encapsulated = true))),
            "only rates out of range" to DeviceCapabilities(sampleRates = intArrayOf(4000), encodings = intArrayOf(PCM16)),
            "only more channels than the app records" to DeviceCapabilities(encodings = intArrayOf(PCM16), channelIndexMasks = intArrayOf((1 shl 17) - 1)),
            "only 24-bit, below API 31" to DeviceCapabilities(encodings = intArrayOf(PCM24))
        )
        for ((what, caps) in unsupported) {
            val candidates = FormatNegotiation.candidates(caps, 30)
            assertEquals("$what: nothing invented, only the compatibility formats", fallbacks, candidates.map { it.format })
            assertTrue(what, candidates.none { it.origin == CandidateOrigin.EXPLORATORY })
        }
    }

    @Test
    fun `advertised capabilities are used exactly as advertised - exploration never caps or extends them`() {
        val caps = DeviceCapabilities(profiles = listOf(profile(PCM24, intArrayOf(384000), IntArray(0), intArrayOf(0b111111))))
        val candidates = FormatNegotiation.candidates(caps, 34)
        assertEquals(PcmFormat(384000, PcmEncoding.PCM_24_PACKED, 6, 0, 0b111111), candidates.first().format)
        assertTrue(candidates.none { it.origin == CandidateOrigin.EXPLORATORY })
        assertEquals(listOf(candidates.first().format) + fallbacks, candidates.map { it.format })
    }

    @Test
    fun `a profile that leaves only its rates unspecified explores rates only, with its own encoding and layout`() {
        val caps = DeviceCapabilities(profiles = listOf(profile(PCM24, IntArray(0), intArrayOf(STEREO))))
        val candidates = FormatNegotiation.candidates(caps, 34).filter { it.origin != CandidateOrigin.COMPATIBILITY_FALLBACK }
        assertEquals(
            listOf(192000 to CandidateOrigin.EXPLORATORY, 96000 to CandidateOrigin.EXPLORATORY, 48000 to CandidateOrigin.PROFILE, 44100 to CandidateOrigin.PROFILE),
            candidates.map { it.format.sampleRate to it.origin }
        )
        assertTrue(candidates.all { it.format.encoding == PcmEncoding.PCM_24_PACKED && it.format.channelMask == STEREO })
    }

    @Test
    fun `candidates are tried best possible first - at least 24-bit, then rate, then channels`() {
        val caps = DeviceCapabilities(profiles = listOf(
            profile(PCM16, intArrayOf(44100, 48000, 96000), intArrayOf(MONO, STEREO)),
            profile(PCM24, intArrayOf(48000), intArrayOf(MONO, STEREO))
        ))
        val ordered = formats(caps)
        assertEquals(
            listOf(
                PcmFormat.of(48000, PcmEncoding.PCM_24_PACKED, 2),
                PcmFormat.of(48000, PcmEncoding.PCM_24_PACKED, 1),
                PcmFormat.of(96000, PcmEncoding.PCM_16, 2),
                PcmFormat.of(96000, PcmEncoding.PCM_16, 1),
                PcmFormat.of(48000, PcmEncoding.PCM_16, 2),
                PcmFormat.of(48000, PcmEncoding.PCM_16, 1),
                PcmFormat.of(44100, PcmEncoding.PCM_16, 2),
                PcmFormat.of(44100, PcmEncoding.PCM_16, 1)
            ),
            ordered
        )
        // 48k and 44.1k 16-bit mono were advertised, so no separate fallback entries are appended.
        assertEquals(8, ordered.size)
    }

    @Test
    fun `correlated profiles never combine values from different profiles`() {
        // 24-bit is only offered in mono at 48 kHz; stereo and 96 kHz only with 16-bit.
        val caps = DeviceCapabilities(profiles = listOf(
            profile(PCM24, intArrayOf(48000), intArrayOf(MONO)),
            profile(PCM16, intArrayOf(96000), intArrayOf(STEREO))
        ))
        val ordered = formats(caps)
        assertFalse(ordered.any { it.encoding == PcmEncoding.PCM_24_PACKED && (it.channelCount == 2 || it.sampleRate == 96000) })
        assertFalse(ordered.any { it.encoding == PcmEncoding.PCM_16 && it.sampleRate == 96000 && it.channelCount == 1 })
        assertEquals(PcmFormat.of(48000, PcmEncoding.PCM_24_PACKED, 1), ordered.first())
    }

    @Test
    fun `at one rate, 32-bit is tried before float, 24-bit and 16-bit - the best each could be if matched`() {
        // Only the order of *trying*: which one is chosen depends on what the device side really
        // captures (see CaptureNegotiationTest) -- a converted 32-bit stream never beats matched 24-bit.
        val caps = DeviceCapabilities(profiles = listOf(
            profile(FLOAT, intArrayOf(48000)), profile(PCM24, intArrayOf(48000)), profile(PCM32, intArrayOf(48000))
        ))
        assertEquals(
            listOf(PcmEncoding.PCM_32, PcmEncoding.PCM_FLOAT, PcmEncoding.PCM_24_PACKED, PcmEncoding.PCM_16),
            formats(caps).map { it.encoding }.distinct()
        )
    }

    @Test
    fun `full-band rates are tried before telephony rates whatever their bit depth`() {
        val caps = DeviceCapabilities(profiles = listOf(
            profile(PCM32, intArrayOf(8000)), profile(PCM16, intArrayOf(8000, 16000)), profile(PCM24, intArrayOf(192000))
        ))
        val ordered = formats(caps)
        assertEquals(PcmFormat.of(192000, PcmEncoding.PCM_24_PACKED, 1), ordered.first())
        val fullBand = ordered.count { it.sampleRate >= 44100 }
        assertTrue(
            "every full-band format -- the 16-bit fallbacks included -- comes before any below 44.1 kHz",
            ordered.take(fullBand).all { it.sampleRate >= 44100 }
        )
        assertEquals(listOf(PcmFormat.of(192000, PcmEncoding.PCM_24_PACKED, 1)) + fallbacks, ordered.take(fullBand))
        assertEquals(PcmFormat.of(8000, PcmEncoding.PCM_32, 1), ordered[fullBand])
    }

    @Test
    fun `the exact client format for a reported device side is offered when the app can record it`() {
        val stereo24 = FormatNegotiation.exactCandidateFor(DeviceSideFormat(48000, PCM24, 2), 34)!!
        assertEquals(PcmFormat.of(48000, PcmEncoding.PCM_24_PACKED, 2), stereo24.format)
        assertEquals(CandidateOrigin.DEVICE_REPORTED, stereo24.origin)
        assertEquals("ENCODING_DEFAULT is 16-bit", PcmFormat.pcm16Mono(44100),
            FormatNegotiation.exactCandidateFor(DeviceSideFormat(44100, 1, 1), 34)!!.format)
        assertNull("24-bit needs API 31", FormatNegotiation.exactCandidateFor(DeviceSideFormat(48000, PCM24, 2), 30))
        assertNull("8-bit isn't recorded", FormatNegotiation.exactCandidateFor(DeviceSideFormat(48000, 3, 1), 34))
        assertNull(FormatNegotiation.exactCandidateFor(DeviceSideFormat(0, PCM16, 1), 34))
        assertNull(FormatNegotiation.exactCandidateFor(DeviceSideFormat(48000, PCM16, 0), 34))
    }

    @Test
    fun `index-mask channels are preserved as genuine multichannel candidates`() {
        val caps = DeviceCapabilities(profiles = listOf(profile(PCM24, intArrayOf(48000), IntArray(0), intArrayOf(0b1111))))
        val first = formats(caps).first()
        assertEquals(4, first.channelCount)
        assertEquals(0b1111, first.channelIndexMask)
    }

    @Test
    fun `encapsulated and non-PCM profiles are ignored`() {
        val caps = DeviceCapabilities(profiles = listOf(
            CapabilityProfile(PCM24, intArrayOf(48000), intArrayOf(MONO), IntArray(0), encapsulated = true),
            profile(3 /* PCM 8-bit */, intArrayOf(48000)),
            profile(0x0E /* AAC etc. */, intArrayOf(48000))
        ))
        assertEquals(fallbacks, formats(caps))
    }

    @Test
    fun `24 and 32-bit are not offered below API 31`() {
        val caps = DeviceCapabilities(
            sampleRates = intArrayOf(48000), encodings = intArrayOf(PCM16, PCM24, PCM32, FLOAT), channelCounts = intArrayOf(1)
        )
        assertEquals(listOf(PcmEncoding.PCM_FLOAT, PcmEncoding.PCM_16), formats(caps, sdk = 30).map { it.encoding }.distinct())
        assertTrue(formats(caps, sdk = 31).any { it.encoding == PcmEncoding.PCM_32 })
    }

    @Test
    fun `capability arrays (API 24-30) produce candidates that still need validation`() {
        val caps = DeviceCapabilities(
            sampleRates = intArrayOf(44100, 48000, 96000), encodings = intArrayOf(PCM16), channelCounts = intArrayOf(1, 2)
        )
        val candidates = FormatNegotiation.candidates(caps, 30)
        assertEquals(PcmFormat.of(96000, PcmEncoding.PCM_16, 2), candidates.first().format)
        assertTrue(candidates.all { it.origin == CandidateOrigin.CAPABILITY_ARRAYS })
    }

    @Test
    fun `an unspecified dimension adds exploratory values, marked as such, and leaves the advertised ones exact`() {
        // Only a 96 kHz rate is reported: nothing about encoding or channels.
        val candidates = FormatNegotiation.candidates(DeviceCapabilities(sampleRates = intArrayOf(96000)), 34)
            .filter { it.origin != CandidateOrigin.COMPATIBILITY_FALLBACK }
        assertTrue("the advertised rate is kept exactly", candidates.all { it.format.sampleRate == 96000 })
        assertEquals(
            setOf(
                PcmFormat.of(96000, PcmEncoding.PCM_16, 1) to CandidateOrigin.CAPABILITY_ARRAYS,   // the baseline values
                PcmFormat.of(96000, PcmEncoding.PCM_16, 2) to CandidateOrigin.EXPLORATORY,
                PcmFormat.of(96000, PcmEncoding.PCM_24_PACKED, 1) to CandidateOrigin.EXPLORATORY,
                PcmFormat.of(96000, PcmEncoding.PCM_24_PACKED, 2) to CandidateOrigin.EXPLORATORY
            ),
            candidates.map { it.format to it.origin }.toSet()
        )

        // Only channels are reported: the baseline rates keep their origin, higher ones are exploratory.
        val stereoOnly = FormatNegotiation.candidates(DeviceCapabilities(channelCounts = intArrayOf(2)), 34)
        assertTrue(stereoOnly.all { it.format.sampleRate <= 48000 || it.origin == CandidateOrigin.EXPLORATORY })
        assertTrue(stereoOnly.filter { it.origin != CandidateOrigin.COMPATIBILITY_FALLBACK }.all { it.format.channelCount == 2 })
    }

    @Test
    fun `nonsense rates are ignored`() {
        val caps = DeviceCapabilities(profiles = listOf(profile(PCM16, intArrayOf(0, -1, 4000, 10_000_000, 48000))))
        assertEquals(listOf(48000, 44100), formats(caps).map { it.sampleRate })
    }

    @Test
    fun `attempts are format first -- each format with UNPROCESSED then MIC -- in quality order`() {
        val caps = DeviceCapabilities(profiles = listOf(
            profile(PCM24, intArrayOf(96000)), profile(PCM16, intArrayOf(48000), intArrayOf(STEREO))
        ))
        val attempts = FormatNegotiation.attemptOrder(FormatNegotiation.candidates(caps, 34), listOf(UNPROCESSED, MIC))
        val described = attempts.map { it.candidate.format to it.audioSource }
        val best = PcmFormat.of(96000, PcmEncoding.PCM_24_PACKED, 1)
        val next = PcmFormat.of(48000, PcmEncoding.PCM_16, 2)
        assertEquals(
            listOf(
                best to UNPROCESSED, best to MIC,
                next to UNPROCESSED, next to MIC,
                fallbacks[0] to UNPROCESSED, fallbacks[0] to MIC,
                fallbacks[1] to UNPROCESSED, fallbacks[1] to MIC
            ),
            described
        )
    }
}
