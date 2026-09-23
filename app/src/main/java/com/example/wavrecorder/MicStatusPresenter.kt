package com.example.wavrecorder

import android.content.Context
import androidx.annotation.ColorRes
import androidx.annotation.StringRes

/** Everything the Record screen's Input device card shows for one microphone state. */
internal data class MicStatusUi(
    val title: String,
    val subtitle: String,
    @ColorRes val colorRes: Int,
    @StringRes val badgeRes: Int
)

/**
 * Presentation-only: turns the microphone state [RecordFragment] already tracks into what the
 * Input device card displays. It decides nothing about routing -- [idle] only ever describes
 * AudioManager's attached-device list ("connected"/"detected", never "verified"), and only [active],
 * fed by the post-start [MicrophoneInfo] that AudioRecord's routing actually verified, may say
 * "verified". Every state pairs its color with a word (the badge), so it never relies on color
 * alone.
 */
internal object MicStatusPresenter {

    /** Before recording/testing starts: a guess from AudioManager's device list. */
    fun idle(context: Context, status: PreferredMicStatus): MicStatusUi = when (status) {
        is PreferredMicStatus.ExternalConnected -> MicStatusUi(
            title = context.getString(R.string.mic_status_connected_title, status.label),
            subtitle = context.getString(R.string.mic_status_connected_subtitle),
            colorRes = R.color.status_detected_blue,
            badgeRes = R.string.mic_badge_detected
        )
        PreferredMicStatus.NoneDetected -> MicStatusUi(
            title = context.getString(R.string.mic_status_none_title),
            subtitle = context.getString(R.string.mic_status_none_subtitle),
            colorRes = R.color.status_warning_orange,
            badgeRes = R.string.mic_badge_phone
        )
    }

    /** Once recording or a microphone test has started: what's actually being captured from, with
     * the device's own name as the title. */
    fun active(context: Context, info: MicrophoneInfo): MicStatusUi = when {
        !info.verified -> MicStatusUi(
            title = info.label,
            subtitle = context.getString(R.string.mic_status_unverified_title),
            colorRes = R.color.status_detected_blue,
            badgeRes = R.string.mic_badge_unverified
        )
        info.isExternal -> MicStatusUi(
            title = info.label,
            subtitle = context.getString(R.string.mic_status_verified_external_title),
            colorRes = R.color.status_verified_green,
            badgeRes = R.string.mic_badge_verified
        )
        else -> MicStatusUi(
            title = info.label,
            subtitle = context.getString(R.string.mic_status_verified_builtin_title),
            colorRes = R.color.status_warning_orange,
            badgeRes = R.string.mic_badge_phone
        )
    }
}
