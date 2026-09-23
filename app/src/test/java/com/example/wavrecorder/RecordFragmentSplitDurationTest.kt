package com.example.wavrecorder

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Looper
import android.view.View
import androidx.fragment.app.testing.FragmentScenario
import androidx.fragment.app.testing.launchFragmentInContainer
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.button.MaterialButtonToggleGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.AudioDeviceInfoBuilder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The Record screen's split-duration toggle: it reflects and persists the user's choice, and the
 * value handed to [RecordingService] is the one selected at the moment Record was pressed -- a
 * later change only ever applies to the next recording.
 */
@RunWith(RobolectricTestRunner::class)
class RecordFragmentSplitDurationTest {

    private fun app(): Application = ApplicationProvider.getApplicationContext()

    private lateinit var boundService: RecordingService

    @Before
    fun setUp() {
        shadowOf(app()).grantPermissions(Manifest.permission.RECORD_AUDIO)
        shadowOf(app()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        // An external input is attached so Record starts straight away, without the
        // no-external-microphone confirmation dialog in between.
        val audioManager = app().getSystemService(AudioManager::class.java)
        shadowOf(audioManager).setInputDevices(
            listOf(AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_USB_DEVICE).build())
        )
        boundService = Robolectric.buildService(RecordingService::class.java).create().get()
        boundService.recorder = WavRecorder(
            openAudioSource = {
                val stopped = CountDownLatch(1)
                val source = object : AudioSource {
                    override fun startRecording() {}
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        stopped.await(5, TimeUnit.SECONDS); return -1
                    }
                    override fun stop() { stopped.countDown() }
                    override fun release() {}
                    // Matches the external device above, so the post-start route check doesn't
                    // stop the session right back out again.
                    override fun describeMicrophone() =
                        MicrophoneInfo(label = "USB Mic", isExternal = true, verified = true)
                }
                WavRecorder.RecorderConfig(source, sampleRate = 48000, bufferSize = 4)
            }
        )
        shadowOf(app()).setComponentNameAndServiceForBindService(
            ComponentName(app(), RecordingService::class.java),
            boundService.LocalBinder()
        )
    }

    private fun launch(): FragmentScenario<RecordFragment> =
        launchFragmentInContainer<RecordFragment>(themeResId = R.style.Theme_WavRecorder)

    private fun checkedButtonId(scenario: FragmentScenario<RecordFragment>): Int {
        var id = View.NO_ID
        scenario.onFragment { fragment ->
            id = fragment.view!!.findViewById<MaterialButtonToggleGroup>(R.id.splitDurationToggle).checkedButtonId
        }
        return id
    }

    private fun click(scenario: FragmentScenario<RecordFragment>, viewId: Int) {
        scenario.onFragment { fragment -> fragment.view!!.findViewById<View>(viewId).performClick() }
    }

    private fun savedSetting(): RecordingSplitDuration = RecordingSettings(app()).splitDuration

    private fun awaitStopped() {
        boundService.stopRecording()
        val deadline = System.currentTimeMillis() + 5000
        while (boundService.isFinalizing && System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun `60 minutes is selected by default`() {
        val scenario = launch()
        assertEquals(R.id.split60Button, checkedButtonId(scenario))
        assertEquals(RecordingSplitDuration.MINUTES_60, savedSetting())
    }

    @Test
    fun `each choice is persisted and shown again when the screen is reopened`() {
        mapOf(
            R.id.split30Button to RecordingSplitDuration.MINUTES_30,
            R.id.split45Button to RecordingSplitDuration.MINUTES_45,
            R.id.split60Button to RecordingSplitDuration.MINUTES_60
        ).forEach { (buttonId, expected) ->
            val scenario = launch()
            click(scenario, buttonId)
            assertEquals(expected, savedSetting())
            scenario.close()

            // A brand-new Fragment reads the choice back from storage, as after an app restart.
            val reopened = launch()
            assertEquals(buttonId, checkedButtonId(reopened))
            reopened.close()
        }
    }

    @Test
    fun `tapping the already-selected choice keeps it selected`() {
        val scenario = launch()
        click(scenario, R.id.split60Button)
        assertEquals(R.id.split60Button, checkedButtonId(scenario))
        assertEquals(RecordingSplitDuration.MINUTES_60, savedSetting())
    }

    @Test
    fun `record passes the selected duration, and changing it mid-recording does not affect that session`() {
        val scenario = launch()
        click(scenario, R.id.split45Button)
        click(scenario, R.id.recordButton)
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue("expected recording to have started", boundService.isRecording)
        assertEquals(RecordingSplitDuration.MINUTES_45, boundService.sessionSplitDuration)

        // The toggle stays usable while recording; the change is saved for next time only.
        click(scenario, R.id.split30Button)
        assertEquals(R.id.split30Button, checkedButtonId(scenario))
        assertEquals(RecordingSplitDuration.MINUTES_30, savedSetting())
        assertTrue(boundService.isRecording)
        assertEquals("the running session must keep the value it started with",
            RecordingSplitDuration.MINUTES_45, boundService.sessionSplitDuration)

        awaitStopped()

        // The next recording picks up the new choice.
        click(scenario, R.id.recordButton)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(boundService.isRecording)
        assertEquals(RecordingSplitDuration.MINUTES_30, boundService.sessionSplitDuration)
        awaitStopped()
    }

    @Test
    fun `a start that waits for the service connection uses the value captured when Record was pressed`() {
        val scenario = launch()
        var fragment: RecordFragment? = null
        scenario.onFragment { fragment = it }
        val theFragment = requireNotNull(fragment)

        // Stands in for beginRecording() having run before the connection was ready, with 30
        // minutes selected at that moment -- and the user switching to 60 before it lands.
        RecordingSettings(app()).splitDuration = RecordingSplitDuration.MINUTES_60
        theFragment.pendingStart = true
        theFragment.pendingRequestId = 1_000L
        theFragment.pendingSplitDuration = RecordingSplitDuration.MINUTES_30
        theFragment.serviceConnection.onServiceConnected(
            ComponentName(app(), RecordingService::class.java), boundService.LocalBinder()
        )

        assertTrue(boundService.isRecording)
        assertEquals(RecordingSplitDuration.MINUTES_30, boundService.sessionSplitDuration)
        awaitStopped()
    }
}
