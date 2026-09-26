package com.example.wavrecorder

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Headers for every format this app can record: layout, sizes, fields, round trip, 4 GiB limit. */
class WavHeaderWriterFormatTest {

    private fun bytes(format: PcmFormat, dataLen: Long, pad: Boolean = true): ByteArray {
        val header = WavHeaderWriter.build(format, dataLen, pad)
        return ByteArray(header.remaining()).also { header.duplicate().get(it) }
    }

    private fun le(bytes: ByteArray) = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    private fun uint(bytes: ByteArray, at: Int) = le(bytes).getInt(at).toLong() and 0xFFFFFFFFL

    @Test
    fun `16-bit mono and stereo are byte-identical to the legacy 44-byte header`() {
        for (channels in 1..2) for (rate in intArrayOf(44100, 48000)) {
            val legacy = WavHeaderWriter.build(rate, channels, 16, 123_456L)
            val legacyBytes = ByteArray(legacy.remaining()).also { legacy.get(it) }
            assertArrayEquals(legacyBytes, bytes(PcmFormat.of(rate, PcmEncoding.PCM_16, channels), 123_456L))
        }
    }

    @Test
    fun `header sizes per layout`() {
        assertEquals(44, WavHeaderWriter.headerSize(PcmFormat.pcm16Mono(48000)))
        assertEquals(58, WavHeaderWriter.headerSize(PcmFormat.of(48000, PcmEncoding.PCM_FLOAT, 1)))
        assertEquals(68, WavHeaderWriter.headerSize(PcmFormat.of(48000, PcmEncoding.PCM_24_PACKED, 2)))
        assertEquals(80, WavHeaderWriter.headerSize(PcmFormat.of(48000, PcmEncoding.PCM_FLOAT, 4)))
        for (format in listOf(PcmFormat.pcm16Mono(48000), PcmFormat.of(48000, PcmEncoding.PCM_32, 6))) {
            assertEquals(WavHeaderWriter.headerSize(format), bytes(format, 0).size)
        }
    }

    @Test
    fun `24-bit stereo is WAVE_FORMAT_EXTENSIBLE with every field correct`() {
        val format = PcmFormat.of(96000, PcmEncoding.PCM_24_PACKED, 2)
        val h = bytes(format, 600)
        val b = le(h)
        assertEquals("RIFF", String(h, 0, 4, Charsets.US_ASCII))
        assertEquals(68L - 8 + 600, uint(h, 4))
        assertEquals("fmt ", String(h, 12, 4, Charsets.US_ASCII))
        assertEquals(40, b.getInt(16))
        assertEquals(0xFFFE, b.getShort(20).toInt() and 0xFFFF)
        assertEquals(2, b.getShort(22).toInt())
        assertEquals(96000, b.getInt(24))
        assertEquals(576_000, b.getInt(28)) // byte rate
        assertEquals(6, b.getShort(32).toInt()) // block align
        assertEquals(24, b.getShort(34).toInt()) // container bits
        assertEquals(22, b.getShort(36).toInt()) // cbSize
        assertEquals(24, b.getShort(38).toInt()) // valid bits
        assertEquals(0x3, b.getInt(40)) // front left | front right
        assertArrayEquals(WavHeaderWriter.SUBFORMAT_PCM, h.copyOfRange(44, 60))
        assertEquals("data", String(h, 60, 4, Charsets.US_ASCII))
        assertEquals(600L, uint(h, 64))
    }

    @Test
    fun `mono float uses WAVE_FORMAT_IEEE_FLOAT with a fact chunk counting frames`() {
        val format = PcmFormat.of(48000, PcmEncoding.PCM_FLOAT, 1)
        val h = bytes(format, 4000)
        val b = le(h)
        assertEquals(18, b.getInt(16))
        assertEquals(3, b.getShort(20).toInt())
        assertEquals(32, b.getShort(34).toInt())
        assertEquals(0, b.getShort(36).toInt()) // cbSize
        assertEquals("fact", String(h, 38, 4, Charsets.US_ASCII))
        assertEquals(4, b.getInt(42))
        assertEquals(1000, b.getInt(46)) // 4000 bytes / 4 bytes per frame
        assertEquals("data", String(h, 50, 4, Charsets.US_ASCII))
        assertEquals(4000, b.getInt(54))
    }

