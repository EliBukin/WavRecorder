package com.example.wavrecorder

/**
 * Format evidence: what Android reports about the format it captures from the input device -- the
 * device side, `AudioRecordingConfiguration.getFormat()` -- compared with the format this app
 * receives and saves (the client format, `getClientFormat()`).
 *
 * This is evidence about Android's capture path only. A device side that matches the client format
 * shows that Android reports no format conversion between its capture stream and this app; it does
 * not show the converter's effective precision, that no signal processing ran in the device or its
 * driver, or that capture is bit-perfect end to end.
 */
enum class CaptureMode {
    /** Android reports the device side in exactly the saved format: no rate, sample-format or
     * channel conversion between its capture stream and this app. */
    MATCHED,

    /** Android reports a different device-side format: it resamples and/or converts the sample
     * format or channels to produce the saved format. The file holds exactly what the app received,
     * but it carries no more than the device side does. */
    CONVERTED,

    /** Android didn't report the device side for this capture (some versions and devices never do;
     * some report it late), so there's no evidence either way. */
    UNREPORTED
}

/**
 * The capture-quality selection policy, defined once, here -- an explicit, tested policy for
 * choosing among formats, not a universal ranking of how recordings sound. It scores what Android
 * reports it *captures* (the device side, see [CaptureMode]) rather than merely the client format
 * AudioRecord agreed to deliver, which Android may produce by resampling or format conversion.
 * Pure JVM.
 *
 * **Quality level** ([Level]), compared in this order, highest first:
 *  1. Full band: a sample rate of at least [FULL_BAND_HZ]. Any full-band capture ranks above any
 *     narrow-band (telephony-rate) one, whatever its sample format: 24-bit/192 kHz above 32-bit/8 kHz.
 *  2. High precision: a sample format of at least 24 bits (24-bit, 32-bit float or 32-bit integer)
 *     above 16-bit.
 *  3. Sample rate, higher first.
 *  4. Sample format: 32-bit integer, then 32-bit float, then 24-bit, then 16-bit. This order is a
 *     policy choice between formats that all hold 24-bit audio losslessly; it says nothing about the
 *     converter's effective resolution, which Android doesn't report.
 *  5. Channel count, more first.
 *
 * **What a candidate is credited with** -- never more than the evidence supports:
 *  - [CaptureMode.MATCHED]: its format.
 *  - [CaptureMode.CONVERTED]: its format limited, in every dimension, by the device side -- the
 *    lower sample rate, precision and channel count of the two. Upsampling, padded bits and
 *    duplicated channels add no captured information, so a 32-bit/192 kHz stream the device side
 *    carries at 24-bit/48 kHz counts as 24-bit/48 kHz.
 *  - [CaptureMode.UNREPORTED], for a format the device advertised (or Android reported for it
 *    earlier): its format, except that 32-bit integer and float count only as 24-bit -- without a
 *    device-side report they can't be told apart from a 24-bit capture Android widened.
 *  - [CaptureMode.UNREPORTED], for an exploratory format ([CandidateOrigin.EXPLORATORY], tried only
 *    because the device left that capability unspecified): no more than the long-standing baseline,
 *    [BASELINE] (48 kHz, 16-bit, mono) -- that AudioRecord accepted it shows only that Android can
 *    deliver it, not that the device captures it.
 *
 * **Ties** at the same credited level, in order:
 *  1. Exactness: matched, then unreported, then converted (a path Android reports no conversion on,
 *     over one it reports converting).
 *  2. Processing: the UNPROCESSED audio source over MIC. MIC may apply platform processing
 *     (gain control, filtering); UNPROCESSED is meant to be free of it where the platform declares
 *     support. A higher credited level still comes first -- a better format over MIC beats a worse
 *     one over UNPROCESSED -- because the level only credits what the device side really carries,
 *     while processing can't be observed from the app either way.
 *  3. Evidence: a format the device advertised (or the baseline) over an exploratory one.
 *  4. Size: the smaller saved stream, i.e. the least uncredited padding, upsampling or channel
 *     duplication -- a larger container is never assumed to sound better; then integer over float.
 *  5. Whichever candidate was tried first ([FormatNegotiation.attemptOrder]).
 *
 * Why this suits voice and external USB microphones: full-band capture is what keeps speech
 * natural (telephony rates cut it off at 4 kHz), so it dominates; a 24-bit sample format then
 * leaves headroom for quiet speech and loud transients alike. Beyond that, higher rates and wider
 * formats are preferred only as far as Android reports the device side carrying them -- a larger
 * file that Android merely padded or resampled holds nothing more of the microphone.
 */
