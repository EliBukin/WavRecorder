package com.example.wavrecorder

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * [DirectPcmReader], the one production read path, driven by a stand-in for
 * `AudioRecord.read(ByteBuffer, size, READ_BLOCKING)` that behaves as the platform does: it writes
 * raw samples of the configured encoding, in the platform's native byte order, from the start of
 * the direct buffer, and returns the byte count or a negative error code.
 */
class PcmReaderTest {

    /** Writes [samples] (each already encoded in [order]) into the direct buffer like AudioRecord,
     * up to the requested size; records every buffer it was handed. */
    private class FakeAudioRecord(private val chunks: List<ByteArray>) {
        val buffers = mutableListOf<ByteBuffer>()
        val requests = mutableListOf<Int>()
        private var next = 0
        var result: Int? = null

        fun read(buffer: ByteBuffer, size: Int): Int {
            buffers += buffer
            requests += size
            result?.let { return it }
            if (next >= chunks.size) return 0
            val chunk = chunks[next++]
            val n = minOf(chunk.size, size)
            for (i in 0 until n) buffer.put(i, chunk[i]) // absolute: position stays unchanged
            return n
        }
    }

    // --- Encoders for each encoding in an explicit byte order, used to build what the platform
    // --- would write (native order) and what WAV must contain (little-endian).

    private fun pcm16(order: ByteOrder, vararg v: Int) =
        ByteBuffer.allocate(2 * v.size).order(order).apply { v.forEach { putShort(it.toShort()) } }.array()

    private fun pcm32(order: ByteOrder, vararg v: Int) =
        ByteBuffer.allocate(4 * v.size).order(order).apply { v.forEach { putInt(it) } }.array()

    private fun float32(order: ByteOrder, vararg v: Float) =
        ByteBuffer.allocate(4 * v.size).order(order).apply { v.forEach { putFloat(it) } }.array()

    private fun pcm24(order: ByteOrder, vararg v: Int): ByteArray = v.flatMap { s ->
        val le = listOf(s.toByte(), (s shr 8).toByte(), (s shr 16).toByte())
        if (order == ByteOrder.LITTLE_ENDIAN) le else le.reversed()
    }.toByteArray()

    private data class Case(val format: PcmFormat, val encode: (ByteOrder) -> ByteArray)

    private val cases = listOf(
        Case(PcmFormat.of(48000, PcmEncoding.PCM_16, 1)) { pcm16(it, -32768, -1, 0, 1, 32767, 0x1234) },
        Case(PcmFormat.of(48000, PcmEncoding.PCM_24_PACKED, 2)) { pcm24(it, -8388608, -1, 0, 1, 8388607, 0x123456) },
        Case(PcmFormat.of(96000, PcmEncoding.PCM_32, 2)) { pcm32(it, Int.MIN_VALUE, -1, 0, 1, Int.MAX_VALUE, 0x12345678) },
        Case(PcmFormat.of(48000, PcmEncoding.PCM_FLOAT, 2)) { float32(it, -1f, 0f, 1f, 0.5f, -0.25f, 1e-7f) }
    )

    private fun readAll(format: PcmFormat, platform: ByteArray, nativeOrder: ByteOrder): ByteArray {
        val fake = FakeAudioRecord(listOf(platform))
        val reader = DirectPcmReader(format, 4096, nativeOrder, fake::read)
        val out = ByteArray(platform.size)
        assertEquals(platform.size, reader.read(out, 0, out.size))
        return out
    }

    @Test
    fun `every encoding arrives as its exact little-endian bytes on a little-endian platform`() {
        for (case in cases) {
            val bytes = case.encode(ByteOrder.LITTLE_ENDIAN)
            assertArrayEquals("${case.format.encoding}", bytes, readAll(case.format, bytes, ByteOrder.LITTLE_ENDIAN))
        }
    }

    @Test
    fun `every encoding is converted to exact little-endian bytes on a big-endian platform`() {
        for (case in cases) {
            val platform = case.encode(ByteOrder.BIG_ENDIAN)
            assertArrayEquals("${case.format.encoding}", case.encode(ByteOrder.LITTLE_ENDIAN), readAll(case.format, platform, ByteOrder.BIG_ENDIAN))
        }
    }

