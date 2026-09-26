package com.example.wavrecorder

/**
 * The lossless PCM sample encodings this app can capture from AudioRecord and write to WAV.
 *
 * [androidEncoding] holds the `android.media.AudioFormat.ENCODING_*` values as literals so this
 * file stays plain JVM (and lint-clean below the API level that introduced the newer constants);
 * PcmFormatTest pins every one of them to the SDK's own constant.
 */
enum class PcmEncoding(
    val androidEncoding: Int,
    /** Bits each sample occupies in the WAV data (the container size). */
    val containerBits: Int,
    /** Bits of real precision in each sample. Equal to [containerBits] for every encoding here. */
    val validBits: Int,
    val isFloat: Boolean,
    /** The first Android API level whose AudioRecord accepts this encoding. */
    val minSdk: Int
) {
    PCM_16(androidEncoding = 2, containerBits = 16, validBits = 16, isFloat = false, minSdk = 23),
    PCM_24_PACKED(androidEncoding = 21, containerBits = 24, validBits = 24, isFloat = false, minSdk = 31),
    PCM_32(androidEncoding = 22, containerBits = 32, validBits = 32, isFloat = false, minSdk = 31),
    PCM_FLOAT(androidEncoding = 4, containerBits = 32, validBits = 32, isFloat = true, minSdk = 23);

    val bytesPerSample: Int get() = containerBits / 8

    companion object {
        fun fromAndroidEncoding(encoding: Int): PcmEncoding? = entries.firstOrNull { it.androidEncoding == encoding }
    }
}

/**
 * The one immutable description of the audio a recording session captures and writes. It's
 * negotiated once, when the session's AudioRecord opens, and every segment of that session uses
 * exactly this format: buffer sizes, byte counters, durations, split thresholds and WAV headers
 * are all derived from it, always in whole frames.
 *
 * Exactly one of [channelMask] (an `AudioFormat.CHANNEL_IN_*` positional mask, used for mono and
 * stereo) or [channelIndexMask] (one bit per captured channel, used for any other channel count)
 * is non-zero.
 */
