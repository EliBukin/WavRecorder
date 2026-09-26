package com.example.wavrecorder

/**
 * One correlated capability of an input device -- an API 31+ `android.media.AudioProfile`: an
 * encoding together with the sample rates and channel layouts supported *with that encoding*.
 * Empty rate/mask arrays mean "unspecified", not "none".
 */
data class CapabilityProfile(
    val encoding: Int,
    val sampleRates: IntArray,
    val channelMasks: IntArray,
    val channelIndexMasks: IntArray,
    /** Compressed/encapsulated profiles (e.g. IEC 61937) are never usable for PCM capture. */
    val encapsulated: Boolean = false
) {
    override fun equals(other: Any?): Boolean = other is CapabilityProfile &&
        encoding == other.encoding && encapsulated == other.encapsulated &&
        sampleRates.contentEquals(other.sampleRates) && channelMasks.contentEquals(other.channelMasks) &&
        channelIndexMasks.contentEquals(other.channelIndexMasks)

    override fun hashCode(): Int =
        ((encoding * 31 + sampleRates.contentHashCode()) * 31 + channelMasks.contentHashCode()) * 31 +
            channelIndexMasks.contentHashCode()
}

/**
 * What Android reports the selected input device supports. On API 31+ [profiles] carries the
 * correlated view; the arrays are the older, *independent* per-property lists (every entry of one
 * is not necessarily supported with every entry of another). An empty array means unspecified.
 */
data class DeviceCapabilities(
    val profiles: List<CapabilityProfile> = emptyList(),
    val sampleRates: IntArray = IntArray(0),
    val encodings: IntArray = IntArray(0),
    val channelCounts: IntArray = IntArray(0),
    val channelMasks: IntArray = IntArray(0),
    val channelIndexMasks: IntArray = IntArray(0)
) {
    val isEmpty: Boolean
        get() = profiles.isEmpty() && sampleRates.isEmpty() && encodings.isEmpty() &&
            channelCounts.isEmpty() && channelMasks.isEmpty() && channelIndexMasks.isEmpty()

    override fun equals(other: Any?): Boolean = other is DeviceCapabilities && profiles == other.profiles &&
        sampleRates.contentEquals(other.sampleRates) && encodings.contentEquals(other.encodings) &&
        channelCounts.contentEquals(other.channelCounts) && channelMasks.contentEquals(other.channelMasks) &&
        channelIndexMasks.contentEquals(other.channelIndexMasks)

    override fun hashCode(): Int = profiles.hashCode() * 31 + sampleRates.contentHashCode()

    /** A compact summary of what was reported, for diagnostics. */
    fun describe(): String {
        if (isEmpty) return "nothing reported (every capability unspecified)"
        fun IntArray.text() = if (isEmpty()) "unspecified" else joinToString(",")
        fun IntArray.hex() = if (isEmpty()) "unspecified" else joinToString(",") { "0x" + Integer.toHexString(it) }
        val profileText = if (profiles.isEmpty()) "no profiles" else profiles.joinToString("; ", "profiles [", "]") {
            "encoding ${it.encoding}${if (it.encapsulated) " (encapsulated)" else ""}: rates ${it.sampleRates.text()}, " +
                "masks ${it.channelMasks.hex()}, index masks ${it.channelIndexMasks.hex()}"
        }
        return "$profileText; encodings ${encodings.text()}; rates ${sampleRates.text()}; " +
            "channel counts ${channelCounts.text()}; masks ${channelMasks.hex()}; index masks ${channelIndexMasks.hex()}"
    }

    companion object {
        /** Nothing known about the device (no device, or it reported nothing). */
        val UNKNOWN = DeviceCapabilities()
    }
}

/** Where a candidate format came from -- which is also how much it can be trusted. Declared in
 * that order: a format several origins produce keeps the first. */
enum class CandidateOrigin {
    /** A correlated API 31+ profile: this combination is advertised (a dimension the profile leaves
     * unspecified filled with its baseline value -- see [FormatNegotiation]). */
    PROFILE,
    /** Built from the independent capability arrays; only AudioRecord validation proves the
     * combination is real. */
    CAPABILITY_ARRAYS,
    /** The app's long-standing 48/44.1 kHz, 16-bit mono formats, always offered: ordered by quality
     * with everything else (so a full-band fallback is tried before any telephony-rate format),
     * after any advertised format of equal quality. */
    COMPATIBILITY_FALLBACK,
    /** The device-side format Android reported for another candidate it converted, tried as an
     * exact client format (see [FormatNegotiation.exactCandidateFor]). */
    DEVICE_REPORTED,
    /** Not advertised: tried only because the device left a capability unspecified, from the finite
     * exploratory set documented on [FormatNegotiation]. Credited only as far as Android's
     * device-side report confirms it ([CaptureQuality]). */
    EXPLORATORY
}

