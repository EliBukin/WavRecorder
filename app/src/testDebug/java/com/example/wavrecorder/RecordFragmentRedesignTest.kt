package com.example.wavrecorder

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.testing.FragmentScenario
import androidx.fragment.app.testing.launchFragmentInContainer
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.AudioDeviceInfoBuilder
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The redesigned Record screen's presentation: one panel state at a time (idle / testing /
 * recording / finalizing), a lifecycle-safe elapsed-time display driven by the service's real
 * session start, the session-pinned split duration, and the controls' mutual exclusion.
 */
@RunWith(RobolectricTestRunner::class)
class RecordFragmentRedesignTest {

    private fun app(): Application = ApplicationProvider.getApplicationContext()
    private lateinit var service: RecordingService
    private val releaseSources = mutableListOf<CountDownLatch>()

    @Before
    fun setUp() {
        shadowOf(app()).grantPermissions(Manifest.permission.RECORD_AUDIO)
        shadowOf(app()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        // An external input is attached (and the fake verifies as it), so Record starts directly.
        shadowOf(app().getSystemService(AudioManager::class.java)).setInputDevices(
            listOf(AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_USB_DEVICE).build())
        )
        service = Robolectric.buildService(RecordingService::class.java).create().get()
        service.recorder = WavRecorder(startup = ImmediateStartup, openAudioSource = {
            val stopped = CountDownLatch(1).also { releaseSources += it }
            val source = object : AudioSource {
                override fun startRecording() {}
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    stopped.await(5, TimeUnit.SECONDS); return -1
                }
                override fun stop() { stopped.countDown() }
                override fun release() {}
                override fun describeMicrophone() = MicrophoneInfo("USB Mic", isExternal = true, verified = true)
            }
            WavRecorder.RecorderConfig(source, sampleRate = 48000, bufferSize = 4)
        })
        shadowOf(app()).setComponentNameAndServiceForBindService(
            ComponentName(app(), RecordingService::class.java), service.LocalBinder()
        )
    }

    @After
    fun tearDown() {
        releaseSources.forEach { it.countDown() }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun launch() = launchFragmentInContainer<RecordFragment>(themeResId = R.style.Theme_WavRecorder)

    private fun <T : View> FragmentScenario<RecordFragment>.view(id: Int): T {
        var v: T? = null
        onFragment { v = it.requireView().findViewById(id) }
        return v!!
    }

    private fun FragmentScenario<RecordFragment>.fragment(): RecordFragment {
        var f: RecordFragment? = null
        onFragment { f = it }
        return f!!
    }

    private fun FragmentScenario<RecordFragment>.text(id: Int) = view<TextView>(id).text.toString()

    private fun startRecording(scenario: FragmentScenario<RecordFragment>) {
        scenario.onFragment { it.requireView().findViewById<View>(R.id.recordButton).performClick() }
        val deadline = System.currentTimeMillis() + 3000
        while (scenario.fragment().panelState != RecordFragment.PanelState.RECORDING &&
            System.currentTimeMillis() < deadline) {
            Thread.sleep(5); idle()
        }
        assertEquals(RecordFragment.PanelState.RECORDING, scenario.fragment().panelState)
    }

    private fun stopAndAwait() {
        service.stopRecording()
        val deadline = System.currentTimeMillis() + 5000
        while (service.isFinalizing && System.currentTimeMillis() < deadline) { Thread.sleep(5); idle() }
        idle()
    }

    @Test
    fun `idle is calm - ready to record, no recording details, test offered`() {
        val scenario = launch()
        assertEquals(RecordFragment.PanelState.IDLE, scenario.fragment().panelState)
        assertEquals(app().getString(R.string.status_idle), scenario.text(R.id.statusText))
        listOf(R.id.elapsedText, R.id.sessionDetailsText, R.id.audioFormatText, R.id.micTestNoticeText,
            R.id.audioLevelSection).forEach {
            assertEquals(View.GONE, scenario.view<View>(it).visibility)
        }
        assertEquals(View.VISIBLE, scenario.view<View>(R.id.testMicButton).visibility)
        assertEquals(app().getString(R.string.record_action_start), scenario.text(R.id.recordButton))
        assertFalse(scenario.fragment().isElapsedTickerRunning)
    }

    @Test
    fun `recording shows elapsed time, part, pinned split and format, with Stop and save as the one action`() {
        RecordingSettings(app()).splitDuration = RecordingSplitDuration.MINUTES_45
        val scenario = launch()
        startRecording(scenario)

        assertEquals(View.VISIBLE, scenario.view<View>(R.id.elapsedText).visibility)
        assertEquals(app().getString(R.string.session_details, 1, 45), scenario.text(R.id.sessionDetailsText))
        // The verified device and the format actually being written.
        assertEquals("USB Mic • 48\u00A0kHz • 16-bit • Mono", scenario.text(R.id.audioFormatText))
        assertEquals("a native capture needs no note", View.GONE, scenario.view<View>(R.id.formatNoteText).visibility)
        assertEquals(View.VISIBLE, scenario.view<View>(R.id.audioLevelSection).visibility)
        assertEquals(app().getString(R.string.record_action_stop), scenario.text(R.id.recordButton))
        assertEquals("the test action isn't offered while recording", View.GONE,
            scenario.view<View>(R.id.testMicButton).visibility)
        assertFalse(scenario.view<View>(R.id.testMicButton).isEnabled)

        // Changing the setting mid-recording is saved for next time but never re-labels this session.
        scenario.onFragment { it.requireView().findViewById<View>(R.id.split30Button).performClick() }
        idle()
        assertEquals(RecordingSplitDuration.MINUTES_30, RecordingSettings(app()).splitDuration)
        assertEquals(app().getString(R.string.session_details, 1, 45), scenario.text(R.id.sessionDetailsText))
        stopAndAwait()
    }

    @Test
    fun `elapsed time is computed from the service's real session start`() {
        val scenario = launch()
        startRecording(scenario)
        val startedAt = service.lastSessionStartedAtMillis!!
        scenario.onFragment { it.clock = { startedAt + 754_000 } }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_100))

        assertEquals("12:34", scenario.text(R.id.elapsedText))
        assertEquals(
            app().getString(R.string.elapsed_time_description, DurationFormatter.format(app(), 754)),
            scenario.view<View>(R.id.elapsedText).contentDescription.toString()
        )
        stopAndAwait()
    }

    @Test
    fun `the timer pauses with the screen, resumes with it, and stops for good with the view`() {
        val scenario = launch()
        startRecording(scenario)
        val fragment = scenario.fragment()
        val startedAt = service.lastSessionStartedAtMillis!!
        assertTrue(fragment.isElapsedTickerRunning)

        scenario.moveToState(Lifecycle.State.STARTED) // paused, e.g. the Library page in front
        assertFalse(fragment.isElapsedTickerRunning)
        fragment.clock = { startedAt + 65_000 }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertFalse("no tick may run while paused", scenario.text(R.id.elapsedText) == "01:05")

        scenario.moveToState(Lifecycle.State.RESUMED)
        assertTrue(fragment.isElapsedTickerRunning)
        assertEquals("resuming shows the real elapsed time immediately", "01:05", scenario.text(R.id.elapsedText))

        scenario.moveToState(Lifecycle.State.DESTROYED)
        assertFalse("the view is gone, so nothing may keep ticking", fragment.isElapsedTickerRunning)
        stopAndAwait()
    }

    @Test
    fun `stopping ends the timer and returns the panel to idle`() {
        val scenario = launch()
        startRecording(scenario)
        stopAndAwait()
        assertEquals(RecordFragment.PanelState.IDLE, scenario.fragment().panelState)
        assertFalse(scenario.fragment().isElapsedTickerRunning)
        assertEquals(View.GONE, scenario.view<View>(R.id.elapsedText).visibility)
        assertEquals(View.VISIBLE, scenario.view<View>(R.id.testMicButton).visibility)
        assertEquals(app().getString(R.string.record_action_start), scenario.text(R.id.recordButton))
    }

    @Test
    fun `a recreated screen picks the session up where it is, not from zero`() {
        val scenario = launch()
        startRecording(scenario)
        val startedAt = service.lastSessionStartedAtMillis!!

        scenario.recreate()
        val deadline = System.currentTimeMillis() + 3000
        while (scenario.fragment().panelState != RecordFragment.PanelState.RECORDING &&
            System.currentTimeMillis() < deadline) {
            Thread.sleep(5); idle()
        }
        val recreated = scenario.fragment()
        assertEquals(RecordFragment.PanelState.RECORDING, recreated.panelState)
        recreated.clock = { startedAt + 3_725_000 }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_100))
        assertEquals("1:02:05", scenario.text(R.id.elapsedText))
        assertEquals(app().getString(R.string.record_action_stop), scenario.text(R.id.recordButton))
        stopAndAwait()
    }

    @Test
    fun `testing says nothing is saved, offers Stop test, and keeps recording unavailable`() {
        val scenario = launch()
        scenario.onFragment { fragment ->
            fragment.micTestSession = MicTestSession(startup = ImmediateStartup, openAudioSource = {
                val stopped = CountDownLatch(1).also { releaseSources += it }
                WavRecorder.RecorderConfig(object : AudioSource {
                    override fun startRecording() {}
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        stopped.await(5, TimeUnit.SECONDS); return -1
                    }
                    override fun stop() { stopped.countDown() }
                    override fun release() {}
                    override fun describeMicrophone() = MicrophoneInfo("USB Mic", isExternal = true, verified = true)
                }, 48000, 4)
            })
            fragment.requireView().findViewById<View>(R.id.testMicButton).performClick()
        }
        idle()

        assertEquals(RecordFragment.PanelState.TESTING, scenario.fragment().panelState)
        assertEquals(app().getString(R.string.mic_test_status_active), scenario.text(R.id.statusText))
        assertEquals(View.VISIBLE, scenario.view<View>(R.id.micTestNoticeText).visibility)
        assertEquals(app().getString(R.string.mic_test_nothing_saved), scenario.text(R.id.micTestNoticeText))
        assertEquals(View.VISIBLE, scenario.view<View>(R.id.audioLevelSection).visibility)
        assertEquals(app().getString(R.string.stop_test), scenario.view<Button>(R.id.testMicButton).text.toString())
        assertFalse("recording stays unavailable while testing", scenario.view<View>(R.id.recordButton).isEnabled)
        assertEquals(View.GONE, scenario.view<View>(R.id.elapsedText).visibility)

        scenario.onFragment { it.requireView().findViewById<View>(R.id.testMicButton).performClick() }
        idle()
        assertEquals(RecordFragment.PanelState.IDLE, scenario.fragment().panelState)
        assertEquals(View.GONE, scenario.view<View>(R.id.micTestNoticeText).visibility)
        assertTrue(scenario.view<View>(R.id.recordButton).isEnabled)
    }

    @Test
    fun `status and device changes are announced and controls are labelled`() {
        val scenario = launch()
        assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, scenario.view<View>(R.id.statusText).accessibilityLiveRegion)
        assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, scenario.view<View>(R.id.micStatusTitle).accessibilityLiveRegion)
        assertTrue("the input device card is read as one unit", scenario.view<View>(R.id.micCard).isFocusable)
        assertNotNull(scenario.text(R.id.micStatusBadge).takeIf { it.isNotBlank() })
        assertEquals(app().getString(R.string.choose_folder_description),
            scenario.view<View>(R.id.chooseFolderButton).contentDescription.toString())
        assertEquals("the state dot is decorative next to its text", View.IMPORTANT_FOR_ACCESSIBILITY_NO,
            scenario.view<View>(R.id.recordingStateDot).importantForAccessibility)
    }
}
