package com.example.wavrecorder

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.DialogInterface
import android.content.res.ColorStateList
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.testing.launchFragmentInContainer
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Covers the pre-recording microphone status shown on [RecordFragment] while idle: it must reflect
 * [AudioManager]'s attached-device list immediately, stay live via the fragment-scoped
 * [android.media.AudioDeviceCallback] registration, gate recording behind a confirmation when no
 * external input is detected (without ever actually blocking it), and hand off cleanly to the
 * post-start "verified active" status once a real [AudioRecord] confirms its route.
 *
 * The Insta360-specific label isn't exercised against a real [AudioDeviceInfo] here: Robolectric's
 * [AudioDeviceInfoBuilder] only supports setting `type`, not `productName`, so that branch is
 * covered directly against [choosePreferredMicStatus] in [PreferredMicStatusTest] instead, using a
 * fake that isn't limited by what the real framework class's shadow can construct.
 */
@RunWith(RobolectricTestRunner::class)
class RecordFragmentMicStatusTest {

    private fun app(): Application = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        shadowOf(app()).grantPermissions(Manifest.permission.RECORD_AUDIO)
        shadowOf(app()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun bindRealService(): RecordingService {
        val service = Robolectric.buildService(RecordingService::class.java).create().get()
        shadowOf(app()).setComponentNameAndServiceForBindService(
            ComponentName(app(), RecordingService::class.java),
            service.LocalBinder()
        )
        return service
    }

    /** The mismatch-triggered stop's own background join runs on a real, uncontrolled-by-Robolectric
     * thread; a retry/continue action must wait for it to genuinely reach a terminal state (not just
     * for the fake source's read() to unblock) before RecordingService will accept a new session --
     * see ServiceState's FINALIZING guard. Mirrors every other test file's identical helper. */
    private fun awaitFinalizationAndIdle(service: RecordingService, timeoutMs: Long = 3000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (service.isFinalizing && System.currentTimeMillis() < deadline) Thread.sleep(5)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun usbDevice(): AudioDeviceInfo =
        AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_USB_DEVICE).build()

    /** [AudioDeviceInfoBuilder] only lets a test choose `type`, not `productName` -- Robolectric's
     * shadow always reports this fixed placeholder for it, never null/empty. That's actually a
     * useful stand-in for the real concern this app cares about: the real Insta360 receiver isn't
     * guaranteed to report a name containing "Insta360" either (Android may call it a generic
     * "USB Audio Device"), so the label shown must always be whatever was actually reported --
     * exercised here as "robolectric" instead of a real product string, but the same code path. */
    private val GENERIC_USB_DEVICE_LABEL = "robolectric"

    private fun dotColor(scenario: androidx.fragment.app.testing.FragmentScenario<RecordFragment>): Int {
        var color = 0
        scenario.onFragment { fragment ->
            val dot = fragment.view!!.findViewById<View>(R.id.micStatusDot)
            color = (dot.backgroundTintList as ColorStateList).defaultColor
        }
        return color
    }

    private fun titleText(scenario: androidx.fragment.app.testing.FragmentScenario<RecordFragment>): String {
        var text: String? = null
        scenario.onFragment { fragment ->
            text = fragment.view!!.findViewById<TextView>(R.id.micStatusTitle).text.toString()
        }
        return text!!
    }

    private fun subtitleText(scenario: androidx.fragment.app.testing.FragmentScenario<RecordFragment>): String {
        var text: String? = null
        scenario.onFragment { fragment ->
            text = fragment.view!!.findViewById<TextView>(R.id.micStatusSubtitle).text.toString()
        }
        return text!!
    }

    private fun badgeText(scenario: androidx.fragment.app.testing.FragmentScenario<RecordFragment>): String {
        var text: String? = null
        scenario.onFragment { fragment ->
            text = fragment.view!!.findViewById<TextView>(R.id.micStatusBadge).text.toString()
        }
        return text!!
    }

    @Test
    fun `idle status shows 'no external microphone detected' when nothing is attached`() {
        bindRealService()
        val scenario = launchFragmentInContainer<RecordFragment>(themeResId = R.style.Theme_WavRecorder)

        assertEquals(app().getString(R.string.mic_status_none_title), titleText(scenario))
        assertEquals(app().getString(R.string.mic_status_none_subtitle), subtitleText(scenario))
        assertEquals(ContextColor.of(app(), R.color.status_warning_orange), dotColor(scenario))
        assertEquals("the warning state must be named, not conveyed by the orange dot alone",
            app().getString(R.string.mic_badge_phone), badgeText(scenario))
    }

    @Test
    fun `with no external microphone the Input device area says the phone microphone will be used, and only then`() {
        bindRealService()
        val scenario = launchFragmentInContainer<RecordFragment>(themeResId = R.style.Theme_WavRecorder)
        val audioManager = app().getSystemService(AudioManager::class.java)
        val device = usbDevice()
        val phoneMicWording = "The phone microphone will be used"

        // The literal text, not just the resource: this wording is itself the requirement.
        assertEquals(phoneMicWording, subtitleText(scenario))

        // A USB mic plugged in: its reported name is shown, and the phone-mic wording is gone.
        shadowOf(audioManager).addInputDevice(device, true)
        assertEquals(
            app().getString(R.string.mic_status_connected_title, GENERIC_USB_DEVICE_LABEL),
            titleText(scenario)
        )
        assertTrue(titleText(scenario).contains(GENERIC_USB_DEVICE_LABEL))
        assertFalse(titleText(scenario).contains(phoneMicWording))
        assertFalse(subtitleText(scenario).contains(phoneMicWording))

        // Unplugged again: back to the phone-mic wording.
        shadowOf(audioManager).removeInputDevice(device, true)
        assertEquals(phoneMicWording, subtitleText(scenario))
    }

    @Test
    fun `idle status shows the connected label and reported name when a generic external input is already attached at launch`() {
        bindRealService()
        val audioManager = app().getSystemService(AudioManager::class.java)
        shadowOf(audioManager).setInputDevices(listOf(usbDevice()))

        val scenario = launchFragmentInContainer<RecordFragment>(themeResId = R.style.Theme_WavRecorder)

        assertEquals(
            app().getString(R.string.mic_status_connected_title, GENERIC_USB_DEVICE_LABEL),
            titleText(scenario)
        )
        assertEquals(app().getString(R.string.mic_status_connected_subtitle), subtitleText(scenario))
        assertEquals(ContextColor.of(app(), R.color.status_detected_blue), dotColor(scenario))
    }

    @Test
    fun `idle status updates live via the AudioDeviceCallback when a device is plugged in or removed while visible`() {
        bindRealService()
        val scenario = launchFragmentInContainer<RecordFragment>(themeResId = R.style.Theme_WavRecorder)
        val audioManager = app().getSystemService(AudioManager::class.java)
        val device = usbDevice()

        assertEquals(app().getString(R.string.mic_status_none_title), titleText(scenario))

        // notify=true drives the same AudioDeviceCallback.onAudioDevicesAdded the fragment itself
        // registered in onStart() -- no service restart or manual refresh call involved.
        shadowOf(audioManager).addInputDevice(device, true)
        assertEquals("expected the idle title to update immediately once a device is plugged in",
            app().getString(R.string.mic_status_connected_title, GENERIC_USB_DEVICE_LABEL), titleText(scenario))

        shadowOf(audioManager).removeInputDevice(device, true)
        assertEquals("expected the idle title to revert immediately once the device is unplugged",
            app().getString(R.string.mic_status_none_title), titleText(scenario))
    }

    @Test
    fun `pressing record with no external microphone detected shows a confirmation dialog instead of starting immediately`() {
        bindRealService()
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
        bindRealService()
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
        bindRealService()
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
        bindRealService()
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

    @Test
    fun `once recording actually starts, the verified-active status replaces the pre-recording connected status`() {
        val service = bindRealService()
        val chunk = byteArrayOf(1, 2, 3, 4)
        val blockForever = CountDownLatch(1)
        val fakeSource = object : AudioSource {
            override fun startRecording() {}
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                blockForever.await(5, TimeUnit.SECONDS)
                return -1
            }
            override fun stop() { blockForever.countDown() }
            override fun release() {}
            override fun describeMicrophone() =
                MicrophoneInfo(label = "Insta360 Mic Air", isExternal = true, verified = true)
        }
        service.recorder = WavRecorder(
            openAudioSource = { WavRecorder.RecorderConfig(fakeSource, sampleRate = 48000, bufferSize = chunk.size) }
        )

        val audioManager = app().getSystemService(AudioManager::class.java)
        shadowOf(audioManager).setInputDevices(listOf(usbDevice()))

        val scenario = launchFragmentInContainer<RecordFragment>(themeResId = R.style.Theme_WavRecorder)

        // Before recording: a "connected/preferred" claim only, blue dot, "Detected" badge.
        assertEquals(app().getString(R.string.mic_status_connected_title, GENERIC_USB_DEVICE_LABEL), titleText(scenario))
        assertEquals(ContextColor.of(app(), R.color.status_detected_blue), dotColor(scenario))
        assertEquals(app().getString(R.string.mic_badge_detected), badgeText(scenario))

        scenario.onFragment { fragment ->
            fragment.view!!.findViewById<View>(R.id.recordButton).performClick()
        }
        val deadline = System.currentTimeMillis() + 2000
        while (subtitleText(scenario) != app().getString(R.string.mic_status_verified_external_title) &&
            System.currentTimeMillis() < deadline
        ) {
            Thread.sleep(5)
            shadowOf(Looper.getMainLooper()).idle()
        }

        // After recording starts: a confirmed claim from AudioRecord.getRoutedDevice() itself --
        // the verified device's own name as the title, distinct wording ("verified active", never
        // "connected/preferred") as the status line, green dot and a "Verified" badge.
        assertEquals("expected the verified device's name once recording actually starts",
            "Insta360 Mic Air", titleText(scenario))
        assertEquals("expected the verified-active status line once recording actually starts",
            app().getString(R.string.mic_status_verified_external_title), subtitleText(scenario))
        assertEquals(ContextColor.of(app(), R.color.status_verified_green), dotColor(scenario))
        assertEquals(app().getString(R.string.mic_badge_verified), badgeText(scenario))

        blockForever.countDown() // release the blocked background thread so it doesn't linger past the test
    }

    /** Every test below builds a fresh [AudioSource] (and its own [CountDownLatch]) per session
     * -- via [WavRecorder]'s `openAudioSource` seam, called once per `start()` -- so a
     * mismatch-triggered stop followed by a retry/continue attempt never reuses an
     * already-counted-down latch from the previous, already-stopped session. [latestBlockLatch]
     * always points at whichever session is currently blocked in `read()`, so a test can release
     * it at the end without leaking a background thread past itself. */
    private fun recorderVerifyingAs(info: MicrophoneInfo, latestBlockLatch: Array<CountDownLatch?>): WavRecorder =
        WavRecorder(
            openAudioSource = {
                val blockForever = CountDownLatch(1)
                latestBlockLatch[0] = blockForever
                val fakeSource = object : AudioSource {
                    override fun startRecording() {}
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        blockForever.await(5, TimeUnit.SECONDS)
                        return -1
                    }
                    override fun stop() { blockForever.countDown() }
                    override fun release() {}
                    override fun describeMicrophone() = info
                }
                WavRecorder.RecorderConfig(fakeSource, sampleRate = 48000, bufferSize = 4)
            }
        )