    @Test
    fun `float -1, 0 and 1 are stored bit-exactly and decode back to themselves`() {
        val format = PcmFormat.of(48000, PcmEncoding.PCM_FLOAT, 1)
        for (order in listOf(ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN)) {
            val out = readAll(format, float32(order, -1f, 0f, 1f), order)
            // IEEE 754: -1 = 0xBF800000, 0 = 0, 1 = 0x3F800000, little-endian.
            assertArrayEquals(
                byteArrayOf(0, 0, 0x80.toByte(), 0xBF.toByte(), 0, 0, 0, 0, 0, 0, 0x80.toByte(), 0x3F),
                out
            )
            assertEquals(-1.0, PcmSamples.sampleAt(out, 0, PcmEncoding.PCM_FLOAT), 0.0)
            assertEquals(0.0, PcmSamples.sampleAt(out, 4, PcmEncoding.PCM_FLOAT), 0.0)
            assertEquals(1.0, PcmSamples.sampleAt(out, 8, PcmEncoding.PCM_FLOAT), 0.0)
        }
    }

    @Test
    fun `24 and 32-bit samples keep every bit of precision`() {
        val f24 = PcmFormat.of(48000, PcmEncoding.PCM_24_PACKED, 1)
        val out24 = readAll(f24, pcm24(ByteOrder.LITTLE_ENDIAN, 8388607, -8388608, 1, -1), ByteOrder.LITTLE_ENDIAN)
        assertEquals(listOf(8388607.0, -8388608.0, 1.0, -1.0), (0 until 4).map { PcmSamples.sampleAt(out24, it * 3, PcmEncoding.PCM_24_PACKED) })

        val f32 = PcmFormat.of(48000, PcmEncoding.PCM_32, 1)
        val out32 = readAll(f32, pcm32(ByteOrder.BIG_ENDIAN, Int.MAX_VALUE, Int.MIN_VALUE, 1, -1), ByteOrder.BIG_ENDIAN)
        assertEquals(
            listOf(Int.MAX_VALUE.toDouble(), Int.MIN_VALUE.toDouble(), 1.0, -1.0),
            (0 until 4).map { PcmSamples.sampleAt(out32, it * 4, PcmEncoding.PCM_32) }
        )
    }

    @Test
    fun `samples land at the destination offset and nothing outside them is touched`() {
        val format = PcmFormat.of(48000, PcmEncoding.PCM_24_PACKED, 2) // 6-byte frames
        val samples = pcm24(ByteOrder.LITTLE_ENDIAN, 1, 2, 3, 4)       // 2 frames
        val reader = DirectPcmReader(format, 600, ByteOrder.LITTLE_ENDIAN, FakeAudioRecord(listOf(samples))::read)
        val out = ByteArray(40) { 0x55 }

        assertEquals(12, reader.read(out, 7, 30))

        assertArrayEquals(ByteArray(7) { 0x55 }, out.copyOfRange(0, 7))
        assertArrayEquals(samples, out.copyOfRange(7, 19))
        assertArrayEquals(ByteArray(21) { 0x55 }, out.copyOfRange(19, 40))
    }

    @Test
    fun `a partial read copies only what was read and reports exactly that`() {
        val format = PcmFormat.of(48000, PcmEncoding.PCM_32, 2) // 8-byte frames
        val first = pcm32(ByteOrder.LITTLE_ENDIAN, 10, 20)          // 1 frame of the 4 requested
        val second = pcm32(ByteOrder.LITTLE_ENDIAN, 30, 40, 50, 60) // 2 frames
        val reader = DirectPcmReader(format, 64, ByteOrder.LITTLE_ENDIAN, FakeAudioRecord(listOf(first, second))::read)
        val out = ByteArray(32)

        assertEquals(8, reader.read(out, 0, 32))
        assertEquals(16, reader.read(out, 8, 24))

        assertArrayEquals(first + second + ByteArray(8), out)
    }

