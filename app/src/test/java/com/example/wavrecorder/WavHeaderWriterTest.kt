package com.example.wavrecorder

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavHeaderWriterTest {

    @Test
    fun `placeholder is 44 zero bytes`() {
        val placeholder = WavHeaderWriter.placeholder()
        assertEquals(44, placeholder.remaining())
        val bytes = ByteArray(44)
        placeholder.duplicate().get(bytes)
        assertEquals(0, bytes.sum())
    }

    @Test
    fun `build writes a spec-correct 44-byte PCM header`() {
        val header = WavHeaderWriter.build(sampleRate = 48000, channels = 1, bitsPerSample = 16, audioDataLen = 1000)
        val bytes = ByteArray(header.remaining())
        header.duplicate().get(bytes)
        assertEquals(44, bytes.size)

        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
        assertEquals(1036, buf.getInt(4)) // 1000 + 36
        assertEquals("WAVE", String(bytes, 8, 4, Charsets.US_ASCII))
        assertEquals("fmt ", String(bytes, 12, 4, Charsets.US_ASCII))
        assertEquals(16, buf.getInt(16))
        assertEquals(1, buf.getShort(20).toInt()) // PCM
        assertEquals(1, buf.getShort(22).toInt()) // channels
        assertEquals(48000, buf.getInt(24))
        assertEquals(48000 * 1 * 16 / 8, buf.getInt(28)) // byte rate
        assertEquals(2, buf.getShort(32).toInt()) // block align
        assertEquals(16, buf.getShort(34).toInt()) // bits per sample
        assertEquals("data", String(bytes, 36, 4, Charsets.US_ASCII))
        assertEquals(1000, buf.getInt(40))
    }

    @Test
    fun `round trips through WavRiffParser`() {
        val header = WavHeaderWriter.build(sampleRate = 44100, channels = 2, bitsPerSample = 16, audioDataLen = 200)
        val bytes = ByteArray(header.remaining())
        header.duplicate().get(bytes)
        val audio = ByteArray(200) { it.toByte() }

        val format = WavRiffParser.parse((bytes + audio).inputStream())

        assertEquals(2, format?.channels)
        assertEquals(44100, format?.sampleRate)
        assertEquals(16, format?.bitsPerSample)
        assertEquals(200L, format?.dataSize)
    }
}