    @Test
    fun `multichannel float is EXTENSIBLE with the IEEE float subformat`() {
        val format = PcmFormat.of(48000, PcmEncoding.PCM_FLOAT, 4)
        val h = bytes(format, 1600)
        assertEquals(0xFFFE, le(h).getShort(20).toInt() and 0xFFFF)
        assertEquals(16, le(h).getShort(32).toInt()) // 4 channels x 4 bytes
        assertEquals(0, le(h).getInt(40)) // no speaker positions for index-mask channels
        assertArrayEquals(WavHeaderWriter.SUBFORMAT_IEEE_FLOAT, h.copyOfRange(44, 60))
        assertEquals("fact", String(h, 60, 4, Charsets.US_ASCII))
        assertEquals(100, le(h).getInt(68)) // 1600 / 16
    }

    @Test
    fun `block alignment for mono, stereo and multichannel`() {
        mapOf(
            PcmFormat.of(48000, PcmEncoding.PCM_16, 4) to 8,
            PcmFormat.of(48000, PcmEncoding.PCM_24_PACKED, 1) to 3,
            PcmFormat.of(48000, PcmEncoding.PCM_24_PACKED, 6) to 18,
            PcmFormat.of(48000, PcmEncoding.PCM_32, 2) to 8
        ).forEach { (format, align) -> assertEquals("$format", align, le(bytes(format, 0)).getShort(32).toInt()) }
    }

    @Test
    fun `every format round-trips through the parser at its own data offset`() {
        val formats = PcmEncoding.entries.flatMap { e -> listOf(1, 2, 3, 8).map { PcmFormat.of(88200, e, it) } }
        for (format in formats) {
            val dataLen = format.bytesPerFrame * 10L
            val parsed = WavRiffParser.parse((bytes(format, dataLen) + ByteArray(dataLen.toInt())).inputStream())!!
            assertEquals("$format", format, parsed.pcmFormat)
            assertEquals("$format", dataLen, parsed.dataSize)
            assertEquals("$format", WavHeaderWriter.headerSize(format).toLong(), parsed.dataOffset)
            assertEquals("$format", format.encoding.isFloat, parsed.isFloat)
            assertEquals("$format", format.bytesPerFrame, parsed.blockAlign)
        }
    }

    @Test
    fun `odd-length data counts the RIFF pad byte only when it's present`() {
        val mono24 = PcmFormat.of(48000, PcmEncoding.PCM_24_PACKED, 1)
        assertEquals(68L - 8 + 9 + 1, uint(bytes(mono24, 9, pad = true), 4))
        assertEquals(68L - 8 + 9, uint(bytes(mono24, 9, pad = false), 4))
        assertEquals(68L - 8 + 12, uint(bytes(mono24, 12, pad = true), 4)) // even: never padded
    }

    @Test
    fun `sizes above 2 GiB are written as unsigned 32-bit values`() {
        val stereo32 = PcmFormat.of(192000, PcmEncoding.PCM_32, 2)
        val dataLen = 3_500_000_000L - 3_500_000_000L % stereo32.bytesPerFrame
        val h = bytes(stereo32, dataLen)
        assertEquals(dataLen, uint(h, 64))
        assertEquals(dataLen + 68 - 8, uint(h, 4))
    }

    @Test
    fun `the 4 GiB ceiling is frame-aligned and never exceeded`() {
        for (format in listOf(
            PcmFormat.pcm16Mono(48000),
            PcmFormat.of(96000, PcmEncoding.PCM_24_PACKED, 1),
            PcmFormat.of(192000, PcmEncoding.PCM_FLOAT, 6)
        )) {
            val max = WavHeaderWriter.maxDataBytes(format)
            assertEquals(0L, max % format.bytesPerFrame)
            val riff = max + WavHeaderWriter.headerSize(format) - 8 + (max and 1)
            assert(riff <= 0xFFFFFFFFL) { "$format RIFF size $riff overflows" }
            bytes(format, max) // representable
            assertThrows(IllegalArgumentException::class.java) { bytes(format, max + format.bytesPerFrame) }
        }
        assertThrows(IllegalArgumentException::class.java) { bytes(PcmFormat.pcm16Mono(48000), -2) }
    }
}
