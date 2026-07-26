package com.example.wavrecorder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavRiffParserTest {

    private val pcmSubformatGuid = byteArrayOf(
        0x01, 0x00, 0x00, 0x00,
        0x00, 0x00,
        0x10, 0x00,
        0x80.toByte(), 0x00, 0x00, 0xAA.toByte(), 0x00, 0x38, 0x9B.toByte(), 0x71
    )
    private val ieeeFloatSubformatGuid = byteArrayOf(
        0x03, 0x00, 0x00, 0x00,
        0x00, 0x00,
        0x10, 0x00,
        0x80.toByte(), 0x00, 0x00, 0xAA.toByte(), 0x00, 0x38, 0x9B.toByte(), 0x71
    )

    /** Builds a full 40-byte WAVEFORMATEXTENSIBLE "fmt " body (format code 0xFFFE). */
    private fun extensibleFmtBody(
        sampleRate: Int = 48000,
        channels: Int = 2,
        bitsPerSample: Int = 24,
        subFormatGuid: ByteArray = pcmSubformatGuid
    ): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val buf = ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(0xFFFE.toShort())
        buf.putShort(channels.toShort())
        buf.putInt(sampleRate)
        buf.putInt(byteRate)
        buf.putShort(blockAlign.toShort())
        buf.putShort(bitsPerSample.toShort())
        buf.putShort(22) // cbSize: validBitsPerSample(2) + channelMask(4) + subFormat GUID(16)
        buf.putShort(bitsPerSample.toShort()) // validBitsPerSample
        buf.putInt(0) // channelMask
        buf.put(subFormatGuid)
        return buf.array()
    }

    /** Builds one RIFF chunk: 4-byte id, 4-byte little-endian size, body, and (if the body is an
     * odd number of bytes) the single zero pad byte RIFF requires to keep chunks word-aligned. */
    private fun chunk(id: String, body: ByteArray): ByteArray {
        val header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        header.put(id.toByteArray(Charsets.US_ASCII))
        header.putInt(body.size)
        val pad = if (body.size % 2 == 1) byteArrayOf(0) else ByteArray(0)
        return header.array() + body + pad
    }

    private fun fmtBody(
        sampleRate: Int = 44100,
        channels: Int = 1,
        bitsPerSample: Int = 16,
        audioFormat: Int = 1,
        extraTrailingBytes: Int = 0
    ): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val buf = ByteBuffer.allocate(16 + extraTrailingBytes).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(audioFormat.toShort())
        buf.putShort(channels.toShort())
        buf.putInt(sampleRate)
        buf.putInt(byteRate)
        buf.putShort(blockAlign.toShort())
        buf.putShort(bitsPerSample.toShort())
        return buf.array()
    }

    private fun riffWave(vararg chunks: ByteArray): ByteArray {
        val body = chunks.fold(ByteArray(0)) { acc, c -> acc + c }
        val header = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(body.size + 4) // + "WAVE"
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        return header.array() + body
    }

    private fun parse(bytes: ByteArray) = WavRiffParser.parse(ByteArrayInputStream(bytes))

    @Test
    fun `parses a minimal well-formed file`() {
        val audio = ByteArray(10) { it.toByte() }
        val bytes = riffWave(chunk("fmt ", fmtBody()), chunk("data", audio))

        val format = parse(bytes)

        assertEquals(1, format?.channels)
        assertEquals(44100, format?.sampleRate)
        assertEquals(16, format?.bitsPerSample)
        assertEquals(10L, format?.dataSize)
    }

    @Test
    fun `skips a JUNK chunk before fmt`() {
        val junk = ByteArray(6) { 0x11 } // even size, no padding needed
        val audio = ByteArray(4)
        val bytes = riffWave(chunk("JUNK", junk), chunk("fmt ", fmtBody()), chunk("data", audio))

        val format = parse(bytes)

        assertEquals(44100, format?.sampleRate)
        assertEquals(4L, format?.dataSize)
    }

    @Test
    fun `skips an odd-sized LIST chunk (with pad byte) between fmt and data`() {
        val list = ByteArray(5) { 0x22 } // odd size -> one pad byte follows it
        val audio = ByteArray(8) { it.toByte() }
        val bytes = riffWave(chunk("fmt ", fmtBody()), chunk("LIST", list), chunk("data", audio))

        val format = parse(bytes)

        assertEquals(8L, format?.dataSize)
        // If the pad byte weren't skipped, the parser would try to read the next chunk header
        // one byte early and misparse everything after it (or fail outright).
    }

    @Test
    fun `handles an extended fmt chunk larger than 16 bytes`() {
        val audio = ByteArray(2)
        val bytes = riffWave(
            chunk("fmt ", fmtBody(sampleRate = 48000, channels = 2, extraTrailingBytes = 2)),
            chunk("data", audio)
        )

        val format = parse(bytes)

        assertEquals(48000, format?.sampleRate)
        assertEquals(2, format?.channels)
    }

    @Test
    fun `rejects a non-PCM format code`() {
        val bytes = riffWave(chunk("fmt ", fmtBody(audioFormat = 3)), chunk("data", ByteArray(4)))

        assertNull(parse(bytes))
    }

    @Test
    fun `rejects wrong RIFF id`() {
        val bytes = riffWave(chunk("fmt ", fmtBody()), chunk("data", ByteArray(4)))
        bytes[0] = 'X'.code.toByte()

        assertNull(parse(bytes))
    }

    @Test
    fun `rejects wrong WAVE id`() {
        val bytes = riffWave(chunk("fmt ", fmtBody()), chunk("data", ByteArray(4)))
        bytes[8] = 'X'.code.toByte()

        assertNull(parse(bytes))
    }

    @Test
    fun `rejects a data chunk that appears before fmt`() {
        val bytes = riffWave(chunk("data", ByteArray(4)), chunk("fmt ", fmtBody()))

        assertNull(parse(bytes))
    }

    @Test
    fun `rejects a truncated fmt chunk`() {
        val bytes = riffWave(chunk("fmt ", ByteArray(10))) // declares a valid-looking chunk but < 16 bytes

        assertNull(parse(bytes))
    }

    @Test
    fun `rejects a file truncated mid-header`() {
        val full = riffWave(chunk("fmt ", fmtBody()), chunk("data", ByteArray(4)))

        assertNull(parse(full.copyOf(20)))
    }

    @Test
    fun `rejects a file with no data chunk at all`() {
        val bytes = riffWave(chunk("fmt ", fmtBody()))

        assertNull(parse(bytes))
    }

    @Test
    fun `accepts WAVE_FORMAT_EXTENSIBLE with the PCM subformat GUID`() {
        val audio = ByteArray(6)
        val bytes = riffWave(chunk("fmt ", extensibleFmtBody()), chunk("data", audio))

        val format = parse(bytes)

        assertEquals(48000, format?.sampleRate)
        assertEquals(2, format?.channels)
        assertEquals(24, format?.bitsPerSample)
        assertEquals(6L, format?.dataSize)
    }

    @Test
    fun `rejects WAVE_FORMAT_EXTENSIBLE with a non-PCM subformat GUID (IEEE float)`() {
        val bytes = riffWave(
            chunk("fmt ", extensibleFmtBody(subFormatGuid = ieeeFloatSubformatGuid)),
            chunk("data", ByteArray(4))
        )

        assertNull(parse(bytes))
    }

    @Test
    fun `rejects a WAVE_FORMAT_EXTENSIBLE fmt chunk truncated before the subformat GUID`() {
        val truncated = extensibleFmtBody().copyOf(24) // cuts off right before the 16-byte GUID
        val bytes = riffWave(chunk("fmt ", truncated), chunk("data", ByteArray(4)))

        assertNull(parse(bytes))
    }

    @Test
    fun `countAvailableBytes reports the actual byte count when the stream is shorter than declared`() {
        val input = ByteArrayInputStream(byteArrayOf(1, 2, 3))

        val available = WavRiffParser.countAvailableBytes(input, declaredSize = 10)

        assertEquals(3L, available)
    }

    @Test
    fun `countAvailableBytes returns the full declared size when enough data is present`() {
        val input = ByteArrayInputStream(ByteArray(20))

        val available = WavRiffParser.countAvailableBytes(input, declaredSize = 10)

        assertEquals(10L, available)
    }

    @Test
    fun `stream is positioned at the first audio byte on success`() {
        val audio = byteArrayOf(1, 2, 3, 4)
        val bytes = riffWave(chunk("fmt ", fmtBody()), chunk("data", audio))
        val input = ByteArrayInputStream(bytes)

        val format = WavRiffParser.parse(input)

        assertEquals(4L, format?.dataSize)
        assertEquals(1, input.read())
        assertEquals(2, input.read())
    }
}
