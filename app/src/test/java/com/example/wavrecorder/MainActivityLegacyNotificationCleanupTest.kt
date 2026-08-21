package com.example.wavrecorder

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Proves the legacy notification/channel retraction actually runs from the real app-launch path
 * (`MainActivity.onCreate()`), not merely that [LegacyNotificationCleanup] works correctly in
 * isolation -- see [LegacyNotificationCleanupTest] for that. Deliberately never starts a
 * recording anywhere in this file: the cleanup must not require one.
 */
@RunWith(RobolectricTestRunner::class)
class MainActivityLegacyNotificationCleanupTest {

    private fun app(): Application = ApplicationProvider.getApplicationContext()
    private fun manager(): NotificationManager = app().getSystemService(NotificationManager::class.java)

    @Before
    fun setUp() {
        shadowOf(app()).grantPermissions(Manifest.permission.RECORD_AUDIO)
        shadowOf(app()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        // RecordFragment (the ViewPager2's first page) always binds in onStart() regardless of
        // what this test needs, and an unregistered bindService() call still delivers
        // onServiceConnected with a null binder in Robolectric, crashing the fragment's own cast --
        // mirrors every other MainActivity/RecordFragment test's identical setup.
        val service = Robolectric.buildService(RecordingService::class.java).create().get()
        shadowOf(app()).setComponentNameAndServiceForBindService(
            ComponentName(app(), RecordingService::class.java),
            service.LocalBinder()
        )
    }

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
                .setSmallIcon(R.drawable.ic_notification_mic)
                .build()
        )
    }

    @Test
    fun `opening the app normally cancels the legacy notification and deletes its channel`() {
        simulateLegacyNotificationState()
        assertTrue(
            "sanity: the legacy notification must be active before the app is ever opened",
            manager().activeNotifications.any { it.id == 1002 }
        )

        ActivityScenario.launch(MainActivity::class.java)

        assertFalse(
            "normal app launch must cancel the legacy notification without needing a new recording",
            manager().activeNotifications.any { it.id == 1002 }
        )
        assertNull(
            "normal app launch must delete the legacy channel",
            manager().getNotificationChannel("recording_result_channel")
        )
    }

    @Test
    fun `opening the app normally never starts a recording or foreground service`() {
        ActivityScenario.launch(MainActivity::class.java)

        assertNull(
            "merely opening the app (and running the legacy cleanup) must never itself start a real recording",
            manager().activeNotifications.firstOrNull { it.id == 1001 }
        )
    }
}