data class FormatCandidate(val format: PcmFormat, val origin: CandidateOrigin)

/** One try: an `android.media.MediaRecorder.AudioSource` value with a candidate format. */
data class NegotiationAttempt(val audioSource: Int, val candidate: FormatCandidate)

/** Thrown when no candidate at all could be opened: a clear, final error for the UI. [rejected]
 * lists every attempt and why it failed, for diagnostics. */
class FormatNegotiationException(
    message: String,
    cause: Throwable?,
    val rejected: List<RejectedAttempt> = emptyList(),
    /** True when a time limit ended the attempts before every one was tried. */
    val timedOut: Boolean = false
) : IllegalStateException(message, cause)

/**
 * Chooses the candidate formats to try for the selected input device, best possible first. Pure
 * JVM -- the Android side ([openBestAudioRecord]) only supplies the device's capabilities and a way
 * to try opening an AudioRecord, so every ordering and fallback rule here is unit-tested directly.
 *
 * **Each capability dimension** (encoding, sample rate, channel layout) -- of each correlated
 * profile on API 31+, or of the independent arrays before that -- is one of:
 *  - *Advertised*: the device lists values this app can record. Exactly those are used, however
 *    high; nothing is added to them, so exploration never becomes a ceiling for a known device.
 *  - *Unspecified*: the device lists nothing (an empty array, or a query that failed). Filled with
 *    the long-standing baseline values -- 16-bit; 48 and 44.1 kHz; mono -- as before, *plus* the
 *    exploratory values below.
 *  - *Unsupported*: the device lists values, but none this app can record -- only compressed or
 *    encapsulated encodings, encodings AudioRecord doesn't take on this API level, rates outside
 *    8-768 kHz, or channel layouts beyond [PcmFormat.MAX_CHANNELS]. That profile (or the arrays)
 *    then contributes nothing: an explicit report is never treated as unspecified, and a compressed
 *    encoding is never taken as permission to invent PCM.
 *
 * **The exploratory set** -- the only formats offered that the device didn't advertise, and only in
 * a dimension it left unspecified -- is finite and small, chosen so that together with Android's
 * device-side report it can reach the high-quality modes a device doesn't describe:
 *  - encoding: one high-precision encoding -- 24-bit packed on API 31+, 32-bit float on API 24-30
 *    (the only AudioRecord encoding above 16-bit there);
 *  - sample rate: 96 and 192 kHz, above the 48/44.1 kHz baseline;
 *  - channels: stereo, above the mono baseline.
 * Exploratory values combine only with the other dimensions' advertised (or baseline and
 * exploratory) values: for a device that reports nothing at all, 14 formats beyond the two
 * compatibility ones. A candidate using any exploratory value is marked [CandidateOrigin.EXPLORATORY]
 * and must pass the same checks as any other -- initialization with exactly its format, start,
 * route and the device-side read -- and even then is credited only as far as the device side
 * confirms it ([CaptureQuality]). The search itself ([negotiateCapture]) turns a converted result
 * into the exact device-side format, so asking for a high format also discovers intermediate ones
 * the device side reports. Limits: formats outside this set (e.g. 88.2/176.4 kHz, 32-bit integer,
 * more than two channels) are reached only through a device-side report, never guessed, and no
 * finite search can prove the maximum of a capability space the device doesn't describe.
 *
 * **Order**: best possible first -- by the level each format would have if the device side
 * carried it exactly ([CaptureQuality.probeOrder]); at an equal level, positional channel masks
 * first, then by origin, most trusted first. The 48 and 44.1 kHz 16-bit mono formats are always
 * offered too, as compatibility fallbacks, in that order with the rest. Each format is tried with
 * every audio source before the next format ([attemptOrder]).
 */
object FormatNegotiation {

    /** The formats every earlier version recorded in, in the order it tried them. */
    val COMPATIBILITY_FALLBACKS: List<PcmFormat> = listOf(PcmFormat.pcm16Mono(48000), PcmFormat.pcm16Mono(44100))

    /** Baseline rates for an unspecified sample rate -- the compatibility rates. */
    private val BASELINE_RATES = listOf(48000, 44100)

    /** Exploratory rates for an unspecified sample rate (see [FormatNegotiation]). */
    val EXPLORATORY_RATES = listOf(192000, 96000)

