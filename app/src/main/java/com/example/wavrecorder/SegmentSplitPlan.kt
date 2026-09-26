package com.example.wavrecorder

/**
 * When a recording session rolls over to a new file: the earlier of the user's chosen split
 * duration and the largest WAV file the format can safely produce. Classic RIFF/WAV sizes are
 * unsigned 32-bit, so high-resolution audio can hit the 4 GiB ceiling before the chosen interval
 * (e.g. 192 kHz / 32-bit stereo reaches it in about 46 minutes); rolling over early keeps every
 * file valid instead of producing an overflowed, corrupt header. Pure JVM; computed once per
 * session from its immutable [format].
 *
 * Both limits are whole frames. The WAV limit keeps a [RIFF_SAFETY_MARGIN_BYTES] margin (never
 * less than two read buffers) below the representable maximum: rollover is checked after each
 * buffer is written, so a segment can run up to one buffer past its threshold and must still fit.
 */
data class SegmentSplitPlan(
    val format: PcmFormat,
    /** The user's chosen split duration, in bytes of [format] audio. */
    val userLimitBytes: Long,
    /** The largest safe segment for [format] under the RIFF size limit. */
    val riffLimitBytes: Long,
    /** The absolute ceiling: a segment must roll over before a write would take it past this --
     * the most audio a [format] WAV header can describe. */
    val hardLimitBytes: Long = WavHeaderWriter.maxDataBytes(format)
) {
    val effectiveLimitBytes: Long get() = minOf(userLimitBytes, riffLimitBytes)

    /** True when the WAV size limit, not the chosen duration, decides where files split. */
    val limitedByRiff: Boolean get() = riffLimitBytes < userLimitBytes

    val effectiveSeconds: Double get() = format.durationSeconds(effectiveLimitBytes)

    companion object {
        const val RIFF_SAFETY_MARGIN_BYTES = 64L * 1024 * 1024

        fun of(format: PcmFormat, splitSeconds: Long, readBufferBytes: Int): SegmentSplitPlan {
            val margin = maxOf(RIFF_SAFETY_MARGIN_BYTES, 2L * readBufferBytes)
            val riff = format.alignToFrame(WavHeaderWriter.maxDataBytes(format) - margin)
            val user = format.bytesForSeconds(splitSeconds.coerceAtLeast(1))
            return SegmentSplitPlan(format, userLimitBytes = user, riffLimitBytes = riff)
        }
    }
}
