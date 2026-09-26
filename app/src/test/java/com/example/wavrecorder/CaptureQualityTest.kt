package com.example.wavrecorder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The capture-quality selection policy ([CaptureQuality]) on its own: levels, what each kind of
 * format evidence is credited with, and the tie-breaks. */
class CaptureQualityTest {

    private fun f(rate: Int, encoding: PcmEncoding, channels: Int = 1) = PcmFormat.of(rate, encoding, channels)
    private fun device(rate: Int, encoding: PcmEncoding, channels: Int = 1) = DeviceSideFormat(rate, encoding.androidEncoding, channels)
    private fun matched(format: PcmFormat) = CaptureQuality.scoreOf(format, device(format.sampleRate, format.encoding, format.channelCount))

    private val P16 = PcmEncoding.PCM_16
    private val P24 = PcmEncoding.PCM_24_PACKED
    private val P32 = PcmEncoding.PCM_32
    private val FLOAT = PcmEncoding.PCM_FLOAT

    @Test
    fun `24-bit at 192 kHz beats 32-bit at 8 kHz`() {
        assertTrue(matched(f(192000, P24)) > matched(f(8000, P32)))
        assertTrue(CaptureQuality.ceiling(f(192000, P24)) > CaptureQuality.ceiling(f(8000, P32)))
    }

    @Test
    fun `any full-band capture beats any telephony-rate capture, whatever the bit depth`() {
        val narrow = listOf(f(8000, P32), f(16000, FLOAT, 2), f(32000, P24, 2), f(44099, P32, 8))
        for (n in narrow) assertTrue("${n} vs 16-bit/44.1 kHz mono", matched(f(44100, P16)) > matched(n))
    }

    @Test
    fun `at least 24-bit beats 16-bit before sample rate, and rate decides within high precision`() {
        assertTrue(matched(f(48000, P24)) > matched(f(192000, P16, 2)))
        assertTrue(matched(f(192000, P24)) > matched(f(96000, P32)))
        assertTrue(matched(f(96000, P32)) > matched(f(96000, FLOAT)))
        assertTrue(matched(f(96000, FLOAT)) > matched(f(96000, P24)))
        assertTrue(matched(f(96000, P24, 2)) > matched(f(96000, P24, 1)))
    }

    @Test
    fun `a converted stream is credited only with what the device side carries`() {
        val converted = CaptureQuality.scoreOf(f(192000, P32, 2), device(48000, P24, 1))
        assertEquals(CaptureMode.CONVERTED, converted.mode)
        assertEquals(CaptureQuality.Level(48000, CaptureQuality.precision(P24), 1), converted.level)
        assertTrue("matched 24-bit beats a converted 32-bit stream of it", matched(f(48000, P24)) > converted)
        assertTrue(matched(f(48000, P24, 2)) > CaptureQuality.scoreOf(f(48000, P32, 2), device(48000, P24, 2)))
    }

    @Test
    fun `duplicated channels and padded bits add nothing`() {
        val duplicated = CaptureQuality.scoreOf(f(48000, P24, 2), device(48000, P24, 1))
        assertEquals(1, duplicated.level.channels)
        assertTrue(matched(f(48000, P24, 1)) > duplicated)
        val padded = CaptureQuality.scoreOf(f(48000, P24), device(48000, P16))
        assertEquals(CaptureQuality.precision(P16), padded.level.precision)
    }

    @Test
    fun `a matched 32-bit or float capture is credited in full`() {
        assertEquals(CaptureMode.MATCHED, matched(f(48000, P32)).mode)
        assertTrue(matched(f(48000, P32)) > matched(f(48000, P24)))
        assertTrue(matched(f(48000, FLOAT)) > matched(f(48000, P24)))
    }