    /** The exploratory encoding for an unspecified encoding on [sdkInt] (see [FormatNegotiation]). */
    fun exploratoryEncoding(sdkInt: Int): PcmEncoding? = listOf(PcmEncoding.PCM_24_PACKED, PcmEncoding.PCM_FLOAT)
        .firstOrNull { it.minSdk <= sdkInt }

    private const val MIN_SAMPLE_RATE = 8000
    private const val MAX_SAMPLE_RATE = 768_000

    private data class ChannelLayout(val count: Int, val mask: Int, val indexMask: Int)

    private val MONO = ChannelLayout(1, PcmFormat.CHANNEL_IN_MONO, 0)
    private val STEREO = ChannelLayout(2, PcmFormat.CHANNEL_IN_STEREO, 0)

    /** A value for one dimension, and whether it is exploratory rather than advertised or baseline. */
    private data class Choice<T>(val value: T, val exploratory: Boolean)

    /** Every candidate format, best first (see [FormatNegotiation]). */
    fun candidates(capabilities: DeviceCapabilities, sdkInt: Int): List<FormatCandidate> {
        val usable = PcmEncoding.entries.filter { it.minSdk <= sdkInt }.toSet()
        val found = mutableListOf<FormatCandidate>()

        val pcmProfiles = capabilities.profiles.filter { !it.encapsulated }
        if (pcmProfiles.isNotEmpty()) {
            for (profile in pcmProfiles) {
                // A profile's encoding is always explicit: one this app can't record here
                // contributes nothing.
                val encoding = PcmEncoding.fromAndroidEncoding(profile.encoding)?.takeIf { it in usable } ?: continue
                combine(
                    found, CandidateOrigin.PROFILE,
                    encodings = listOf(Choice(encoding, false)),
                    rates = rates(profile.sampleRates) ?: continue,
                    layouts = layouts(profile.channelMasks, profile.channelIndexMasks, IntArray(0)) ?: continue
                )
            }
        } else if (!capabilities.isEmpty) {
            val encodings = when {
                capabilities.encodings.isNotEmpty() ->
                    capabilities.encodings.toList().mapNotNull { PcmEncoding.fromAndroidEncoding(it) }.filter { it in usable }
                        .distinct().map { Choice(it, false) }
                // Profiles were reported, but none for PCM (all compressed/encapsulated): the
                // encoding is explicit, and unsupported -- not unspecified.
                capabilities.profiles.isNotEmpty() -> emptyList()
                else -> unspecifiedEncodings(sdkInt)
            }
            val rates = rates(capabilities.sampleRates)
            val layouts = layouts(capabilities.channelMasks, capabilities.channelIndexMasks, capabilities.channelCounts)
            if (encodings.isNotEmpty() && rates != null && layouts != null) {
                combine(found, CandidateOrigin.CAPABILITY_ARRAYS, encodings, rates, layouts)
            }
        } else {
            // Nothing reported at all: every dimension unspecified. The baseline-only combinations
            // are exactly the compatibility fallbacks added below; only the exploratory ones are new.
            combine(found, CandidateOrigin.COMPATIBILITY_FALLBACK, unspecifiedEncodings(sdkInt), unspecifiedRates(), unspecifiedLayouts())
            found.removeAll { it.origin != CandidateOrigin.EXPLORATORY }
        }

        val fallbacks = COMPATIBILITY_FALLBACKS.map { FormatCandidate(it, CandidateOrigin.COMPATIBILITY_FALLBACK) }
        return (found + fallbacks)
            .sortedWith(
                compareBy<FormatCandidate, PcmFormat>(CaptureQuality.probeOrder) { it.format }
                    .thenBy { if (it.format.channelMask != 0) 0 else 1 }
                    .thenBy { it.origin.ordinal }
            )
            .distinctBy { it.format }
    }

    /** Every combination of the choices; exploratory if any of its values is. */
    private fun combine(
        into: MutableList<FormatCandidate>,
        origin: CandidateOrigin,
        encodings: List<Choice<PcmEncoding>>,
        rates: List<Choice<Int>>,
        layouts: List<Choice<ChannelLayout>>
    ) {
        for (encoding in encodings) for (rate in rates) for (layout in layouts) {
            val exploratory = encoding.exploratory || rate.exploratory || layout.exploratory
            into += FormatCandidate(format(rate.value, encoding.value, layout.value), if (exploratory) CandidateOrigin.EXPLORATORY else origin)
        }
    }

    private fun unspecifiedEncodings(sdkInt: Int): List<Choice<PcmEncoding>> =
        listOf(Choice(PcmEncoding.PCM_16, false)) + listOfNotNull(exploratoryEncoding(sdkInt)?.let { Choice(it, true) })

