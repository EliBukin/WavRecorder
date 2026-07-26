package com.example.wavrecorder

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Builds the canonical 44-byte PCM WAV header this app writes for every file it produces:
 * RIFF/WAVE, a 16-byte "fmt " chunk, then a "data" chunk with no extra chunks in between.
 * Kept separate from [WavRecorder] so the exact byte layout can be unit tested (and read back
 * with [WavRiffParser]) without touching Android APIs.
 */
object WavHeaderWriter {
    const val HEADER_SIZE = 44

    fun placeholder(): ByteBuffer = ByteBuffer.allocate(HEADER_SIZE)

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
}