    @Test
    fun `an external mic detected beforehand but verified as the phone mic shows a blocking mismatch dialog and stops the session`() {
        val service = bindRealService()
        val latestBlockLatch = arrayOfNulls<CountDownLatch>(1)
        service.recorder = recorderVerifyingAs(
            MicrophoneInfo(label = "Phone microphone", isExternal = false, verified = true),
            latestBlockLatch
        )
        val audioManager = app().getSystemService(AudioManager::class.java)
        shadowOf(audioManager).setInputDevices(listOf(usbDevice()))

        val scenario = launchFragmentInContainer<RecordFragment>(themeResId = R.style.Theme_WavRecorder)
        scenario.onFragment { fragment ->
            fragment.view!!.findViewById<View>(R.id.recordButton).performClick()
        }

        assertFalse("recording must not continue once the verified route mismatches what was " +
            "expected before recording started", service.isRecording)
        val dialog = ShadowDialog.getLatestDialog() as? AlertDialog
        assertNotNull("expected a mismatch dialog explaining the external mic wasn't actually used",
            dialog)
        assertFalse("the mismatch dialog must be blocking, not dismissible by tapping outside",
            shadowOf(dialog!!).isCancelable)

        latestBlockLatch[0]?.countDown()
    }

    @Test
    fun `an external mic detected beforehand but an unverified route also shows the mismatch dialog`() {
        val service = bindRealService()
        val latestBlockLatch = arrayOfNulls<CountDownLatch>(1)
        service.recorder = recorderVerifyingAs(MicrophoneInfo.UNVERIFIED, latestBlockLatch)
        val audioManager = app().getSystemService(AudioManager::class.java)
        shadowOf(audioManager).setInputDevices(listOf(usbDevice()))

        val scenario = launchFragmentInContainer<RecordFragment>(themeResId = R.style.Theme_WavRecorder)
        scenario.onFragment { fragment ->
            fragment.view!!.findViewById<View>(R.id.recordButton).performClick()
        }

        assertFalse(service.isRecording)
        assertNotNull("an unverifiable route must not be allowed to silently continue either",
            ShadowDialog.getLatestDialog())

        latestBlockLatch[0]?.countDown()
    }

