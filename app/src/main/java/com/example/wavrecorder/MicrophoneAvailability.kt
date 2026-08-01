package com.example.wavrecorder

import android.media.AudioManager

/**
 * Pre-recording assessment of which input device would currently be preferred, based purely on
 * [AudioManager]'s attached-device list. Deliberately distinct from [MicrophoneInfo]: this is only
 * ever a "connected" claim (a device is attached and would be preferred), never an "active" one --
 * the real route is only known once [android.media.AudioRecord.startRecording] actually runs (see
 * [SystemAudioSource]), and this status must never be worded as if it already confirmed that.
 */
sealed class PreferredMicStatus {
    /** Some external input device is attached and would be preferred over the phone's own mic.
     * [label] is always the device's own reported name when Android supplies one (falling back to
     * a generic type-based label only when it doesn't) -- deliberately shown even when [isInsta360]
     * is false: the real Insta360 USB-C receiver this app is built around is not guaranteed to
     * report a name containing "Insta360" at all (Android may report it as a generic
     * "USB Audio Device"), so the exact reported name is the only way for the user to actually
     * recognize *their* hardware rather than a made-up generic phrase. [isInsta360] only affects
     * which of two otherwise-identical status templates is shown. */
    data class ExternalConnected(val label: String, val isInsta360: Boolean) : PreferredMicStatus()

    /** No external input device is currently attached; recording would fall back to the phone's
     * own microphone. */
    object NoneDetected : PreferredMicStatus()
}

/**
 * Minimal, testable view of one attached input device's properties relevant to
 * [choosePreferredMicStatus] -- decouples that decision from [android.media.AudioDeviceInfo]
 * itself, which (unlike a plain data class) can't be constructed with a specific product name in a
 * JVM unit test, the same reason [AudioSource] exists as a seam over a real [android.media.AudioRecord].
 */
internal data class InputDeviceSnapshot(val label: String, val isExternal: Boolean, val isInsta360: Boolean)

/** The actual preference decision: prefer an Insta360 device by product name, else any other
 * external input, else report none detected. Pure and Android-free so it can be exercised directly
 * with fakes covering every combination, including the one real [android.media.AudioDeviceInfo]
 * instances can't easily be built with in a test -- an Insta360 device alongside a non-Insta360
 * external one. */
internal fun choosePreferredMicStatus(devices: List<InputDeviceSnapshot>): PreferredMicStatus {
    val externalDevices = devices.filter { it.isExternal }
    val preferred = externalDevices.firstOrNull { it.isInsta360 } ?: externalDevices.firstOrNull()
    return if (preferred != null) {
        PreferredMicStatus.ExternalConnected(preferred.label, preferred.isInsta360)
    } else {
        PreferredMicStatus.NoneDetected
    }
}

/**
 * Production adapter: queries [AudioManager] for the currently attached input devices and maps
 * them into the same [choosePreferredMicStatus] decision [openBestAudioRecord] itself uses when
 * actually picking a device to record from, so this pre-recording status stays consistent with what
 * recording will actually try to use. A null [audioManager] (the same practically-never-expected
 * case [openBestAudioRecord] falls back from) reports [PreferredMicStatus.NoneDetected] rather than
 * a false "connected" claim -- without it, nothing here can be verified either way.
 */
internal fun queryPreferredMicStatus(audioManager: AudioManager?): PreferredMicStatus {
    val devices = audioManager?.getDevices(AudioManager.GET_DEVICES_INPUTS)
        ?.map { device ->
            InputDeviceSnapshot(
                label = friendlyDeviceLabel(device),
                isExternal = isExternalInputType(device.type),
                isInsta360 = isInsta360Device(device)
            )
        }
        ?: emptyList()
    return choosePreferredMicStatus(devices)
}
