package com.example.wavrecorder

import android.content.Context
import android.net.Uri
import java.io.InputStream
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
    val clippedSamples: Long,
    /** True for IEEE-float samples. */
    val isFloat: Boolean = false
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
 * Parses the WAV header for its declared format, then scans every sample once (any encoding
 * [WavRiffParser.Format.encoding] recognizes: 16/24/32-bit integer or 32-bit float, any number of
 * channels) to get peak level, RMS level and clipped-sample count.
 * Meant to run off the main thread: reading + scanning is O(file size).
 */
object AudioStatsReader {

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

                val encoding = format.encoding
                val scan: PcmScanResult?
                val dataBytesAvailable: Long
                if (encoding != null) {
                    val result = scanPcmSamples(input, format.dataSize, encoding)
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

                val fullScale = encoding?.let { PcmSamples.fullScale(it) } ?: 1.0
                val peakDbfs = if (scan != null && scan.sampleCount > 0 && scan.peak > 0) {
                    20 * log10(scan.peak / fullScale)
                } else null
                val rmsDbfs = if (scan != null && scan.sampleCount > 0) {
                    val rms = sqrt(scan.sumSquares / scan.sampleCount)
                    if (rms > 0) 20 * log10(rms / fullScale) else null
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
                    clippedSamples = scan?.clippedSamples ?: 0L,
                    isFloat = format.isFloat
                )
            }
        } catch (e: InterruptedException) {
            // Never swallowed as an ordinary read failure: a caller running this inside
            // kotlinx.coroutines.runInterruptible relies on this propagating out so it can convert
            // it into a genuine coroutine cancellation, rather than a stray null this class would
            // otherwise report indistinguishably from "the file was actually unreadable".
            throw e
        } catch (e: Exception) {
            null
        }
    }
}

/** 16-bit shorthand for [scanPcmSamples] (the only format earlier versions recorded). */
internal fun scanPcm16Samples(input: InputStream, dataSize: Long): Pcm16ScanResult {
    val result = scanPcmSamples(input, dataSize, PcmEncoding.PCM_16)
    return Pcm16ScanResult(
        peak = result.peak.toInt(),
        sumSquares = result.sumSquares,
        sampleCount = result.sampleCount,
        clippedSamples = result.clippedSamples,
        bytesRead = result.bytesRead
    )
}
