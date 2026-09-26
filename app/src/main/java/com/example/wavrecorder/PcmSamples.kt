package com.example.wavrecorder

import java.io.InputStream
import kotlin.math.abs
import kotlin.math.log10

/**
 * Decodes little-endian PCM samples of every [PcmEncoding] this app records, for the live level
 * meter, the microphone test and post-recording statistics -- one implementation, so the three
 * can never disagree about what a sample means. Pure JVM; no Android APIs.
 *
 * Samples are interleaved (every channel of frame 0, then frame 1, ...); a level or statistic is
 * taken over every channel's samples together.
 */
internal object PcmSamples {

    /** Magnitude of a full-scale sample, in the encoding's own units: 2^(bits-1) for integers,
     * 1.0 for float. Used for dBFS, matching how statistics were always computed for 16-bit. */
    fun fullScale(encoding: PcmEncoding): Double = when (encoding) {
        PcmEncoding.PCM_16 -> 32768.0
        PcmEncoding.PCM_24_PACKED -> 8388608.0
        PcmEncoding.PCM_32 -> 2147483648.0
        PcmEncoding.PCM_FLOAT -> 1.0
    }

    /** The largest positive sample value -- a sample at or beyond this magnitude is clipped. */
    fun maxPositive(encoding: PcmEncoding): Double = when (encoding) {
        PcmEncoding.PCM_16 -> 32767.0
        PcmEncoding.PCM_24_PACKED -> 8388607.0
        PcmEncoding.PCM_32 -> 2147483647.0
        PcmEncoding.PCM_FLOAT -> 1.0
    }

    /**
     * The sample starting at [offset], in the encoding's own units (an integer value, or the float
     * itself). Float samples are made safe here: NaN reads as silence (0), and anything beyond
     * full scale -- including +/-infinity -- is clamped to +/-1.0, so no non-finite value can ever
     * reach a level or statistic.
     */
    fun sampleAt(buffer: ByteArray, offset: Int, encoding: PcmEncoding): Double = when (encoding) {
        PcmEncoding.PCM_16 ->
            ((buffer[offset + 1].toInt() shl 8) or (buffer[offset].toInt() and 0xFF)).toShort().toDouble()
        PcmEncoding.PCM_24_PACKED ->
            // The top byte's toInt() sign-extends, which is what makes this a signed 24-bit value.
            ((buffer[offset + 2].toInt() shl 16) or
                ((buffer[offset + 1].toInt() and 0xFF) shl 8) or
                (buffer[offset].toInt() and 0xFF)).toDouble()
        PcmEncoding.PCM_32 -> littleEndianInt(buffer, offset).toDouble()
        PcmEncoding.PCM_FLOAT -> {
            val value = Float.fromBits(littleEndianInt(buffer, offset))
            when {
                value.isNaN() -> 0.0
                value > 1f -> 1.0
                value < -1f -> -1.0
                else -> value.toDouble()
            }
        }
    }

    /** Whether a sample (as returned by [sampleAt]) is at or beyond full scale. */
    fun isClipped(sample: Double, encoding: PcmEncoding): Boolean = abs(sample) >= maxPositive(encoding)

    /** Peak magnitude over every complete frame in `buffer[0, length)`, from 0 (silence) to 1
     * (a full-scale positive sample). Any trailing partial frame is ignored. */
    fun peakNormalized(buffer: ByteArray, length: Int, format: PcmFormat): Double {
        val sampleBytes = format.bytesPerSample
        val end = length - length % format.bytesPerFrame
        val max = maxPositive(format.encoding)
        var peak = 0.0
        var i = 0
        while (i + sampleBytes <= end) {
            val magnitude = abs(sampleAt(buffer, i, format.encoding))
            if (magnitude > peak) peak = magnitude
            i += sampleBytes
        }
        return (peak / max).coerceIn(0.0, 1.0)
    }

    private fun littleEndianInt(buffer: ByteArray, offset: Int): Int =
        (buffer[offset].toInt() and 0xFF) or
            ((buffer[offset + 1].toInt() and 0xFF) shl 8) or
            ((buffer[offset + 2].toInt() and 0xFF) shl 16) or
            (buffer[offset + 3].toInt() shl 24)
}

