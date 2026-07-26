package com.example.wavrecorder

import android.Manifest
import android.app.Application
import android.content.ComponentName
import androidx.fragment.app.testing.launchFragmentInContainer
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/**
 * Covers the Android 13+ permission bug: [ActivityResultContracts.RequestMultiplePermissions]'s
 * results map only contains entries for permissions it was actually asked for, so when
 * RECORD_AUDIO is already granted and only POST_NOTIFICATIONS gets requested, the map has no
 * RECORD_AUDIO entry at all. The fragment must fall back to checking the live permission state
 * instead of reading `results[RECORD_AUDIO]` and treating "absent" as "denied".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RecordFragmentPermissionTest {

    private fun app(): Application = ApplicationProvider.getApplicationContext()

    @Before
    fun setUpRecordingServiceBinding() {
        // RecordFragment binds to RecordingService as soon as it starts (onStart), so a plain
        // bindService() call needs somewhere to resolve to, or Robolectric hands
        // onServiceConnected a null binder and RecordFragment's cast to LocalBinder crashes.
        // Creating a real instance and registering its binder makes that later bindService()
        // call actually connect.
        val service = Robolectric.buildService(RecordingService::class.java).create().get()
        val binder = service.LocalBinder()
        shadowOf(app()).setComponentNameAndServiceForBindService(
            ComponentName(app(), RecordingService::class.java),
            binder
        )
    }

    @Test
    fun `mic already granted but notifications denied still starts the recording service`() {
        shadowOf(app()).grantPermissions(Manifest.permission.RECORD_AUDIO)
        shadowOf(app()).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        val scenario = launchFragmentInContainer<RecordFragment>(themeResId = R.style.Theme_WavRecorder)
        shadowOf(app()).nextStartedService // drain the bindService() from onStart() before checking
        scenario.onFragment { fragment ->
            // Simulates what RequestMultiplePermissions actually hands back in this situation:
            // only the permission that was requested (and denied) appears in the map.
            fragment.handlePermissionResult(mapOf(Manifest.permission.POST_NOTIFICATIONS to false))
        }

        val startedService = shadowOf(app()).nextStartedService
        assertNotNull("expected RecordingService to be started", startedService)
        assertEquals(RecordingService::class.java.name, startedService?.component?.className)
    }

    @Test
    fun `mic denied shows the permission-denied toast and never starts the service`() {
        shadowOf(app()).denyPermissions(Manifest.permission.RECORD_AUDIO)

        val scenario = launchFragmentInContainer<RecordFragment>(themeResId = R.style.Theme_WavRecorder)
        shadowOf(app()).nextStartedService // drain the bindService() from onStart() before checking
        scenario.onFragment { fragment ->
            fragment.handlePermissionResult(mapOf(Manifest.permission.RECORD_AUDIO to false))
        }

        assertNull(shadowOf(app()).nextStartedService)
        assertEquals(app().getString(R.string.permission_denied), ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun `both permissions already granted starts recording directly on record button tap`() {
        shadowOf(app()).grantPermissions(Manifest.permission.RECORD_AUDIO)
        shadowOf(app()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        val scenario = launchFragmentInContainer<RecordFragment>(themeResId = R.style.Theme_WavRecorder)
        shadowOf(app()).nextStartedService // drain the bindService() from onStart() before checking
        scenario.onFragment { fragment ->
            fragment.view!!.findViewById<android.view.View>(R.id.recordButton).performClick()
        }

        val startedService = shadowOf(app()).nextStartedService
        assertNotNull("expected RecordingService to be started without a permission prompt", startedService)
        assertEquals(RecordingService::class.java.name, startedService?.component?.className)
    }
}