internal object CaptureQuality {

    const val FULL_BAND_HZ = 44_100

    private const val PRECISION_8 = 0
    private const val PRECISION_16 = 1
    private const val PRECISION_24 = 2
    private const val PRECISION_FLOAT = 3
    private const val PRECISION_32 = 4

    /** Precision rank of an encoding this app records: 16 < 24 < float < 32 (see [Level]). */
    fun precision(encoding: PcmEncoding): Int = when (encoding) {
        PcmEncoding.PCM_16 -> PRECISION_16
        PcmEncoding.PCM_24_PACKED -> PRECISION_24
        PcmEncoding.PCM_FLOAT -> PRECISION_FLOAT
        PcmEncoding.PCM_32 -> PRECISION_32
    }

    /** Precision rank of an Android encoding reported for the device side, or null for one that
     * isn't linear PCM this app knows (its precision is then unknown -- not zero, and not assumed). */
    fun precisionOfAndroidEncoding(encoding: Int): Int? = when (encoding) {
        3 -> PRECISION_8                  // ENCODING_PCM_8BIT
        1, 2 -> PRECISION_16              // ENCODING_DEFAULT (16-bit PCM), ENCODING_PCM_16BIT
        21 -> PRECISION_24                // ENCODING_PCM_24BIT_PACKED
        4 -> PRECISION_FLOAT              // ENCODING_PCM_FLOAT
        22 -> PRECISION_32                // ENCODING_PCM_32BIT
        else -> null
    }

    /** A quality level, ordered as documented on [CaptureQuality]. [precision] is a sample-format
     * rank ([precision]), never a measure of the converter's effective resolution. */
    data class Level(val sampleRate: Int, val precision: Int, val channels: Int) : Comparable<Level> {
        val fullBand: Boolean get() = sampleRate >= FULL_BAND_HZ
        val highPrecision: Boolean get() = precision >= PRECISION_24

        override fun compareTo(other: Level): Int = COMPARATOR.compare(this, other)

        /** This level, no higher than [cap] in any dimension. */
        fun atMost(cap: Level) = Level(minOf(sampleRate, cap.sampleRate), minOf(precision, cap.precision), minOf(channels, cap.channels))

        companion object {
            private val COMPARATOR = compareBy<Level>({ it.fullBand }, { it.highPrecision }, { it.sampleRate }, { it.precision }, { it.channels })
        }
    }

    /** The most an unreported exploratory candidate is credited with: the app's long-standing
     * 48 kHz, 16-bit mono format. */
    val BASELINE = Level(48_000, PRECISION_16, 1)

    /** The most an unreported advertised candidate's precision is credited with (see [CaptureQuality]). */
    private const val UNREPORTED_PRECISION_CAP = PRECISION_24

    /** A candidate's standing: its credited level, then the tie-breaks (see [CaptureQuality]). */
    data class Score(
        val level: Level,
        val mode: CaptureMode,
        /** Captured with the UNPROCESSED audio source (rather than MIC). */
        val unprocessedSource: Boolean,
        /** Tried only because the device left a capability unspecified ([CandidateOrigin.EXPLORATORY]). */
        val exploratory: Boolean,
        /** Bytes per second of the saved stream. */
        val byteRate: Long,
        val floatSamples: Boolean
    ) : Comparable<Score> {
        override fun compareTo(other: Score): Int = COMPARATOR.compare(this, other)

        companion object {
            private fun exactness(mode: CaptureMode) = when (mode) {
                CaptureMode.MATCHED -> 2
                CaptureMode.UNREPORTED -> 1
                CaptureMode.CONVERTED -> 0
            }

            private val COMPARATOR = compareBy<Score>(
                { it.level },
                { exactness(it.mode) },
                { it.unprocessedSource },
                { !it.exploratory },
                { -it.byteRate },
                { !it.floatSamples }
            )
        }
    }

    /** The level [format] would have if the device side carried exactly it: the most any candidate
     * for it can be credited with. */
    fun ceiling(format: PcmFormat): Level = Level(format.sampleRate, precision(format.encoding), format.channelCount)

