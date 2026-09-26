package com.example.wavrecorder

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Builds the WAV headers this app writes. Kept separate from [WavRecorder] so every byte layout
 * can be unit tested (and read back with [WavRiffParser]) without touching Android APIs.
 *
 * A header is always: RIFF/WAVE, a "fmt " chunk, an optional "fact" chunk (float formats, where
 * the specification requires one), then the "data" chunk header -- nothing after it but audio.
 * Its size depends on the format (see [headerSize]), so the audio does not always start at byte
 * 44; every reader in this app locates it through [WavRiffParser] or [headerSize] instead.
 *
 * RIFF sizes are unsigned 32-bit: [maxDataBytes] is the largest frame-aligned audio length a
 * header can describe, and [build] refuses anything larger rather than wrapping around.
 */
object WavHeaderWriter {
    /** Size of the classic 16-bit PCM header ([build] with sampleRate/channels/bits, and
     * [WavLayout.PCM_BASIC]) -- the only layout earlier versions of this app ever wrote. */
    const val HEADER_SIZE = 44

    private const val WAVE_FORMAT_PCM = 1
    private const val WAVE_FORMAT_IEEE_FLOAT = 3
    private const val WAVE_FORMAT_EXTENSIBLE = 0xFFFE
    private const val MAX_UINT32 = 0xFFFFFFFFL

    /** KSDATAFORMAT_SUBTYPE_PCM / _IEEE_FLOAT as laid out on disk (the first three GUID fields
     * little-endian, the trailing eight bytes literal). Shared with [WavRiffParser]. */
    internal val SUBFORMAT_PCM = subformatGuid(0x01)
    internal val SUBFORMAT_IEEE_FLOAT = subformatGuid(0x03)

    private fun subformatGuid(first: Int) = byteArrayOf(
        first.toByte(), 0x00, 0x00, 0x00,
        0x00, 0x00,
        0x10, 0x00,
        0x80.toByte(), 0x00, 0x00, 0xAA.toByte(), 0x00, 0x38, 0x9B.toByte(), 0x71
    )

    /**
     * The classic 44-byte integer-PCM header, byte-for-byte what every earlier version of this app
     * wrote. Kept for callers that only know those three values; new recordings go through the
     * [PcmFormat] overload, which produces exactly these bytes for 16-bit mono/stereo.
     */
    fun build(sampleRate: Int, channels: Int, bitsPerSample: Int, audioDataLen: Long): ByteBuffer {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val totalDataLen = audioDataLen + 36

        val header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(totalDataLen.toInt())
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16) // PCM sub-chunk size
        header.putShort(1) // audio format = PCM
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(blockAlign.toShort())
        header.putShort(bitsPerSample.toShort())
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(audioDataLen.toInt())
        header.flip()
        return header
    }

    private fun fmtBodySize(layout: WavLayout): Int = when (layout) {
        WavLayout.PCM_BASIC -> 16
        WavLayout.FLOAT_BASIC -> 18 // + cbSize (0)
        WavLayout.EXTENSIBLE_PCM, WavLayout.EXTENSIBLE_FLOAT -> 40 // + cbSize (22) and its extension
    }

    private fun hasFactChunk(layout: WavLayout) = layout == WavLayout.FLOAT_BASIC || layout == WavLayout.EXTENSIBLE_FLOAT

    /** Bytes before the first audio byte for [format]: 44, 58, 68 or 80 depending on layout. */
    fun headerSize(format: PcmFormat): Int {
        val layout = format.wavLayout
        return 12 + 8 + fmtBodySize(layout) + (if (hasFactChunk(layout)) 12 else 0) + 8
    }

    /**
     * The largest audio length (whole frames) a [format] header can describe: the RIFF size
     * (everything after its own 8-byte header, including a pad byte after odd-length data) must
     * fit in an unsigned 32-bit field. One byte is always reserved for that pad.
     */
    fun maxDataBytes(format: PcmFormat): Long =
        format.alignToFrame(MAX_UINT32 - (headerSize(format) - 8) - 1)

    /**
     * The header for [audioDataLen] bytes of [format] audio. [includePadByte] says whether the
     * file actually carries the zero pad byte RIFF requires after odd-length data (WavRecorder
     * always writes it when finalizing; crash recovery only counts it if it's really there).
     */
    fun build(format: PcmFormat, audioDataLen: Long, includePadByte: Boolean = true): ByteBuffer {
        require(audioDataLen >= 0 && audioDataLen <= maxDataBytes(format)) {
            "$audioDataLen audio bytes can't be described by a WAV header (max ${maxDataBytes(format)})"
        }
        val layout = format.wavLayout
        val size = headerSize(format)
        val pad = if (includePadByte && audioDataLen % 2 == 1L) 1 else 0
        val riffSize = size - 8 + audioDataLen + pad

        val header = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(riffSize.toUInt32())
        header.put("WAVE".toByteArray(Charsets.US_ASCII))

        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(fmtBodySize(layout))
        header.putShort(
            when (layout) {
                WavLayout.PCM_BASIC -> WAVE_FORMAT_PCM
                WavLayout.FLOAT_BASIC -> WAVE_FORMAT_IEEE_FLOAT
                WavLayout.EXTENSIBLE_PCM, WavLayout.EXTENSIBLE_FLOAT -> WAVE_FORMAT_EXTENSIBLE
            }.toShort()
        )
        header.putShort(format.channelCount.toShort())
        header.putInt(format.sampleRate)
        header.putInt(format.byteRate.toUInt32())
        header.putShort(format.bytesPerFrame.toShort()) // block align
        header.putShort(format.containerBitsPerSample.toShort())
        when (layout) {
            WavLayout.PCM_BASIC -> Unit
            WavLayout.FLOAT_BASIC -> header.putShort(0) // cbSize
            WavLayout.EXTENSIBLE_PCM, WavLayout.EXTENSIBLE_FLOAT -> {
                header.putShort(22) // cbSize
                header.putShort(format.validBitsPerSample.toShort())
                header.putInt(format.wavSpeakerMask)
                header.put(if (layout == WavLayout.EXTENSIBLE_FLOAT) SUBFORMAT_IEEE_FLOAT else SUBFORMAT_PCM)
            }
        }

        if (hasFactChunk(layout)) {
            header.put("fact".toByteArray(Charsets.US_ASCII))
            header.putInt(4)
            header.putInt(format.framesIn(audioDataLen).toUInt32()) // sample frames per channel
        }

        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(audioDataLen.toUInt32())
        check(header.position() == size)
        header.flip()
        return header
    }

    private fun Long.toUInt32(): Int {
        require(this in 0..MAX_UINT32) { "$this doesn't fit an unsigned 32-bit WAV field" }
        return toInt() // two's-complement bit pattern == the unsigned value, as putInt writes it
    }
}
