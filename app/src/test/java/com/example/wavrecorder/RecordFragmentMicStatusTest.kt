package com.example.wavrecorder

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.DialogInterface
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.testing.launchFragmentInContainer
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.AudioDeviceInfoBuilder
import org.robolectric.shadows.ShadowDialog

/**
 * Covers the pre-recording microphone status shown on [RecordFragment] while idle: it must reflect
 * [AudioManager]'s attached-device list immediately (not just after recording has already started,
 * which [RecordFragmentPermissionTest] and the mic-verification tests elsewhere already cover), stay
 * live via the fragment-scoped [android.media.AudioDeviceCallback] registration, and gate recording
 * behind a confirmation when no external input is detected -- without ever actually blocking it.
 *
 * The Insta360-specific label ("Insta360 Mic Air connected...") isn't exercised here: Robolectric's
 * [AudioDeviceInfoBuilder] only supports setting `type`, not `productName`, so that branch is
 * covered directly against [choosePreferredMicStatus] in [PreferredMicStatusTest] instead, using a
 * fake that isn't limited by what the real framework class's shadow can construct.
 */
@RunWith(RobolectricTestRunner::class)
class RecordFragmentMicStatusTest {

    private fun app(): Application = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        // Mirrors RecordFragmentPermissionTest's setup: a real bindService() target so
        // onServiceConnected doesn't crash on a null binder, and both permissions pre-granted so a
        // record-button tap that isn't blocked by the new confirmation dialog starts recording
        // directly, the same way it did before that dialog existed.
        val service = Robolectric.buildService(RecordingService::class.java).create().get()
        shadowOf(app()).setComponentNameAndServiceForBindService(
            ComponentName(app(), RecordingService::class.java),
            service.LocalBinder()
        )
        shadowOf(app()).grantPermissions(Manifest.permission.RECORD_AUDIO)
        shadowOf(app()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun usbDevice(): AudioDeviceInfo =
        AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_USB_DEVICE).build()

    @Test
    fun `idle status shows 'no external microphone detected' when nothing is attached`() {
        val scenario = launchFragmentInContainer<RecordFragment>(themeResId = R.style.Theme_WavRecorder)

        var labelText: String? = null
        scenario.onFragment { fragment ->
            labelText = fragment.view!!.findViewById<TextView>(R.id.micDeviceLabel).text.toString()
        }

        assertEquals(app().getString(R.string.mic_idle_none_detected), labelText)
    }

    @Test
    fun `idle status shows the external-connected label when a generic external input is already attached at launch`() {
        val audioManager = app().getSystemService(AudioManager::class.java)
        shadowOf(audioManager).setInputDevices(listOf(usbDevice()))

        val scenario = launchFragmentInContainer<RecordFragment>(themeResId = R.style.Theme_WavRecorder)

        var labelText: String? = null
        scenario.onFragment { fragment ->
            labelText = fragment.view!!.findViewById<TextView>(R.id.micDeviceLabel).text.toString()
        }

        assertEquals(app().getString(R.string.mic_idle_external_connected), labelText)
    }

    @Test
    fun `idle status updates live via the AudioDeviceCallback when a device is plugged in or removed while visible`() {
        val scenario = launchFragmentInContainer<RecordFragment>(themeResId = R.style.Theme_WavRecorder)
        val audioManager = app().getSystemService(AudioManager::class.java)
        val device = usbDevice()

        fun currentLabel(): String {
            var text: String? = null
            scenario.onFragment { fragment ->
                text = fragment.view!!.findViewById<TextView>(R.id.micDeviceLabel).text.toString()
            }
            return text!!
        }

        assertEquals(app().getString(R.string.mic_idle_none_detected), currentLabel())

        // notify=true drives the same AudioDeviceCallback.onAudioDevicesAdded the fragment itself
        // registered in onStart() -- no service restart or manual refresh call involved.
        shadowOf(audioManager).addInputDevice(device, true)
        assertEquals("expected the idle label to update immediately once a device is plugged in, " +
            "without the fragment needing to be restarted",
            app().getString(R.string.mic_idle_external_connected), currentLabel())

        shadowOf(audioManager).removeInputDevice(device, true)
        assertEquals("expected the idle label to revert immediately once the device is unplugged",
            app().getString(R.string.mic_idle_none_detected), currentLabel())
    }

    @Test
    fun `pressing record with no external microphone detected shows a confirmation dialog instead of starting immediately`() {
        val scenario = launchFragmentInContainer<RecordFragment>(themeResId = R.style.Theme_WavRecorder)
        shadowOf(app()).nextStartedService // drain the bindService() from onStart() before checking

        scenario.onFragment { fragment ->
            fragment.view!!.findViewById<View>(R.id.recordButton).performClick()
        }

        assertNull("recording must not start until the user confirms",
            shadowOf(app()).nextStartedService)
        val dialog = ShadowDialog.getLatestDialog() as? AlertDialog
        assertNotNull("expected a confirmation dialog to be shown", dialog)
        assertTrue(dialog!!.isShowing)
    }

    @Test
    fun `confirming the no-external-microphone dialog starts recording anyway`() {
        val scenario = launchFragmentInContainer<RecordFragment>(themeResId = R.style.Theme_WavRecorder)
        shadowOf(app()).nextStartedService

        scenario.onFragment { fragment ->
            fragment.view!!.findViewById<View>(R.id.recordButton).performClick()
        }
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle() // the dialog button's click dispatches via the main looper

        val startedService = shadowOf(app()).nextStartedService
        assertNotNull("expected recording to start once the user chooses to continue anyway",
            startedService)
        assertEquals(RecordingService::class.java.name, startedService?.component?.className)
    }

    @Test
    fun `canceling the no-external-microphone dialog leaves recording stopped`() {
        val scenario = launchFragmentInContainer<RecordFragment>(themeResId = R.style.Theme_WavRecorder)
        shadowOf(app()).nextStartedService

        scenario.onFragment { fragment ->
            fragment.view!!.findViewById<View>(R.id.recordButton).performClick()
        }
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        dialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick()

        assertNull("recording must remain stopped after the user cancels",
            shadowOf(app()).nextStartedService)
    }

    @Test
    fun `an available external microphone skips the confirmation dialog and starts recording directly`() {
        val audioManager = app().getSystemService(AudioManager::class.java)
        shadowOf(audioManager).setInputDevices(listOf(usbDevice()))

        val scenario = launchFragmentInContainer<RecordFragment>(themeResId = R.style.Theme_WavRecorder)
        shadowOf(app()).nextStartedService

        scenario.onFragment { fragment ->
            fragment.view!!.findViewById<View>(R.id.recordButton).performClick()
        }

        val startedService = shadowOf(app()).nextStartedService
        assertNotNull("expected recording to start directly, without a confirmation dialog",
            startedService)
    }
}