    private fun unspecifiedRates(): List<Choice<Int>> =
        BASELINE_RATES.map { Choice(it, false) } + EXPLORATORY_RATES.map { Choice(it, true) }

    private fun unspecifiedLayouts(): List<Choice<ChannelLayout>> = listOf(Choice(MONO, false), Choice(STEREO, true))

    /** The rates to try: the advertised ones this app can record; the baseline and exploratory
     * rates when none is advertised; null when rates are advertised but none is recordable. */
    private fun rates(advertised: IntArray): List<Choice<Int>>? {
        if (advertised.isEmpty()) return unspecifiedRates()
        return advertised.filter { it in MIN_SAMPLE_RATE..MAX_SAMPLE_RATE }.distinct().map { Choice(it, false) }.ifEmpty { null }
    }

    /**
     * The order to try (source, format) pairs in -- the one definition of this policy, which the
     * README describes: format first, source second. Each of [candidates] (already best possible
     * first) is tried with every source in [audioSources] order -- UNPROCESSED, then MIC -- before
     * the next, lower-ranked format:
     *
     *   best format / UNPROCESSED, best format / MIC, next format / UNPROCESSED, next format / MIC, ...
     *
     * So a format that only works with MIC is still chosen over any lower format that works with
     * UNPROCESSED: the credited level decides, and the less-processed source breaks ties.
     */
    fun attemptOrder(candidates: List<FormatCandidate>, audioSources: List<Int>): List<NegotiationAttempt> =
        candidates.flatMap { candidate -> audioSources.map { source -> NegotiationAttempt(source, candidate) } }

    /**
     * The client format that is exactly what the device side captures, per [device] -- tried after
     * a candidate turns out to be converted, as the exact path at the same credited quality -- or
     * null when this app can't record it (an encoding it doesn't write, or not on this API level; a
     * rate or channel count out of range).
     */
    fun exactCandidateFor(device: DeviceSideFormat, sdkInt: Int): FormatCandidate? {
        val encoding = PcmEncoding.fromAndroidEncoding(if (device.encoding == 1) PcmEncoding.PCM_16.androidEncoding else device.encoding)
            ?.takeIf { it.minSdk <= sdkInt } ?: return null
        if (device.sampleRate !in MIN_SAMPLE_RATE..MAX_SAMPLE_RATE) return null
        if (device.channelCount !in 1..PcmFormat.MAX_CHANNELS) return null
        return FormatCandidate(PcmFormat.of(device.sampleRate, encoding, device.channelCount), CandidateOrigin.DEVICE_REPORTED)
    }

    /** The channel layouts a device exposes: mono/stereo positional masks, and index
     * masks (or bare counts) for everything else. Nothing exposed: the mono baseline and the
     * exploratory stereo layout. Layouts exposed, but none usable: null. */
    private fun layouts(masks: IntArray, indexMasks: IntArray, counts: IntArray): List<Choice<ChannelLayout>>? {
        if (masks.isEmpty() && indexMasks.isEmpty() && counts.isEmpty()) return unspecifiedLayouts()
        val result = mutableListOf<ChannelLayout>()
        for (mask in masks) {
            val count = PcmFormat.positionalChannelCount(mask)
            if (count > 0) {
                result += ChannelLayout(count, mask, 0)
            } else {
                // Some other positional layout (front/back, ...): capture its channels in order,
                // without claiming left/right positions it doesn't have.
                val n = Integer.bitCount(mask)
                if (n in 1..PcmFormat.MAX_CHANNELS) result += ChannelLayout(n, 0, PcmFormat.indexMaskFor(n))
            }
        }
        for (indexMask in indexMasks) {
            val n = Integer.bitCount(indexMask)
            if (n in 1..PcmFormat.MAX_CHANNELS) result += ChannelLayout(n, 0, indexMask)
        }
        for (count in counts) {
            if (count !in 1..PcmFormat.MAX_CHANNELS) continue
            val f = PcmFormat.of(48000, PcmEncoding.PCM_16, count)
            result += ChannelLayout(count, f.channelMask, f.channelIndexMask)
        }
        // One layout per channel count, preferring the positional mono/stereo description.
        return result.sortedBy { if (it.mask != 0) 0 else 1 }.distinctBy { it.count }.map { Choice(it, false) }.ifEmpty { null }
    }

    private fun format(rate: Int, encoding: PcmEncoding, layout: ChannelLayout) =
        PcmFormat(rate, encoding, layout.count, layout.mask, layout.indexMask)
}

/** Why a [NegotiationAttempt] was not used. */
data class RejectedAttempt(val attempt: NegotiationAttempt, val reason: String)
