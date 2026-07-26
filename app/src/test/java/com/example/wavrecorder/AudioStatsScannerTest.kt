package com.example.wavrecorder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioStatsScannerTest {

    /** Simulates a real (e.g. SAF-backed) stream that never hands back more than [chunkSize]
     * bytes per read() call, regardless of how much the caller asked for — so a 16-bit sample
     * can land split across two separate read() calls. */
    private class ChunkedInputStream(private val data: ByteArray, private val chunkSize: Int) : InputStream() {
        private var pos = 0
        override fun read(): Int {
            if (pos >= data.size) return -1
            return data[pos++].toInt() and 0xFF
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= data.size) return -1
            val n = minOf(len, chunkSize, data.size - pos)
            System.arraycopy(data, pos, b, off, n)
            pos += n
            return n
        }
    }

    private fun samplesToBytes(samples: ShortArray): ByteArray {
        val buf = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { buf.putShort(it) }
        return buf.array()
    }

    private val testSamples = shortArrayOf(0, 100, -100, 32767, -32768, 5000, -5000, 1, -1, 12345)

    @Test
    fun `contiguous read matches expected sample count and peak`() {
        val bytes = samplesToBytes(testSamples)

        val result = scanPcm16Samples(ByteArrayInputStream(bytes), bytes.size.toLong())

        assertEquals(testSamples.size.toLong(), result.sampleCount)
        assertEquals(32768, result.peak) // abs(-32768)
        assertEquals(2L, result.clippedSamples) // 32767 and -32768 both have magnitude >= 32767
    }

    @Test
    fun `every possible odd chunk boundary yields identical results to a contiguous read`() {
        val bytes = samplesToBytes(testSamples)
        val reference = scanPcm16Samples(ByteArrayInputStream(bytes), bytes.size.toLong())

        for (chunkSize in 1..7) {
            val chunked = scanPcm16Samples(ChunkedInputStream(bytes, chunkSize), bytes.size.toLong())

            assertEquals("chunkSize=$chunkSize sampleCount", reference.sampleCount, chunked.sampleCount)
            assertEquals("chunkSize=$chunkSize peak", reference.peak, chunked.peak)
            assertEquals("chunkSize=$chunkSize sumSquares", reference.sumSquares, chunked.sumSquares, 0.0001)
            assertEquals("chunkSize=$chunkSize clipped", reference.clippedSamples, chunked.clippedSamples)
        }
    }

    @Test
    fun `no samples are lost when every read returns a single byte`() {
        // This is exactly the scenario the old implementation got wrong: treating each
        // InputStream#read() buffer independently discards a trailing unpaired byte and
        // resyncs on the wrong boundary for the rest of the stream.
        val bytes = samplesToBytes(testSamples)

        val result = scanPcm16Samples(ChunkedInputStream(bytes, 1), bytes.size.toLong())

        assertEquals(testSamples.size.toLong(), result.sampleCount)
    }

    @Test
    fun `stops at dataSize even if the underlying stream has more bytes`() {
        val bytes = samplesToBytes(testSamples) + samplesToBytes(shortArrayOf(999, 999))
        val dataSize = (testSamples.size * 2).toLong()

        val result = scanPcm16Samples(ByteArrayInputStream(bytes), dataSize)

        assertEquals(testSamples.size.toLong(), result.sampleCount)
        assertEquals(dataSize, result.bytesRead)
    }

    @Test
    fun `truncated stream stops cleanly without throwing and reports fewer bytes than declared`() {
        val bytes = samplesToBytes(testSamples)
        val declaredDataSize = bytes.size.toLong() + 1000 // header claims more audio than exists

        val result = scanPcm16Samples(ByteArrayInputStream(bytes), declaredDataSize)

        assertEquals(testSamples.size.toLong(), result.sampleCount)
        // The caller (AudioStatsReader) uses this to detect and reject a truncated file instead
        // of silently trusting the declared size for duration.
        assertEquals(bytes.size.toLong(), result.bytesRead)
        assertTrue("bytesRead should be less than what the header declared", result.bytesRead < declaredDataSize)
    }
}
