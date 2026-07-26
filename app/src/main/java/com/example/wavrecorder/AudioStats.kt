package com.example.wavrecorder

import android.content.Context
import android.net.Uri
import java.io.InputStream
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

/** Result of scanning a stream of 16-bit PCM samples; see [scanPcm16Samples]. */
internal data class Pcm16ScanResult(
    val peak: Int,
    val sumSquares: Double,
    val sampleCount: Long,
    val clippedSamples: Long,
    /** Bytes actually read, which can be less than the requested data size if the stream (file)
     * is truncated/damaged and ran out before the declared "data" chunk size was reached. */
    val bytesRead: Long
)

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
                val format = WavRiffParser.parse(input) ?: return null

                // Showing stats always needs a full scan of the audio for real peak/RMS/clip
                // values, so this can't avoid reading the payload the way WavFileInfo does for
                // the library list. But a file that's *obviously* truncated by a cheap file-size
                // check can be rejected without even starting that scan.
                val actualFileSize = WavFileInfo.queryActualSize(context, uri)
                if (WavRiffParser.isDataComplete(actualFileSize, format) == false) return null

                val scan: Pcm16ScanResult?
                val dataBytesAvailable: Long
                if (format.bitsPerSample == 16) {
                    val result = scanPcm16Samples(input, format.dataSize)
                    scan = result
                    dataBytesAvailable = result.bytesRead
                } else {
                    scan = null
                    dataBytesAvailable = WavRiffParser.countAvailableBytes(input, format.dataSize)
                }
                // The header declared more audio than the file actually contains: it's damaged
                // or was cut off mid-write. Duration/stats derived from the declared size would
                // be misleading, so treat the whole file as unreadable rather than report them.
                if (dataBytesAvailable < format.dataSize) return null

                val peakDbfs = if (scan != null && scan.sampleCount > 0 && scan.peak > 0) {
                    20 * log10(scan.peak / FULL_SCALE)
                } else null
                val rmsDbfs = if (scan != null && scan.sampleCount > 0) {
                    val rms = sqrt(scan.sumSquares / scan.sampleCount)
                    if (rms > 0) 20 * log10(rms / FULL_SCALE) else null
                } else null

                val duration = if (format.byteRate > 0) format.dataSize.toDouble() / format.byteRate else 0.0
                val sizeBytes = actualFileSize ?: (format.dataOffset + format.dataSize)

                AudioStats(
                    sampleRate = format.sampleRate,
                    channels = format.channels,
                    bitsPerSample = format.bitsPerSample,
                    durationSeconds = duration,
                    sizeBytes = sizeBytes,
                    sampleCount = scan?.sampleCount ?: 0L,
                    peakDbfs = peakDbfs,
                    rmsDbfs = rmsDbfs,
                    clippedSamples = scan?.clippedSamples ?: 0L
                )
            }
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Scans exactly [dataSize] bytes of 16-bit little-endian PCM audio from [input]. Each
 * [InputStream.read] call can return an arbitrary number of bytes, including an odd count, so a
 * sample can straddle two reads; the trailing unpaired byte from one read is carried over
 * ([leftover]) and combined with the first byte of the next read instead of being discarded,
 * which would otherwise desync every following sample boundary in that segment.
 */
internal fun scanPcm16Samples(input: InputStream, dataSize: Long): Pcm16ScanResult {
    var peak = 0
    var sumSquares = 0.0
    var sampleCount = 0L
    var clipped = 0L
    var leftover: Byte? = null

    fun consumeSample(low: Byte, high: Byte) {
        val sample = ((high.toInt() shl 8) or (low.toInt() and 0xFF)).toShort().toInt()
        val magnitude = abs(sample)
        if (magnitude > peak) peak = magnitude
        sumSquares += sample.toDouble() * sample.toDouble()
        sampleCount++
        if (magnitude >= 32767) clipped++
    }

    var remaining = dataSize
    val buffer = ByteArray(8192)
    while (remaining > 0) {
        val toRead = minOf(buffer.size.toLong(), remaining).toInt()
        val n = input.read(buffer, 0, toRead)
        if (n < 0) break
        remaining -= n

        var i = 0
        val pending = leftover
        if (pending != null && n > 0) {
            consumeSample(low = pending, high = buffer[0])
            leftover = null
            i = 1
        }
        while (i + 1 < n) {
            consumeSample(low = buffer[i], high = buffer[i + 1])
            i += 2
        }
        if (i < n) {
            leftover = buffer[i]
        }
    }

    return Pcm16ScanResult(peak, sumSquares, sampleCount, clipped, bytesRead = dataSize - remaining)
}
