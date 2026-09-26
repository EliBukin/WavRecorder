package com.example.wavrecorder

import android.media.AudioDeviceInfo
import android.media.AudioProfile
import android.os.Build
import android.util.Log

/**
 * What a recording or microphone-test session actually negotiated -- the format written (or
 * metered), how trustworthy its origin is, and which audio source opened it. [format] is always
 * the format AudioRecord confirmed it is delivering, never merely the first one requested.
 *
 * Two independent kinds of confidence are kept apart: [captureMode] is format evidence (what
 * Android reports about the device side of this capture), and [search] is how far the search for a
 * better format got. A complete search can end on an unreported capture, and a matched capture can
 * come from an incomplete search.
 */
data class NegotiatedAudio(
    val format: PcmFormat,
    val origin: CandidateOrigin,
    /** The `MediaRecorder.AudioSource` that opened (UNPROCESSED or MIC). */
    val audioSource: Int,
    /** Whether the platform declares UNPROCESSED capture supported
     * (`AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED`). Only when this is true *and*
     * [audioSource] is UNPROCESSED may the signal be described as unprocessed. */
    val unprocessedSupported: Boolean,
    /** How many attempts failed (couldn't be built, validated, started or routed). */
    val rejectedAttempts: Int,
    /** What Android reports about the device side of this capture (see [CaptureQuality]). */
    val captureMode: CaptureMode = CaptureMode.UNREPORTED,
    /** What Android reports the device side captures; null when it doesn't report it. */
    val deviceFormat: DeviceSideFormat? = null,
    /** Verified candidates compared and found worse. */
    val supersededCandidates: Int = 0,
    /** Whether the winner had to be stopped while alternatives were compared, and was reopened and
     * verified again. */
    val reopened: Boolean = false,
    /** How far the search for a better format got; null for a source that wasn't negotiated. */
    val search: SearchReport? = null,
    /** How many of the failed attempts were formats the device itself advertised. */
    val rejectedAdvertisedAttempts: Int = rejectedAttempts,
    /** How long negotiation took, from its start to the accepted candidate, in ms. */
    val negotiationMs: Long = 0
) {
    val isUnprocessed: Boolean get() = audioSource == AUDIO_SOURCE_UNPROCESSED && unprocessedSupported

    /** What can be said about platform processing, for diagnostics: none can be observed from the
     * app, so this is what was requested and what the platform declares. */
    val processing: String
        get() = when {
            isUnprocessed -> "UNPROCESSED source; the platform declares unprocessed capture supported"
            audioSource == AUDIO_SOURCE_UNPROCESSED ->
                "UNPROCESSED source, but the platform doesn't declare unprocessed capture supported: processing may still apply (unverified)"
            else -> "MIC source: platform processing may apply (unverified)"
        }

    companion object {
        /** `MediaRecorder.AudioSource.UNPROCESSED` / `.MIC` (literal, so this stays plain JVM). */
        const val AUDIO_SOURCE_UNPROCESSED = 9
        const val AUDIO_SOURCE_MIC = 1
    }
}

/**
 * The format Android's audio server is capturing from the device in, when it can say (the active
 * `AudioRecordingConfiguration`'s format), for comparison with the format this app receives and
 * writes: if they differ, Android is converting, and the saved format must not be presented as what
 * the device side carries. If they match, Android reports no conversion -- which says nothing about
 * the converter's effective precision or processing inside the device.
 */
data class DeviceSideFormat(val sampleRate: Int, val encoding: Int, val channelCount: Int) {
    /** True when [client] differs from this in rate, sample encoding or channel count -- i.e. the
     * capture is converted (see [CaptureQuality.isExact]). */
    fun differsFrom(client: PcmFormat): Boolean = !CaptureQuality.isExact(client, this)
}

/** Reads an input device's capabilities into the plain model [FormatNegotiation] works on.
 * Each query is guarded on its own: one that throws, or returns null despite its non-null
 * declaration (some implementations do), reads as empty -- "unspecified" -- without discarding
 * the others. What negotiation does with an unspecified capability is described on
 * [FormatNegotiation]. */
internal object AudioInputCapabilities {
    private const val TAG = "AudioInputCapabilities"

    fun of(device: AudioDeviceInfo?): DeviceCapabilities {
        if (device == null) return DeviceCapabilities.UNKNOWN
        return DeviceCapabilities(
            profiles = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                query(device, "audio profiles") { profilesOf(device) } ?: emptyList()
            } else {
                emptyList()
            },
            sampleRates = queryArray(device, "sample rates") { device.sampleRates },
            encodings = queryArray(device, "encodings") { device.encodings },
            channelCounts = queryArray(device, "channel counts") { device.channelCounts },
            channelMasks = queryArray(device, "channel masks") { device.channelMasks },
            channelIndexMasks = queryArray(device, "channel index masks") { device.channelIndexMasks }
        )
    }

    private fun <T> query(device: AudioDeviceInfo, what: String, read: () -> T?): T? = try {
        read()
    } catch (e: Exception) {
        Log.w(TAG, "Could not read $what of input device ${device.id}; treating them as unspecified", e)
        null
    }

    private fun queryArray(device: AudioDeviceInfo, what: String, read: () -> IntArray?): IntArray =
        query(device, what) { read() } ?: IntArray(0)

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.S)
    private fun profilesOf(device: AudioDeviceInfo): List<CapabilityProfile> {
        val profiles: List<AudioProfile>? = device.audioProfiles
        return profiles.orEmpty().map {
            val rates: IntArray? = it.sampleRates
            val masks: IntArray? = it.channelMasks
            val indexMasks: IntArray? = it.channelIndexMasks
            CapabilityProfile(
                encoding = it.format,
                sampleRates = rates ?: IntArray(0),
                channelMasks = masks ?: IntArray(0),
                channelIndexMasks = indexMasks ?: IntArray(0),
                encapsulated = it.encapsulationType != AudioProfile.AUDIO_ENCAPSULATION_TYPE_NONE
            )
        }
    }
}
