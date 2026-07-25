package com.example.wavrecorder

import android.content.Context
import android.net.Uri
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

data class AudioStats(
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
    val durationSeconds: Double,
    val sizeBytes: Long,
    val sampleCount: Long,
    val peakDbfs: Double?,
    val rmsDbfs: Double?,
    val clippedSamples: Long
) {
    val bitrateKbps: Double get() = sampleRate.toLong() * channels * bitsPerSample / 1000.0
}

/**
 * Parses the WAV header for its declared format, then scans every 16-bit
 * PCM sample once to get peak level, RMS level and clipped-sample count.
 * Meant to run off the main thread: reading + scanning is O(file size).
 */
object AudioStatsReader {

    private const val FULL_SCALE = 32768.0 // magnitude of a full-scale 16-bit sample

    fun read(context: Context, uri: Uri): AudioStats? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val header = ByteArray(44)
                var totalRead = 0
                while (totalRead < 44) {
                    val n = input.read(header, totalRead, 44 - totalRead)
                    if (n < 0) break
                    totalRead += n
                }
                if (totalRead < 44) return null

                val h = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                val channels = h.getShort(22).toInt()
                val sampleRate = h.getInt(24)
                val bitsPerSample = h.getShort(34).toInt()
                val dataSize = h.getInt(40)
                val byteRate = h.getInt(28)
                val duration = if (byteRate > 0) dataSize.toDouble() / byteRate else 0.0

                var peak = 0
                var sumSquares = 0.0
                var sampleCount = 0L
                var clipped = 0L

                if (bitsPerSample == 16) {
                    val buffer = ByteArray(8192)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        var i = 0
                        while (i + 1 < n) {
                            val sample = ((buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)).toShort().toInt()
                            val magnitude = abs(sample)
                            if (magnitude > peak) peak = magnitude
                            sumSquares += sample.toDouble() * sample.toDouble()
                            sampleCount++
                            if (magnitude >= 32767) clipped++
                            i += 2
                        }
                    }
                }

                val peakDbfs = if (sampleCount > 0 && peak > 0) 20 * log10(peak / FULL_SCALE) else null
                val rmsDbfs = if (sampleCount > 0) {
                    val rms = sqrt(sumSquares / sampleCount)
                    if (rms > 0) 20 * log10(rms / FULL_SCALE) else null
                } else null

                AudioStats(
                    sampleRate = sampleRate,
                    channels = channels,
                    bitsPerSample = bitsPerSample,
                    durationSeconds = duration,
                    sizeBytes = dataSize.toLong() + 44,
                    sampleCount = sampleCount,
                    peakDbfs = peakDbfs,
                    rmsDbfs = rmsDbfs,
                    clippedSamples = clipped
                )
            }
        } catch (e: Exception) {
            null
        }
    }
}