    @Test
    fun `an unreported device side counts 32-bit or float only as 24-bit`() {
        val unreported32 = CaptureQuality.scoreOf(f(96000, P32), null)
        val unreported24 = CaptureQuality.scoreOf(f(96000, P24), null)
        assertEquals(CaptureMode.UNREPORTED, unreported32.mode)
        assertEquals(unreported24.level, unreported32.level)
        assertTrue("at an equal level, the smaller 24-bit stream is preferred", unreported24 > unreported32)
        assertTrue(unreported24 > CaptureQuality.scoreOf(f(96000, FLOAT), null))
        assertTrue("matched beats unreported beats converted at the same level",
            matched(f(96000, P24)) > unreported24 && unreported24 > CaptureQuality.scoreOf(f(96000, P32), device(96000, P24)))
    }

    @Test
    fun `ENCODING_DEFAULT on the device side is 16-bit PCM`() {
        assertEquals(CaptureMode.MATCHED, CaptureQuality.modeOf(f(48000, P16), DeviceSideFormat(48000, 1, 1)))
        assertTrue(!DeviceSideFormat(48000, 1, 1).differsFrom(f(48000, P16)))
    }

    @Test
    fun `below 44_1 kHz the best format still ranks, rather than nothing`() {
        assertTrue(matched(f(16000, P16)) > matched(f(8000, P16)))
        assertTrue(matched(f(16000, P24)) > matched(f(16000, P16)))
    }

    private fun exploratory(format: PcmFormat, device: DeviceSideFormat?, source: Int = NegotiatedAudio.AUDIO_SOURCE_UNPROCESSED) =
        CaptureQuality.scoreOf(format, device, source, CandidateOrigin.EXPLORATORY)

    @Test
    fun `an exploratory format with no device-side report earns no more than the baseline, and loses to it`() {
        val guess = exploratory(f(192000, P24, 2), null)
        assertEquals(CaptureQuality.BASELINE, guess.level)
        val baseline = CaptureQuality.scoreOf(PcmFormat.pcm16Mono(48000), null, origin = CandidateOrigin.COMPATIBILITY_FALLBACK)
        assertTrue("same credit: the format the app has always used wins over an unconfirmed larger one", baseline > guess)
        // Confirmed by the device side, it is credited in full -- or as far as the device side goes.
        assertEquals(CaptureQuality.ceiling(f(192000, P24, 2)), exploratory(f(192000, P24, 2), device(192000, P24, 2)).level)
        assertEquals(CaptureQuality.Level(48000, CaptureQuality.precision(P24), 2), exploratory(f(192000, P24, 2), device(48000, P24, 2)).level)
    }

    @Test
    fun `at an equal credited level, UNPROCESSED beats MIC, and a larger stream never wins on size alone`() {
        val mic = CaptureQuality.scoreOf(f(48000, P24, 2), device(48000, P24, 2), NegotiatedAudio.AUDIO_SOURCE_MIC)
        val unprocessed = CaptureQuality.scoreOf(f(48000, P24, 2), device(48000, P24, 2), NegotiatedAudio.AUDIO_SOURCE_UNPROCESSED)
        assertTrue(unprocessed > mic)
        // A higher credited level still beats the less-processed source.
        assertTrue(CaptureQuality.scoreOf(f(96000, P24, 2), device(96000, P24, 2), NegotiatedAudio.AUDIO_SOURCE_MIC) > unprocessed)

        // Both converted to the same 16-bit/48 kHz device side: the one that pads and upsamples less wins.
        val padded = CaptureQuality.scoreOf(f(96000, P32, 2), device(48000, P16, 2))
        val plain = CaptureQuality.scoreOf(f(48000, P16, 2), device(96000, P24, 2))
        assertEquals(padded.level, plain.level)
        assertTrue(plain > padded)
    }

    @Test
    fun `a device-side encoding that isn't known PCM is never credited with the client's precision`() {
        val unknownEncoding = CaptureQuality.scoreOf(f(48000, P32, 2), DeviceSideFormat(48000, 0x7000, 2))
        assertEquals(CaptureMode.CONVERTED, unknownEncoding.mode)
        assertEquals("as if unreported: 32-bit counts as 24", CaptureQuality.precision(P24), unknownEncoding.level.precision)
    }
}