    @Test
    fun `continuing with the phone microphone from the mismatch dialog starts recording without showing it again`() {
        val service = bindRealService()
        val latestBlockLatch = arrayOfNulls<CountDownLatch>(1)
        service.recorder = recorderVerifyingAs(
            MicrophoneInfo(label = "Phone microphone", isExternal = false, verified = true),
            latestBlockLatch
        )
        val audioManager = app().getSystemService(AudioManager::class.java)
        shadowOf(audioManager).setInputDevices(listOf(usbDevice()))

        val scenario = launchFragmentInContainer<RecordFragment>(themeResId = R.style.Theme_WavRecorder)
        scenario.onFragment { fragment ->
            fragment.view!!.findViewById<View>(R.id.recordButton).performClick()
        }
        // The mismatch-triggered stop must reach a genuine terminal state before RecordingService
        // will accept the dialog's retry/continue action as a new session -- see ServiceState.
        awaitFinalizationAndIdle(service)

        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle() // a dialog button's click dispatches via the main looper

        assertTrue("expected recording to continue on the phone mic once the user explicitly " +
            "accepted it", service.isRecording)
        assertEquals("Phone microphone", titleText(scenario))
        assertEquals(app().getString(R.string.mic_status_verified_builtin_title), subtitleText(scenario))
        assertEquals(app().getString(R.string.mic_badge_phone), badgeText(scenario))

        latestBlockLatch[0]?.countDown()
    }

