package com.example.wavrecorder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The parser never assumes audio starts at byte 44: extensible/float headers, "fact" chunks and
 * unknown chunks anywhere before "data" are all located by walking the chunk structure. */
class WavRiffParserFormatTest {

    private fun chunk(id: String, body: ByteArray): ByteArray {
        val header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        header.put(id.toByteArray(Charsets.US_ASCII)).putInt(body.size)
        return header.array() + body + (if (body.size % 2 == 1) byteArrayOf(0) else ByteArray(0))
    }

    /** Splits one of our own headers into its fmt/fact bodies, to rebuild files around them. */
    private fun fmtAndFact(format: PcmFormat): Pair<ByteArray, ByteArray?> {
        val h = WavHeaderWriter.build(format, 0).let { b -> ByteArray(b.remaining()).also { b.get(it) } }
        val fmtSize = ByteBuffer.wrap(h, 16, 4).order(ByteOrder.LITTLE_ENDIAN).int
        val fmt = h.copyOfRange(20, 20 + fmtSize)
        val factAt = 20 + fmtSize
        val fact = if (String(h, factAt, 4, Charsets.US_ASCII) == "fact") h.copyOfRange(factAt + 8, factAt + 12) else null
        return fmt to fact
    }

    private fun riff(vararg chunks: ByteArray): ByteArray {
        val body = chunks.fold("WAVE".toByteArray(Charsets.US_ASCII)) { acc, c -> acc + c }
        val header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(body.size)
        return header.array() + body
    }

    @Test
    fun `unknown chunks before and between fmt, fact and data are skipped`() {
        val format = PcmFormat.of(48000, PcmEncoding.PCM_FLOAT, 2)
        val (fmt, fact) = fmtAndFact(format)
        val audio = ByteArray(80) { it.toByte() }
        val file = riff(
            chunk("JUNK", ByteArray(28)),
            chunk("fmt ", fmt),
            chunk("LIST", ByteArray(13)), // odd, padded
            chunk("fact", fact!!),
            chunk("bext", ByteArray(602)),
            chunk("data", audio)
        )
        val stream = file.inputStream()
        val parsed = WavRiffParser.parse(stream)!!
        assertEquals(format, parsed.pcmFormat)
        assertEquals(80L, parsed.dataSize)
        val expectedOffset = 12L + (8 + 28) + (8 + fmt.size) + (8 + 14) + (8 + 4) + (8 + 602) + 8
        assertEquals(expectedOffset, parsed.dataOffset)
        assert(parsed.dataOffset != 44L)
        assertEquals(audio.toList(), stream.readBytes().toList())
    }

    @Test
    fun `an extensible 24-bit multichannel header parses to the exact format`() {
        val format = PcmFormat.of(96000, PcmEncoding.PCM_24_PACKED, 6)
        val (fmt, _) = fmtAndFact(format)
        val parsed = WavRiffParser.parse(riff(chunk("fmt ", fmt), chunk("data", ByteArray(18))).inputStream())!!
        assertEquals(PcmEncoding.PCM_24_PACKED, parsed.encoding)
        assertEquals(6, parsed.channels)
        assertEquals(18, parsed.blockAlign)
        assertEquals(24, parsed.validBitsPerSample)
    }

    @Test
    fun `formats this app can't decode still parse, but report no encoding`() {
        // 20 valid bits in a 24-bit container
        val body = ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(0xFFFE.toShort()).putShort(2).putInt(48000).putInt(288000).putShort(6).putShort(24)
            .putShort(22).putShort(20).putInt(3).put(WavHeaderWriter.SUBFORMAT_PCM).array()
        val parsed = WavRiffParser.parse(riff(chunk("fmt ", body), chunk("data", ByteArray(6))).inputStream())!!
        assertEquals(20, parsed.validBitsPerSample)
        assertNull(parsed.encoding)
        assertEquals(288000, parsed.byteRate) // duration still computable
    }

    @Test
    fun `a block alignment that contradicts the sample size means no decodable encoding`() {
        val body = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(1).putShort(2).putInt(48000).putInt(192000).putShort(3).putShort(16).array()
        val parsed = WavRiffParser.parse(riff(chunk("fmt ", body), chunk("data", ByteArray(4))).inputStream())!!
        assertNull(parsed.encoding)
    }
}