data class PcmFormat(
    val sampleRate: Int,
    val encoding: PcmEncoding,
    val channelCount: Int,
    val channelMask: Int,
    val channelIndexMask: Int
) {
    init {
        require(sampleRate > 0) { "sample rate must be positive: $sampleRate" }
        require(channelCount in 1..MAX_CHANNELS) { "unsupported channel count: $channelCount" }
        require((channelMask == 0) != (channelIndexMask == 0)) {
            "exactly one of channelMask/channelIndexMask must be set ($channelMask, $channelIndexMask)"
        }
        require(channelIndexMask == 0 || Integer.bitCount(channelIndexMask) == channelCount) {
            "channel index mask $channelIndexMask doesn't describe $channelCount channels"
        }
        require(channelMask == 0 || positionalChannelCount(channelMask) == channelCount) {
            "channel mask $channelMask doesn't describe $channelCount channels"
        }
    }

    val bytesPerSample: Int get() = encoding.bytesPerSample
    val containerBitsPerSample: Int get() = encoding.containerBits
    val validBitsPerSample: Int get() = encoding.validBits
    val bytesPerFrame: Int get() = bytesPerSample * channelCount
    val byteRate: Long get() = sampleRate.toLong() * bytesPerFrame

    /** How the WAV header describes this format -- see [WavHeaderWriter]. */
    val wavLayout: WavLayout
        get() = when {
            encoding.isFloat && channelCount <= 2 -> WavLayout.FLOAT_BASIC
            encoding.isFloat -> WavLayout.EXTENSIBLE_FLOAT
            containerBitsPerSample == 16 && channelCount <= 2 -> WavLayout.PCM_BASIC
            // More than 16 bits, or more than 2 channels: WAVE_FORMAT_EXTENSIBLE, which is what
            // the WAV specification requires for both to be described unambiguously.
            else -> WavLayout.EXTENSIBLE_PCM
        }

    /** The WAVEFORMATEXTENSIBLE speaker-position mask: front-center for mono, front-left/right for
     * stereo, and 0 ("no speaker positions assigned") for index-mask channels, whose order Android
     * reports but whose physical positions it doesn't. */
    val wavSpeakerMask: Int
        get() = when {
            channelIndexMask != 0 -> 0
            channelMask == CHANNEL_IN_MONO -> SPEAKER_FRONT_CENTER
            channelMask == CHANNEL_IN_STEREO -> SPEAKER_FRONT_LEFT or SPEAKER_FRONT_RIGHT
            else -> 0
        }

    /** Bytes of audio in [seconds] seconds (whole frames, by construction). */
    fun bytesForSeconds(seconds: Long): Long = sampleRate.toLong() * seconds * bytesPerFrame

    /** [bytes] rounded down to a whole number of frames. */
    fun alignToFrame(bytes: Long): Long = if (bytes <= 0) 0 else bytes - bytes % bytesPerFrame

    fun framesIn(bytes: Long): Long = bytes / bytesPerFrame

    fun durationSeconds(bytes: Long): Double = framesIn(bytes).toDouble() / sampleRate

    companion object {
        const val MAX_CHANNELS = 16

        // android.media.AudioFormat input channel masks (literal for the same reason as
        // PcmEncoding.androidEncoding; PcmFormatTest pins them to the SDK).
        const val CHANNEL_IN_LEFT = 0x4
        const val CHANNEL_IN_RIGHT = 0x8
        const val CHANNEL_IN_MONO = 0x10
        const val CHANNEL_IN_STEREO = CHANNEL_IN_LEFT or CHANNEL_IN_RIGHT

        // WAVEFORMATEXTENSIBLE dwChannelMask speaker bits.
        const val SPEAKER_FRONT_LEFT = 0x1
        const val SPEAKER_FRONT_RIGHT = 0x2
        const val SPEAKER_FRONT_CENTER = 0x4

        /** The format every earlier version of this app recorded in, and the final compatibility
         * fallback: 16-bit mono at [sampleRate]. */
        fun pcm16Mono(sampleRate: Int) = PcmFormat(sampleRate, PcmEncoding.PCM_16, 1, CHANNEL_IN_MONO, 0)

        /** A format with the natural channel description for [channelCount]: the positional mono
         * or stereo mask for 1 or 2 channels, an index mask of that many channels otherwise. */
        fun of(sampleRate: Int, encoding: PcmEncoding, channelCount: Int): PcmFormat = when (channelCount) {
            1 -> PcmFormat(sampleRate, encoding, 1, CHANNEL_IN_MONO, 0)
            2 -> PcmFormat(sampleRate, encoding, 2, CHANNEL_IN_STEREO, 0)
            else -> PcmFormat(sampleRate, encoding, channelCount, 0, indexMaskFor(channelCount))
        }

        fun indexMaskFor(channelCount: Int): Int = (1 shl channelCount) - 1

        /** Number of channels a positional input mask describes, or 0 for one this app doesn't
         * record with (only mono and stereo positional masks are used; anything else is captured
         * through an index mask instead). */
        fun positionalChannelCount(channelMask: Int): Int = when (channelMask) {
            CHANNEL_IN_MONO -> 1
            CHANNEL_IN_STEREO -> 2
            else -> 0
        }

        /** Rebuilds the format of a recording journaled by an earlier app version, which only
         * stored sample rate, channel count and bit depth -- all integer PCM. Null if those
         * values can't describe a format this app writes. */
        fun fromLegacy(sampleRate: Int, channels: Int, bitsPerSample: Int): PcmFormat? {
            val encoding = when (bitsPerSample) {
                16 -> PcmEncoding.PCM_16
                24 -> PcmEncoding.PCM_24_PACKED
                32 -> PcmEncoding.PCM_32
                else -> return null
            }
            if (sampleRate <= 0 || channels !in 1..MAX_CHANNELS) return null
            return of(sampleRate, encoding, channels)
        }
    }
}

/** The four ways [WavHeaderWriter] lays out a header; see [PcmFormat.wavLayout]. */
enum class WavLayout {
    /** WAVE_FORMAT_PCM, 16-byte fmt: 16-bit mono/stereo -- the classic 44-byte header. */
    PCM_BASIC,
    /** WAVE_FORMAT_IEEE_FLOAT, 18-byte fmt plus a "fact" chunk: float mono/stereo. */
    FLOAT_BASIC,
    /** WAVE_FORMAT_EXTENSIBLE with the PCM subformat: 24/32-bit, or more than 2 channels. */
    EXTENSIBLE_PCM,
    /** WAVE_FORMAT_EXTENSIBLE with the IEEE-float subformat, plus a "fact" chunk. */
    EXTENSIBLE_FLOAT
}
