package com.example.wavrecorder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Decoding and levels for every encoding: sign extension, extremes, float safety, channels. */
class PcmSamplesTest {

    private fun le(size: Int, fill: ByteBuffer.() -> Unit): ByteArray =
        ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).apply(fill).array()

    private fun int24(value: Int) = byteArrayOf(value.toByte(), (value shr 8).toByte(), (value shr 16).toByte())

    private fun decode(bytes: ByteArray, encoding: PcmEncoding) = PcmSamples.sampleAt(bytes, 0, encoding)

    @Test
    fun `16-bit extremes`() {
        assertEquals(32767.0, decode(le(2) { putShort(32767) }, PcmEncoding.PCM_16), 0.0)
        assertEquals(-32768.0, decode(le(2) { putShort(-32768) }, PcmEncoding.PCM_16), 0.0)
        assertEquals(-1.0, decode(le(2) { putShort(-1) }, PcmEncoding.PCM_16), 0.0)
    }

    @Test
    fun `packed 24-bit samples are sign-extended correctly, including the extremes`() {
        assertEquals(8388607.0, decode(int24(0x7FFFFF), PcmEncoding.PCM_24_PACKED), 0.0)
        assertEquals(-8388608.0, decode(int24(0x800000), PcmEncoding.PCM_24_PACKED), 0.0)
        assertEquals(-1.0, decode(int24(0xFFFFFF), PcmEncoding.PCM_24_PACKED), 0.0)
        assertEquals(1.0, decode(int24(0x000001), PcmEncoding.PCM_24_PACKED), 0.0)
        assertEquals(-256.0, decode(int24(-256), PcmEncoding.PCM_24_PACKED), 0.0)
        assertEquals(0x123456.toDouble(), decode(int24(0x123456), PcmEncoding.PCM_24_PACKED), 0.0)
    }

    @Test
    fun `32-bit extremes`() {
        assertEquals(Int.MAX_VALUE.toDouble(), decode(le(4) { putInt(Int.MAX_VALUE) }, PcmEncoding.PCM_32), 0.0)
        assertEquals(Int.MIN_VALUE.toDouble(), decode(le(4) { putInt(Int.MIN_VALUE) }, PcmEncoding.PCM_32), 0.0)
    }

    @Test
    fun `float NaN, infinities and out-of-range values are made safe`() {
        fun f(v: Float) = decode(le(4) { putFloat(v) }, PcmEncoding.PCM_FLOAT)
        assertEquals(0.0, f(Float.NaN), 0.0)
        assertEquals(1.0, f(Float.POSITIVE_INFINITY), 0.0)
        assertEquals(-1.0, f(Float.NEGATIVE_INFINITY), 0.0)
        assertEquals(1.0, f(1.5f), 0.0)
        assertEquals(-1.0, f(-7f), 0.0)
        assertEquals(0.25, f(0.25f), 1e-9)
        assertTrue(PcmSamples.isClipped(f(Float.POSITIVE_INFINITY), PcmEncoding.PCM_FLOAT))
        assertTrue(!PcmSamples.isClipped(f(Float.NaN), PcmEncoding.PCM_FLOAT))
    }

    @Test
    fun `the peak is taken across every interleaved channel`() {
        // Stereo 24-bit: left quiet, right at full scale in the second frame.
        val bytes = int24(1000) + int24(-2000) + int24(10) + int24(0x7FFFFF)
        val format = PcmFormat.of(48000, PcmEncoding.PCM_24_PACKED, 2)
        assertEquals(1.0, PcmSamples.peakNormalized(bytes, bytes.size, format), 1e-9)
        assertEquals(1f, peakAmplitude(bytes, bytes.size, format), 0f)
    }

    @Test
    fun `a trailing partial frame is ignored by the level`() {
        val format = PcmFormat.of(48000, PcmEncoding.PCM_24_PACKED, 2)
        val bytes = int24(100) + int24(100) + int24(0x7FFFFF) // frame + half a frame
        assertTrue(PcmSamples.peakNormalized(bytes, bytes.size, format) < 0.001)
    }

    @Test
    fun `levels are identical for 16-bit whichever entry point is used`() {
        val bytes = le(8) { putShort(1000); putShort(-12000); putShort(5); putShort(0) }
        assertEquals(peakAmplitude(bytes, bytes.size), peakAmplitude(bytes, bytes.size, PcmFormat.pcm16Mono(44100)), 0f)
        assertEquals(levelFromLinearPeak(12000 / 32767.0), peakAmplitude(bytes, bytes.size), 1e-6f)
    }

    @Test
    fun `level scale endpoints`() {
        assertEquals(0f, levelFromLinearPeak(0.0), 0f)
        assertEquals(1f, levelFromLinearPeak(1.0), 0f)
        assertEquals(0f, levelFromLinearPeak(Math.pow(10.0, -50 / 20.0)), 0f) // below the -45 dB floor
        assertEquals(0.5f, levelFromLinearPeak(Math.pow(10.0, -22.5 / 20.0)), 1e-5f)
    }

    /** Hands back at most [chunk] bytes per read, to split samples across reads. */
    private class Chunked(private val data: ByteArray, private val chunk: Int) : InputStream() {
        private var pos = 0
        override fun read(): Int = if (pos < data.size) data[pos++].toInt() and 0xFF else -1
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= data.size) return -1
            val n = minOf(len, chunk, data.size - pos)
            System.arraycopy(data, pos, b, off, n); pos += n; return n
        }
    }

    @Test
    fun `24-bit statistics are identical however reads split the samples`() {
        val samples = intArrayOf(0, 8388607, -8388608, 12345, -1, 42, -4000000)
        val bytes = samples.fold(ByteArray(0)) { acc, v -> acc + int24(v) }
        val reference = scanPcmSamples(ByteArrayInputStream(bytes), bytes.size.toLong(), PcmEncoding.PCM_24_PACKED)
        assertEquals(samples.size.toLong(), reference.sampleCount)
        assertEquals(8388608.0, reference.peak, 0.0)
        assertEquals(2L, reference.clippedSamples)
        for (chunk in 1..8) {
            val chunked = scanPcmSamples(Chunked(bytes, chunk), bytes.size.toLong(), PcmEncoding.PCM_24_PACKED)
            assertEquals("chunk=$chunk", reference, chunked)
        }
    }

    @Test
    fun `float statistics never become non-finite`() {
        val bytes = le(20) { putFloat(Float.NaN); putFloat(0.5f); putFloat(Float.POSITIVE_INFINITY); putFloat(-2f); putFloat(0f) }
        val result = scanPcmSamples(ByteArrayInputStream(bytes), bytes.size.toLong(), PcmEncoding.PCM_FLOAT)
        assertEquals(5L, result.sampleCount)
        assertEquals(1.0, result.peak, 0.0)
        assertEquals(2L, result.clippedSamples)
        assertTrue(result.sumSquares.isFinite())
        assertEquals(0.25 + 1 + 1, result.sumSquares, 1e-9)
    }

    @Test
    fun `32-bit statistics and a truncated final sample`() {
        val bytes = le(10) { putInt(Int.MAX_VALUE); putInt(-5); put(1); put(2) }
        val result = scanPcmSamples(ByteArrayInputStream(bytes), bytes.size.toLong(), PcmEncoding.PCM_32)
        assertEquals(2L, result.sampleCount)
        assertEquals(1L, result.clippedSamples)
        assertEquals(10L, result.bytesRead)
    }
}