/**
 * Maps a linear peak (0..1 of full scale) onto the level meter's 0..1 scale -- perceptual
 * (log/dBFS), not linear: a straight linear ratio barely moves for normal speech, since speech
 * rarely gets near full scale (and recording with UNPROCESSED means no automatic gain control
 * boosting it either). Ears -- and eyes watching a meter -- perceive loudness on a log scale, so
 * dBFS is mapped onto a fixed floor..0dB range, the way a real VU/peak meter does. -45dBFS is a
 * normal quiet-room noise floor; anything at full scale reads as maxed out.
 */
internal fun levelFromLinearPeak(linear: Double): Float {
    if (linear <= 0.0) return 0f
    val dbfs = 20.0 * log10(linear)
    val floorDb = -45.0
    return ((dbfs - floorDb) / -floorDb).coerceIn(0.0, 1.0).toFloat()
}

/** The live level (0..1) of a buffer of [format] audio -- shared by real recording's waveform and
 * the microphone test, so both read identically for every format. */
internal fun peakAmplitude(buffer: ByteArray, length: Int, format: PcmFormat): Float =
    levelFromLinearPeak(PcmSamples.peakNormalized(buffer, length, format))

/** 16-bit mono shorthand for [peakAmplitude], the only format earlier versions recorded. */
internal fun peakAmplitude(buffer: ByteArray, length: Int): Float =
    peakAmplitude(buffer, length, PcmFormat.pcm16Mono(48000))

/** Result of [scanPcmSamples], in the encoding's own sample units (see [PcmSamples.sampleAt]). */
internal data class PcmScanResult(
    val peak: Double,
    val sumSquares: Double,
    val sampleCount: Long,
    val clippedSamples: Long,
    /** Bytes actually read, which can be less than the requested data size if the stream (file)
     * is truncated/damaged and ran out before the declared "data" chunk size was reached. */
    val bytesRead: Long
)

/**
 * Scans exactly [dataSize] bytes of [encoding] audio from [input], for peak, RMS and clipping.
 * [InputStream.read] can return any number of bytes, so a sample can straddle two reads; the
 * incomplete tail of one read is carried over and completed by the next rather than discarded,
 * which would otherwise desync every following sample boundary. A final incomplete sample (a
 * truncated file) is ignored.
 */
internal fun scanPcmSamples(input: InputStream, dataSize: Long, encoding: PcmEncoding): PcmScanResult {
    val sampleBytes = encoding.bytesPerSample
    var peak = 0.0
    var sumSquares = 0.0
    var sampleCount = 0L
    var clipped = 0L

    // A read chunk plus room for up to (sampleBytes - 1) carried-over bytes in front of it.
    val chunk = 8192
    val buffer = ByteArray(chunk + sampleBytes)
    var carried = 0
    var remaining = dataSize
    while (remaining > 0) {
        // Checked once per chunk (not per sample): cooperative cancellation only needs to be
        // noticed promptly, not instantly. A cancelled coroutine's runInterruptible() wrapper (see
        // LibraryFragment.showStats) marks this thread interrupted; without this, a large file
        // would keep scanning to completion regardless of cancellation.
        if (Thread.interrupted()) throw InterruptedException("Audio stats scan interrupted")
        val toRead = minOf(chunk.toLong(), remaining).toInt()
        val n = input.read(buffer, carried, toRead)
        if (n < 0) break
        remaining -= n

        val available = carried + n
        var i = 0
        while (i + sampleBytes <= available) {
            val sample = PcmSamples.sampleAt(buffer, i, encoding)
            val magnitude = abs(sample)
            if (magnitude > peak) peak = magnitude
            sumSquares += sample * sample
            sampleCount++
            if (PcmSamples.isClipped(sample, encoding)) clipped++
            i += sampleBytes
        }
        carried = available - i
        if (carried > 0) System.arraycopy(buffer, i, buffer, 0, carried)
    }

    return PcmScanResult(peak, sumSquares, sampleCount, clipped, bytesRead = dataSize - remaining)
}
