package com.example.wavrecorder

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.widget.Button
import androidx.fragment.app.testing.launchFragmentInContainer
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.color.MaterialColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.AudioDeviceInfoBuilder

/**
 * Covers the record button's destructive/red styling: it must apply only while the button
 * genuinely represents "Stop recording" (a real, active/finalizing recording -- never idle,
 * never the separate microphone-test button), come from theme resources (`?attr/colorError`),
 * and be fully restored once recording ends.
 */
@RunWith(RobolectricTestRunner::class)
class RecordFragmentStopButtonColorTest {

    private fun app(): Application = ApplicationProvider.getApplicationContext()

    private lateinit var boundService: RecordingService

    @Before
    fun setUp() {
        shadowOf(app()).grantPermissions(Manifest.permission.RECORD_AUDIO)
        shadowOf(app()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val audioManager = app().getSystemService(AudioManager::class.java)
        shadowOf(audioManager).setInputDevices(
            listOf(AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_USB_DEVICE).build())
        )
        boundService = Robolectric.buildService(RecordingService::class.java).create().get()
        shadowOf(app()).setComponentNameAndServiceForBindService(
            ComponentName(app(), RecordingService::class.java),
            boundService.LocalBinder()
        )
    }

    // Reports itself as a verified external mic so it matches the external input device setUp()
    // registers above -- otherwise RecordFragment's own post-start mic-route mismatch check (a
    // separately-covered fix; see RecordFragmentMicTestMismatchTest) would immediately stop a test
    // using this fake right back out again, silently exercising the mismatch path instead of the
    // real "test genuinely starts and runs" one this file's own tests actually mean to exercise.
    private fun blockingAudioSource(): AudioSource = object : AudioSource {
        override fun startRecording() {}
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int { Thread.sleep(20); return 4 }
        override fun stop() {}
        override fun release() {}
        override fun describeMicrophone(): MicrophoneInfo =
            MicrophoneInfo(label = "USB Mic", isExternal = true, verified = true)
    }

    @Test
    fun `the idle record button never uses the destructive color`() {
        val scenario = launchFragmentInContainer<RecordFragment>(themeResId = R.style.Theme_WavRecorder)

        var idleColor = 0
        var errorColor = 0
        scenario.onFragment { fragment ->
            val button = fragment.view!!.findViewById<Button>(R.id.recordButton)
            idleColor = button.backgroundTintList?.defaultColor
                ?: MaterialColors.getColor(button, com.google.android.material.R.attr.colorPrimary)
            errorColor = MaterialColors.getColor(button, com.google.android.material.R.attr.colorError)
        }

        assertNotEquals(
            "the idle record button must not already be styled with the destructive/error color",
            errorColor, idleColor
        )
    }

    @Test
    fun `the record button turns the destructive color once a real recording actually starts`() {
        boundService.recorder = WavRecorder(startup = ImmediateStartup, openAudioSource = { WavRecorder.RecorderConfig(blockingAudioSource(), 48000, 4) })
        val scenario = launchFragmentInContainer<RecordFragment>(themeResId = R.style.Theme_WavRecorder)
        boundService.startRecording(1L)

        val deadline = System.currentTimeMillis() + 2000
        while (!boundService.isRecording && System.currentTimeMillis() < deadline) Thread.sleep(5)
        shadowOf(android.os.Looper.getMainLooper()).idle()

        var buttonColor = 0
        var errorColor = 0
        var buttonText: String? = null
        scenario.onFragment { fragment ->
            val button = fragment.view!!.findViewById<Button>(R.id.recordButton)
            buttonColor = button.backgroundTintList?.defaultColor ?: 0
            errorColor = MaterialColors.getColor(button, com.google.android.material.R.attr.colorError)
            buttonText = button.text.toString()
        }

        assertEquals(app().getString(R.string.record_action_stop), buttonText)
        assertEquals(
            "the record button must use the theme's own colorError once it represents Stop recording",
            errorColor, buttonColor
        )
        boundService.stopRecording()
    }

    @Test
    fun `the record button reverts to its normal color once recording stops`() {
        val scenario = launchFragmentInContainer<RecordFragment>(themeResId = R.style.Theme_WavRecorder)

        // Captured before recording ever starts, from the button's own real (untouched) tint --
        // queried for the explicit enabled state so this doesn't depend on which entry a
        // ColorStateList happens to treat as its ambient "default" (MaterialButton's own built-in
        // selector may order disabled-vs-enabled differently than the app's own, purpose-built
        // record_button_stop_* selectors do).
        var idleColorBefore = -1
        scenario.onFragment { fragment ->
            val button = fragment.view!!.findViewById<Button>(R.id.recordButton)
            idleColorBefore = button.backgroundTintList
                ?.getColorForState(intArrayOf(android.R.attr.state_enabled), -1) ?: -1
        }

        boundService.recorder = WavRecorder(startup = ImmediateStartup, openAudioSource = { WavRecorder.RecorderConfig(blockingAudioSource(), 48000, 4) })
        boundService.startRecording(1L)
        val startDeadline = System.currentTimeMillis() + 2000
        while (!boundService.isRecording && System.currentTimeMillis() < startDeadline) Thread.sleep(5)
        shadowOf(android.os.Looper.getMainLooper()).idle()

        boundService.stopRecording()
        // isRecording flips false as soon as requestStop() runs, but beginAsyncFinalize()'s own
        // background join (and the resetToIdle() it eventually triggers via onStopped) is still
        // running until isFinalizing also clears -- mirrors RecordFragmentMicStatusTest's identical
        // awaitFinalizationAndIdle helper. Idles periodically during the wait (not just once after)
        // since finalizingRequestId itself is only cleared from inside a posted Handler runnable.
        val stopDeadline = System.currentTimeMillis() + 5000
        while (boundService.isFinalizing && System.currentTimeMillis() < stopDeadline) {
            Thread.sleep(5)
            shadowOf(android.os.Looper.getMainLooper()).idle()
        }
        repeat(20) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(2)
        }

        var buttonColorAfter = -2
        var buttonText: String? = null
        scenario.onFragment { fragment ->
            val button = fragment.view!!.findViewById<Button>(R.id.recordButton)
            buttonColorAfter = button.backgroundTintList
                ?.getColorForState(intArrayOf(android.R.attr.state_enabled), -2) ?: -2
            buttonText = button.text.toString()
        }

        assertEquals(app().getString(R.string.record_action_start), buttonText)
        assertEquals(
            "the record button's background must be fully restored to its original (non-error) tint once idle again",
            idleColorBefore, buttonColorAfter
        )
    }

    @Test
    fun `the microphone-test button never uses the destructive stop-recording color`() {
        val scenario = launchFragmentInContainer<RecordFragment>(themeResId = R.style.Theme_WavRecorder)
        val session = MicTestSession(startup = ImmediateStartup, openAudioSource = { WavRecorder.RecorderConfig(blockingAudioSource(), 48000, 4) })

        var testButtonColor: android.content.res.ColorStateList? = null
        var errorColor = 0
        scenario.onFragment { fragment ->
            fragment.micTestSession = session
            val testButton = fragment.view!!.findViewById<Button>(R.id.testMicButton)
            testButton.performClick()
            testButtonColor = testButton.backgroundTintList
            errorColor = MaterialColors.getColor(testButton, com.google.android.material.R.attr.colorError)
        }

        assertNotEquals(
            "the microphone-test button must never be accidentally styled as the real recording stop button",
            errorColor, testButtonColor?.defaultColor ?: -1
        )
        session.stop()
    }
}
