package com.example.wavrecorder

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Covers [LegacyNotificationCleanup] directly against the exact, documented legacy identifiers
 * (notification id 1002, channel "recording_result_channel") rather than the helper's own private
 * constants -- this proves the retraction targets the real historical values a pre-update install
 * actually used, not just "whatever LegacyNotificationCleanup itself happens to think they are".
 */
@RunWith(RobolectricTestRunner::class)
class LegacyNotificationCleanupTest {

    private fun app(): Application = ApplicationProvider.getApplicationContext()
    private fun manager(): NotificationManager = app().getSystemService(NotificationManager::class.java)

    /** Reproduces the exact OS-side state a pre-update install of this app would have left behind:
     * a channel and an active notification using the legacy identifiers described in the bug
     * report, built independently of [LegacyNotificationCleanup]'s own code. */
    private fun simulateLegacyNotificationState() {
        manager().createNotificationChannel(
            NotificationChannel(
                "recording_result_channel", "Recording results", NotificationManager.IMPORTANCE_DEFAULT
            )
        )
        manager().notify(
            1002,
            Notification.Builder(app(), "recording_result_channel")
                .setContentTitle("Recording stopped")
                .setContentText(
                    "External microphone disconnected. Recording was stopped and saved up to that point."
                )
                .setSmallIcon(R.drawable.ic_notification_mic)
                .build()
        )
    }

    @Test
    fun `cancels the legacy result notification left over from a previous install`() {
        simulateLegacyNotificationState()
        assertTrue(
            "sanity: the legacy notification must genuinely be active first",
            manager().activeNotifications.any { it.id == 1002 }
        )

        LegacyNotificationCleanup.run(app())

        assertFalse(
            "notification 1002 must be cancelled",
            manager().activeNotifications.any { it.id == 1002 }
        )
    }

    @Test
    fun `deletes the legacy result notification channel on Android O and above`() {
        simulateLegacyNotificationState()
        assertNotNull(
            "sanity: the legacy channel must genuinely exist first",
            manager().getNotificationChannel("recording_result_channel")
        )

        LegacyNotificationCleanup.run(app())

        assertNull(
            "recording_result_channel must be deleted",
            manager().getNotificationChannel("recording_result_channel")
        )
    }

    @Test
    fun `is a safe no-op when there is nothing left to clean up`() {
        // Nothing simulated -- a fresh install, or a launch after this cleanup already ran once.
        LegacyNotificationCleanup.run(app()) // must not throw

        assertNull(manager().getNotificationChannel("recording_result_channel"))
        assertFalse(manager().activeNotifications.any { it.id == 1002 })
    }
}