    /** Whether [device] is exactly [client]: the same rate, sample encoding and channel count. */
    fun isExact(client: PcmFormat, device: DeviceSideFormat): Boolean =
        device.sampleRate == client.sampleRate &&
            normalized(device.encoding) == client.encoding.androidEncoding &&
            device.channelCount == client.channelCount

    /** ENCODING_DEFAULT is 16-bit PCM. */
    private fun normalized(encoding: Int): Int = if (encoding == 1) PcmEncoding.PCM_16.androidEncoding else encoding

    fun modeOf(client: PcmFormat, device: DeviceSideFormat?): CaptureMode = when {
        device == null -> CaptureMode.UNREPORTED
        isExact(client, device) -> CaptureMode.MATCHED
        else -> CaptureMode.CONVERTED
    }

    /** What an unreported capture of [client] is credited with: see [CaptureQuality]. */
    private fun unreportedLevel(client: PcmFormat, exploratory: Boolean): Level {
        val level = ceiling(client)
        return if (exploratory) level.atMost(BASELINE) else level.copy(precision = minOf(level.precision, UNREPORTED_PRECISION_CAP))
    }

    /**
     * [client] as captured, given the device side Android reports for it (null: not reported),
     * the [audioSource] that opened it and where the format came from ([origin]).
     */
    fun scoreOf(
        client: PcmFormat,
        device: DeviceSideFormat?,
        audioSource: Int = NegotiatedAudio.AUDIO_SOURCE_UNPROCESSED,
        origin: CandidateOrigin = CandidateOrigin.PROFILE
    ): Score {
        val exploratory = origin == CandidateOrigin.EXPLORATORY
        val mode = modeOf(client, device)
        val level = when (mode) {
            CaptureMode.MATCHED -> ceiling(client)
            CaptureMode.UNREPORTED -> unreportedLevel(client, exploratory)
            CaptureMode.CONVERTED -> {
                device!!
                // A dimension the device side reports in a form this app can't interpret is credited
                // as if unreported -- never with the client's value by default.
                val unknownCap = unreportedLevel(client, exploratory)
                Level(
                    sampleRate = if (device.sampleRate > 0) minOf(client.sampleRate, device.sampleRate) else unknownCap.sampleRate,
                    precision = precisionOfAndroidEncoding(device.encoding)?.let { minOf(precision(client.encoding), it) }
                        ?: unknownCap.precision,
                    channels = if (device.channelCount > 0) minOf(client.channelCount, device.channelCount) else unknownCap.channels
                )
            }
        }
        return score(client, level, mode, audioSource, exploratory)
    }

    fun scoreOf(attempt: NegotiationAttempt, device: DeviceSideFormat?): Score =
        scoreOf(attempt.candidate.format, device, attempt.audioSource, attempt.candidate.origin)

    /**
     * The best score [attempt] could still achieve: matched at its [ceiling]. This is what decides
     * whether trying it could improve on the best found so far. With [assumeUnreported], what it
     * could achieve *without* a device-side report -- used only to order the search once reports
     * have been absent for a while (see [NegotiationLimits.unreportedProbesBeforeReorder]), never
     * to rule a candidate out.
     */
    fun bestPossible(attempt: NegotiationAttempt, assumeUnreported: Boolean): Score {
        val format = attempt.candidate.format
        val exploratory = attempt.candidate.origin == CandidateOrigin.EXPLORATORY
        return if (assumeUnreported) {
            score(format, unreportedLevel(format, exploratory), CaptureMode.UNREPORTED, attempt.audioSource, exploratory)
        } else {
            score(format, ceiling(format), CaptureMode.MATCHED, attempt.audioSource, exploratory)
        }
    }

    private fun score(client: PcmFormat, level: Level, mode: CaptureMode, audioSource: Int, exploratory: Boolean) = Score(
        level, mode,
        unprocessedSource = audioSource == NegotiatedAudio.AUDIO_SOURCE_UNPROCESSED,
        exploratory = exploratory,
        byteRate = client.byteRate,
        floatSamples = client.encoding.isFloat
    )

    /** The order to try client formats in, best first: by the level they'd have if the device side
     * carried them exactly ([ceiling]). */
    val probeOrder: Comparator<PcmFormat> = compareByDescending { ceiling(it) }
}