    @Test
    fun `requests are whole frames, never more than the buffer holds`() {
        val format = PcmFormat.of(48000, PcmEncoding.PCM_24_PACKED, 2) // 6-byte frames
        val fake = FakeAudioRecord(emptyList())
        val reader = DirectPcmReader(format, 100, ByteOrder.LITTLE_ENDIAN, fake::read) // 96 usable
        val out = ByteArray(1000)

        reader.read(out, 0, 17)   // -> 12 (2 frames)
        reader.read(out, 0, 1000) // -> capacity, 96
        reader.read(out, 0, 6)    // -> exactly one frame

        assertEquals(96, reader.capacity)
        assertEquals(listOf(12, 96, 6), fake.requests)
        assertEquals("less than a frame asks for nothing", 0, reader.read(out, 0, 5))
        assertEquals(3, fake.requests.size)
    }

    @Test
    fun `negative AudioRecord errors are returned unchanged and leave the destination untouched`() {
        val format = PcmFormat.of(48000, PcmEncoding.PCM_FLOAT, 1)
        for (error in listOf(-1, -2, -3, -6)) { // ERROR, BAD_VALUE, INVALID_OPERATION, DEAD_OBJECT
            val fake = FakeAudioRecord(emptyList()).apply { result = error }
            val reader = DirectPcmReader(format, 64, ByteOrder.LITTLE_ENDIAN, fake::read)
            val out = ByteArray(16) { 7 }
            assertEquals(error, reader.read(out, 0, 16))
            assertArrayEquals(ByteArray(16) { 7 }, out)
        }
    }

    @Test
    fun `an empty read returns 0`() {
        val reader = DirectPcmReader(PcmFormat.pcm16Mono(48000), 64, ByteOrder.LITTLE_ENDIAN, FakeAudioRecord(emptyList())::read)
        assertEquals(0, reader.read(ByteArray(64), 0, 64))
    }

    @Test
    fun `one direct buffer is allocated on the first read and reused for every read after`() {
        val format = PcmFormat.of(48000, PcmEncoding.PCM_FLOAT, 2)
        val fake = FakeAudioRecord(List(50) { float32(ByteOrder.nativeOrder(), 0.1f, -0.1f) })
        val reader = DirectPcmReader(format, 4096, readInto = fake::read)
        assertNull("nothing is allocated before the first read", reader.directBuffer)

        val out = ByteArray(4096)
        repeat(60) { reader.read(out, 0, out.size) }

        val first = fake.buffers.first()
        assertTrue(first.isDirect)
        assertEquals(4096, first.capacity())
        assertTrue("every read reuses the same buffer", fake.buffers.all { it === first })
        assertSame(first, reader.directBuffer)
    }

    @Test
    fun `invalid arguments and impossible results are refused`() {
        val fake = FakeAudioRecord(emptyList())
        val reader = DirectPcmReader(PcmFormat.pcm16Mono(48000), 64, ByteOrder.LITTLE_ENDIAN, fake::read)
        assertThrows(IllegalArgumentException::class.java) { reader.read(ByteArray(8), 4, 8) }
        assertThrows(IllegalArgumentException::class.java) { reader.read(ByteArray(8), -1, 2) }

        fake.result = 128 // more than the 8 bytes requested
        assertThrows(IllegalStateException::class.java) { reader.read(ByteArray(8), 0, 8) }
    }

    @Test
    fun `byte swapping reverses whole samples only`() {
        val b = byteArrayOf(1, 2, 3, 4, 5, 6, 7)
        swapSamplesToLittleEndian(b, 0, 7, 3)
        assertArrayEquals(byteArrayOf(3, 2, 1, 6, 5, 4, 7), b)

        val c = byteArrayOf(9, 1, 2, 3, 4, 9)
        swapSamplesToLittleEndian(c, 1, 4, 4)
        assertArrayEquals(byteArrayOf(9, 4, 3, 2, 1, 9), c)

        val d = byteArrayOf(1, 2, 3, 4)
        swapSamplesToLittleEndian(d, 0, 4, 2)
        assertArrayEquals(byteArrayOf(2, 1, 4, 3), d)
    }

    @Test
    fun `empty reads back off 1, 2, 4, 8 then 10 ms, and start over after data`() {
        val sleeps = mutableListOf<Long>()
        val backoff = EmptyReadBackoff { sleeps += it }
        repeat(7) { backoff.onEmpty() }
        backoff.onData()
        backoff.onEmpty()
        assertEquals(listOf(1L, 2L, 4L, 8L, 10L, 10L, 10L, 1L), sleeps)
    }
}
