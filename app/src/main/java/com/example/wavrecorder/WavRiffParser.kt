package com.example.wavrecorder

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Parses the RIFF/WAVE chunk structure of a WAV file read sequentially off an [InputStream].
 * Real-world WAV files (this app's library scans whatever a user drops into the selected
 * folder, not just files this app wrote) may carry a "JUNK", "LIST", "fact", or other chunk
 * before "fmt " or "data", and "fmt " itself may be extended beyond 16 bytes. Fixed-offset
 * reads silently misparse those. This walks chunks properly instead.
 *
 * The stream is only ever read forward (SAF streams aren't guaranteed seekable), so on a
 * successful parse the stream is left positioned at the first byte of PCM audio data, ready
 * for the caller to read exactly [Format.dataSize] bytes from it.
 */
object WavRiffParser {

    data class Format(
        val channels: Int,
        val sampleRate: Int,
        val bitsPerSample: Int,
        val byteRate: Int,
        val dataSize: Long,
        /** Absolute byte offset from the start of the file where the "data" chunk's audio
         * payload begins. Lets a caller verify the file is complete (`fileSize >= dataOffset +
         * dataSize`) using only a cheaply-known file size, without reading the audio itself. */
        val dataOffset: Long
    )

    private const val PCM = 1
    private const val WAVE_FORMAT_EXTENSIBLE = 0xFFFE
    private const val MAX_FMT_CHUNK_SIZE = 4096L // real "fmt " chunks are 16-40 bytes; a larger
    // declared size is either malformed or hostile input, so bail out rather than allocate it.

    // A WAVEFORMATEXTENSIBLE fmt chunk is the 16-byte base fields, a 2-byte cbSize, a 2-byte
    // validBitsPerSample/reserved, a 4-byte channel mask, then a 16-byte subformat GUID — 40
    // bytes total. Anything shorter is missing the GUID that actually says what the data is.
    private const val MIN_EXTENSIBLE_FMT_CHUNK_SIZE = 40
    private const val EXTENSIBLE_SUBFORMAT_OFFSET = 24

    // KSDATAFORMAT_SUBTYPE_PCM ({00000001-0000-0010-8000-00AA00389B71}) as it's laid out on disk:
    // the first three GUID fields little-endian, the trailing 8-byte data4 as literal bytes.
    private val PCM_SUBFORMAT_GUID = byteArrayOf(
        0x01, 0x00, 0x00, 0x00,
        0x00, 0x00,
        0x10, 0x00,
        0x80.toByte(), 0x00, 0x00, 0xAA.toByte(), 0x00, 0x38, 0x9B.toByte(), 0x71
    )

    fun parse(input: InputStream): Format? {
        val riffHeader = ByteArray(12)
        if (!readFully(input, riffHeader)) return null
        val riffId = String(riffHeader, 0, 4, Charsets.US_ASCII)
        val waveId = String(riffHeader, 8, 4, Charsets.US_ASCII)
        if (riffId != "RIFF" || waveId != "WAVE") return null
        var bytesConsumed = riffHeader.size.toLong()

        var channels: Int? = null
        var sampleRate: Int? = null
        var bitsPerSample: Int? = null
        var byteRate: Int? = null

        val chunkHeader = ByteArray(8)
        while (true) {
            if (!readFully(input, chunkHeader)) return null // ran out of chunks before "data"
            bytesConsumed += chunkHeader.size
            val chunkId = String(chunkHeader, 0, 4, Charsets.US_ASCII)
            val chunkSize = ByteBuffer.wrap(chunkHeader, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
                .toLong() and 0xFFFFFFFFL

            when (chunkId) {
                "fmt " -> {
                    if (chunkSize < 16 || chunkSize > MAX_FMT_CHUNK_SIZE) return null
                    val body = ByteArray(chunkSize.toInt())
                    if (!readFully(input, body)) return null
                    val fmt = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
                    val audioFormat = fmt.getShort(0).toInt() and 0xFFFF
                    when (audioFormat) {
                        PCM -> Unit
                        WAVE_FORMAT_EXTENSIBLE -> {
                            // The format code alone doesn't say what's actually in the data chunk
                            // for an extensible fmt -- that's in the subformat GUID. Accepting
                            // 0xFFFE without checking it would scan e.g. extensible IEEE-float
                            // data as if it were PCM.
                            if (chunkSize < MIN_EXTENSIBLE_FMT_CHUNK_SIZE) return null
                            val subFormat = body.copyOfRange(
                                EXTENSIBLE_SUBFORMAT_OFFSET,
                                EXTENSIBLE_SUBFORMAT_OFFSET + PCM_SUBFORMAT_GUID.size
                            )
                            if (!subFormat.contentEquals(PCM_SUBFORMAT_GUID)) return null
                        }
                        else -> return null // e.g. IEEE float, A-law/mu-law, ADPCM: not supported
                    }
                    channels = fmt.getShort(2).toInt() and 0xFFFF
                    sampleRate = fmt.getInt(4)
                    byteRate = fmt.getInt(8)
                    bitsPerSample = fmt.getShort(14).toInt() and 0xFFFF
                    bytesConsumed += chunkSize
                    if (chunkSize % 2L == 1L) {
                        if (!skipFully(input, 1)) return null
                        bytesConsumed += 1
                    }
                }
                "data" -> {
                    val c = channels ?: return null // "data" before "fmt ": malformed
                    val sr = sampleRate ?: return null
                    val bps = bitsPerSample ?: return null
                    val br = byteRate ?: return null
                    if (c <= 0 || sr <= 0 || bps <= 0) return null
                    return Format(c, sr, bps, br, dataSize = chunkSize, dataOffset = bytesConsumed)
                }
                else -> {
                    if (!skipFully(input, chunkSize)) return null
                    bytesConsumed += chunkSize
                    if (chunkSize % 2L == 1L) {
                        if (!skipFully(input, 1)) return null
                        bytesConsumed += 1
                    }
                }
            }
        }
    }

    /**
     * Cheap (no audio bytes touched) truncation check using an already-known file size. Returns
     * null when [actualFileSize] is unavailable, meaning the only way to verify the file is
     * complete is to actually stream through the declared audio payload — see
     * [countAvailableBytes] for that fallback.
     */
    fun isDataComplete(actualFileSize: Long?, format: Format): Boolean? =
        actualFileSize?.let { it >= format.dataOffset + format.dataSize }

    /**
     * Consumes up to [declaredSize] bytes of [input] and returns how many were actually
     * available before the stream ran out. A damaged or truncated file can declare a "data"
     * chunk size larger than the bytes that actually follow it; callers use this to detect that
     * instead of silently treating the declared size as ground truth.
     */
    fun countAvailableBytes(input: InputStream, declaredSize: Long): Long {
        var remaining = declaredSize
        val scratch = ByteArray(8192)
        while (remaining > 0) {
            val n = input.read(scratch, 0, minOf(remaining, scratch.size.toLong()).toInt())
            if (n < 0) break
            remaining -= n
        }
        return declaredSize - remaining
    }

    private fun readFully(input: InputStream, buffer: ByteArray): Boolean {
        var total = 0
        while (total < buffer.size) {
            val n = input.read(buffer, total, buffer.size - total)
            if (n < 0) return false
            total += n
        }
        return true
    }

    private fun skipFully(input: InputStream, count: Long): Boolean {
        var remaining = count
        val scratch = ByteArray(8192)
        while (remaining > 0) {
            val n = input.read(scratch, 0, minOf(remaining, scratch.size.toLong()).toInt())
            if (n < 0) return false
            remaining -= n
        }
        return true
    }
}
