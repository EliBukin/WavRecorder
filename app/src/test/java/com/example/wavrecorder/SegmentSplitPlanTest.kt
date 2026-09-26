package com.example.wavrecorder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The earlier of the chosen split duration and the WAV size limit, always in whole frames. */
class SegmentSplitPlanTest {

    private fun plan(format: PcmFormat, minutes: Long = 60, buffer: Int = 64 * 1024) =
        SegmentSplitPlan.of(format, minutes * 60, buffer)

    @Test
    fun `standard formats split exactly at the chosen duration`() {
        val p = plan(PcmFormat.pcm16Mono(48000))
        assertFalse(p.limitedByRiff)
        assertEquals(48000L * 2 * 3600, p.effectiveLimitBytes)
        assertEquals(3600.0, p.effectiveSeconds, 0.0)
        // 96 kHz / 24-bit stereo: 2.07 GB per hour still fits.
        assertFalse(plan(PcmFormat.of(96000, PcmEncoding.PCM_24_PACKED, 2)).limitedByRiff)
    }

    @Test
    fun `high-resolution formats split early, before the WAV size limit`() {
        val format = PcmFormat.of(192000, PcmEncoding.PCM_32, 2) // 1.536 MB/s: 5.5 GB per hour
        val p = plan(format)
        assertTrue(p.limitedByRiff)
        assertTrue(p.effectiveLimitBytes < p.userLimitBytes)
        assertEquals(0L, p.effectiveLimitBytes % format.bytesPerFrame)
        assertTrue(p.effectiveLimitBytes <= WavHeaderWriter.maxDataBytes(format) - SegmentSplitPlan.RIFF_SAFETY_MARGIN_BYTES)
        assertEquals(46.0, p.effectiveSeconds / 60, 1.0) // ~46 minutes instead of 60
        // A 30-minute choice already fits: the chosen duration wins again.
        assertFalse(plan(format, minutes = 30).limitedByRiff)
    }

    @Test
    fun `a segment can overshoot by a whole read and still be representable`() {
        for (format in listOf(
            PcmFormat.of(384000, PcmEncoding.PCM_32, 8),
            PcmFormat.of(192000, PcmEncoding.PCM_24_PACKED, 1),
            PcmFormat.of(96000, PcmEncoding.PCM_FLOAT, 6)
        )) {
            val buffer = 1024 * 1024
            val p = plan(format, buffer = buffer)
            val worstCase = p.effectiveLimitBytes - 1 + buffer
            assertTrue("$format", worstCase <= p.hardLimitBytes)
            assertEquals(0L, p.riffLimitBytes % format.bytesPerFrame)
        }
    }

    @Test
    fun `odd-sized frames keep every limit aligned`() {
        val mono24 = PcmFormat.of(44100, PcmEncoding.PCM_24_PACKED, 1) // 3-byte frames
        val p = plan(mono24, minutes = 45)
        assertEquals(0L, p.effectiveLimitBytes % 3)
        assertEquals(0L, p.riffLimitBytes % 3)
        assertEquals(0L, p.hardLimitBytes % 3)
    }

    @Test
    fun `the margin grows with an unusually large read buffer`() {
        val format = PcmFormat.of(384000, PcmEncoding.PCM_32, 16)
        val huge = 100 * 1024 * 1024
        val p = plan(format, buffer = huge)
        assertTrue(p.riffLimitBytes <= WavHeaderWriter.maxDataBytes(format) - 2L * huge)
    }
}