    @Test
    fun `retrying from the mismatch dialog re-attempts and shows it again if the mismatch persists`() {
        val service = bindRealService()
        val latestBlockLatch = arrayOfNulls<CountDownLatch>(1)
        service.recorder = recorderVerifyingAs(
            MicrophoneInfo(label = "Phone microphone", isExternal = false, verified = true),
            latestBlockLatch
        )
        val audioManager = app().getSystemService(AudioManager::class.java)
        shadowOf(audioManager).setInputDevices(listOf(usbDevice()))

        val scenario = launchFragmentInContainer<RecordFragment>(themeResId = R.style.Theme_WavRecorder)
        scenario.onFragment { fragment ->
            fragment.view!!.findViewById<View>(R.id.recordButton).performClick()
        }
        latestBlockLatch[0]?.countDown()
        // The mismatch-triggered stop must reach a genuine terminal state before RecordingService
        // will accept the retry as a new session -- see ServiceState.
        awaitFinalizationAndIdle(service)

        val firstDialog = ShadowDialog.getLatestDialog() as AlertDialog
        firstDialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick() // Retry
        shadowOf(Looper.getMainLooper()).idle() // a dialog button's click dispatches via the main looper
        latestBlockLatch[0]?.countDown()
        awaitFinalizationAndIdle(service)

        assertFalse("recording must still not be active after a retry into the same mismatch",
            service.isRecording)
        val secondDialog = ShadowDialog.getLatestDialog()
        assertNotNull("expected the mismatch dialog to reappear since the device state hasn't changed",
            secondDialog)
        assertTrue("expected a genuinely new dialog instance from the retry, not the same one still on screen",
            secondDialog !== firstDialog)

        latestBlockLatch[0]?.countDown()
    }

    @Test
    fun `an external mic that verifies correctly never shows the mismatch dialog`() {
        // Regression guard for the happy path this whole feature must never regress: an external
        // mic that verifies exactly as expected must record straight through, exactly like before
        // this mismatch check existed.
        val service = bindRealService()
        val latestBlockLatch = arrayOfNulls<CountDownLatch>(1)
        service.recorder = recorderVerifyingAs(
            MicrophoneInfo(label = "Insta360 Mic Air", isExternal = true, verified = true),
            latestBlockLatch
        )
        val audioManager = app().getSystemService(AudioManager::class.java)
        shadowOf(audioManager).setInputDevices(listOf(usbDevice()))

        val scenario = launchFragmentInContainer<RecordFragment>(themeResId = R.style.Theme_WavRecorder)
        scenario.onFragment { fragment ->
            fragment.view!!.findViewById<View>(R.id.recordButton).performClick()
        }

        assertTrue(service.isRecording)
        assertNull("no mismatch dialog should ever appear when the route verifies as expected",
            ShadowDialog.getLatestDialog())

        latestBlockLatch[0]?.countDown()
    }
}

/** Tiny helper so tests can compare against the exact resolved color int without repeating
 * ContextCompat boilerplate at every call site. */
private object ContextColor {
    fun of(context: android.content.Context, colorRes: Int): Int =
        androidx.core.content.ContextCompat.getColor(context, colorRes)
}
