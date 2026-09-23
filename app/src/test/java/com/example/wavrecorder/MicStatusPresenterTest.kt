package com.example.wavrecorder

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Every Input device state keeps its distinct wording, color and word badge -- and nothing before
 * route verification ever claims "verified". */
@RunWith(RobolectricTestRunner::class)
class MicStatusPresenterTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun `no external microphone detected`() {
        val ui = MicStatusPresenter.idle(app, PreferredMicStatus.NoneDetected)
        assertEquals(app.getString(R.string.mic_status_none_title), ui.title)
        assertEquals("The phone microphone will be used", ui.subtitle)
        assertEquals(R.color.status_warning_orange, ui.colorRes)
        assertEquals(R.string.mic_badge_phone, ui.badgeRes)
    }

    @Test
    fun `external microphone detected but not yet verified`() {
        val ui = MicStatusPresenter.idle(app, PreferredMicStatus.ExternalConnected("Insta360 Mic Air", isInsta360 = true))
        assertEquals(app.getString(R.string.mic_status_connected_title, "Insta360 Mic Air"), ui.title)
        assertEquals(app.getString(R.string.mic_status_connected_subtitle), ui.subtitle)
        assertEquals(R.color.status_detected_blue, ui.colorRes)
        assertEquals(R.string.mic_badge_detected, ui.badgeRes)
        assertFalse("a detected mic must never be described as verified",
            (ui.title + ui.subtitle + app.getString(ui.badgeRes)).contains("Verified"))
    }

    @Test
    fun `verified active external microphone`() {
        val ui = MicStatusPresenter.active(app, MicrophoneInfo("Insta360 Mic Air", isExternal = true, verified = true))
        assertEquals("Insta360 Mic Air", ui.title)
        assertEquals(app.getString(R.string.mic_status_verified_external_title), ui.subtitle)
        assertEquals(R.color.status_verified_green, ui.colorRes)
        assertEquals(R.string.mic_badge_verified, ui.badgeRes)
    }

    @Test
    fun `verified phone microphone`() {
        val ui = MicStatusPresenter.active(app, MicrophoneInfo("Phone microphone", isExternal = false, verified = true))
        assertEquals("Phone microphone", ui.title)
        assertEquals(app.getString(R.string.mic_status_verified_builtin_title), ui.subtitle)
        assertEquals(R.color.status_warning_orange, ui.colorRes)
        assertEquals(R.string.mic_badge_phone, ui.badgeRes)
    }

    @Test
    fun `a route that could not be verified is never shown as verified or green`() {
        val ui = MicStatusPresenter.active(app, MicrophoneInfo.UNVERIFIED.copy(isExternal = true))
        assertEquals(app.getString(R.string.mic_status_unverified_title), ui.subtitle)
        assertEquals(R.color.status_detected_blue, ui.colorRes)
        assertEquals(R.string.mic_badge_unverified, ui.badgeRes)
    }
}
