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
import androidx.fragment.app.testing.launchFragmentInContainer
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.AudioDeviceInfoBuilder

/**
 * The Record screen while recording or a microphone test is still STARTING (negotiating its
 * microphone off the main thread): it says so, lets the user cancel, and a start cancelled from
 * here -- by a tap, or by leaving the screen during a test -- never becomes active later.
 */
@RunWith(RobolectricTestRunner::class)
class RecordFragmentStartupTest {

    private fun app(): Application = ApplicationProvider.getApplicationContext()

    private lateinit var service: RecordingService
    private val worker = ManualExecutor()
    private val deliver = ManualExecutor()

    private class Source : AudioSource {
        var starts = 0
        var releases = 0
        override fun startRecording() { starts++ }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = 0
        override fun stop() {}
        override fun release() { releases++ }
        override fun describeMicrophone() = MicrophoneInfo("USB Mic", isExternal = true, verified = true)
    }

    @Before
    fun setUp() {
        shadowOf(app()).grantPermissions(Manifest.permission.RECORD_AUDIO)
        shadowOf(app()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        // An external microphone is attached, so Record goes straight to starting (no "no external
        // mic" confirmation) and the start requires it.
        shadowOf(app().getSystemService(AudioManager::class.java)).setInputDevices(
            listOf(AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_USB_DEVICE).build())
        )
        service = Robolectric.buildService(RecordingService::class.java).create().get()
        shadowOf(app()).setComponentNameAndServiceForBindService(
            ComponentName(app(), RecordingService::class.java), service.LocalBinder()
        )
    }

    private fun settle() {
        while (worker.pending > 0 || deliver.pending > 0) {
            worker.runAll()
            deliver.runAll()
        }
    }

    private fun awaitFinalizationAndIdle() {
        val deadline = System.currentTimeMillis() + 3000
        while (service.isFinalizing && System.currentTimeMillis() < deadline) Thread.sleep(5)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun recordingCount(): Int = DestinationManager(app()).listRecordings().size

    @Test
    fun `while recording is starting the screen says so, and the record button cancels it`() {
        val source = Source()
        service.recorder = WavRecorder(startup = AudioStartup(worker, deliver), openAudioSource = { WavRecorder.RecorderConfig(source, 48000, 4096) })
        val before = recordingCount()
        val scenario = launchFragmentInContainer<RecordFragment>(themeResId = R.style.Theme_WavRecorder)

        scenario.onFragment { it.requireView().findViewById<View>(R.id.recordButton).performClick() }
        scenario.onFragment { fragment ->
            val view = fragment.requireView()
            assertTrue(service.isStarting)
            assertEquals(app().getString(R.string.status_preparing), view.findViewById<TextView>(R.id.statusText).text.toString())
            val record = view.findViewById<Button>(R.id.recordButton)
            assertEquals(app().getString(R.string.record_action_stop), record.text.toString())
            assertTrue(record.isEnabled)
            assertFalse("no microphone test while a recording starts", view.findViewById<View>(R.id.testMicButton).isEnabled)
            record.performClick() // cancel: no confirmation, nothing was recorded
        }
        awaitFinalizationAndIdle()
        settle()
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(service.isRecording)
        assertFalse(service.isStarting)
        assertEquals("the cancelled start never opened the microphone", 0, source.starts)
        assertEquals(before, recordingCount())
        scenario.onFragment { fragment ->
            assertEquals(app().getString(R.string.status_idle),
                fragment.requireView().findViewById<TextView>(R.id.statusText).text.toString())
        }
    }

    @Test
    fun `a recording start that completes later is shown as recording`() {
        service.recorder = WavRecorder(startup = AudioStartup(worker, deliver), openAudioSource = { WavRecorder.RecorderConfig(Source(), 48000, 4096) })
        val scenario = launchFragmentInContainer<RecordFragment>(themeResId = R.style.Theme_WavRecorder)

        scenario.onFragment { it.requireView().findViewById<View>(R.id.recordButton).performClick() }
        settle()
        val deadline = System.currentTimeMillis() + 3000
        var panel = RecordFragment.PanelState.IDLE
        while (panel != RecordFragment.PanelState.RECORDING && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            scenario.onFragment { panel = it.panelState }
            Thread.sleep(5)
        }

        assertEquals(RecordFragment.PanelState.RECORDING, panel)
        assertTrue(service.isRecording)
        service.stopRecording()
        awaitFinalizationAndIdle()
    }

    @Test
    fun `returning to the screen while recording is still starting shows it as starting`() {
        service.recorder = WavRecorder(startup = AudioStartup(worker, deliver), openAudioSource = { WavRecorder.RecorderConfig(Source(), 48000, 4096) })
        val scenario = launchFragmentInContainer<RecordFragment>(themeResId = R.style.Theme_WavRecorder)
        scenario.onFragment { it.requireView().findViewById<View>(R.id.recordButton).performClick() }

        scenario.recreate()
        shadowOf(Looper.getMainLooper()).idle()

        scenario.onFragment { fragment ->
            assertEquals(app().getString(R.string.status_preparing),
                fragment.requireView().findViewById<TextView>(R.id.statusText).text.toString())
        }
        settle()
        service.stopRecording()
        awaitFinalizationAndIdle()
    }

    @Test
    fun `a microphone test stopped while it is starting never starts later`() {
        val source = Source()
        val session = MicTestSession(startup = AudioStartup(worker, deliver), openAudioSource = { WavRecorder.RecorderConfig(source, 48000, 4096) })
        val scenario = launchFragmentInContainer<RecordFragment>(themeResId = R.style.Theme_WavRecorder)

        scenario.onFragment { fragment ->
            fragment.micTestSession = session
            val test = fragment.requireView().findViewById<Button>(R.id.testMicButton)
            test.performClick()
            assertTrue(session.starting)
            assertEquals(app().getString(R.string.stop_test), test.text.toString())
            worker.runAll() // its microphone is open, on its way
            test.performClick() // Stop test
        }
        settle()
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(session.active)
        assertFalse(session.starting)
        assertEquals(1, source.releases)
        scenario.onFragment { fragment ->
            assertEquals(app().getString(R.string.test_microphone),
                fragment.requireView().findViewById<Button>(R.id.testMicButton).text.toString())
            assertEquals(RecordFragment.PanelState.IDLE, fragment.panelState)
        }
    }

    @Test
    fun `leaving the screen while a microphone test is starting cancels it for good`() {
        val source = Source()
        val session = MicTestSession(startup = AudioStartup(worker, deliver), openAudioSource = { WavRecorder.RecorderConfig(source, 48000, 4096) })
        val scenario = launchFragmentInContainer<RecordFragment>(themeResId = R.style.Theme_WavRecorder)
        scenario.onFragment { fragment ->
            fragment.micTestSession = session
            fragment.requireView().findViewById<View>(R.id.testMicButton).performClick()
        }

        scenario.moveToState(Lifecycle.State.CREATED)
        settle()
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(session.active)
        assertEquals("cancelled before it opened anything", 0, source.starts)
    }

    @Test
    fun `a microphone test that finishes starting shows its verified microphone and format`() {
        val session = MicTestSession(startup = AudioStartup(worker, deliver), openAudioSource = { WavRecorder.RecorderConfig(Source(), 48000, 4096) })
        val scenario = launchFragmentInContainer<RecordFragment>(themeResId = R.style.Theme_WavRecorder)
        scenario.onFragment { fragment ->
            fragment.micTestSession = session
            fragment.requireView().findViewById<View>(R.id.testMicButton).performClick()
            assertEquals(View.GONE, fragment.requireView().findViewById<View>(R.id.audioFormatText).visibility)
        }

        settle()

        scenario.onFragment { fragment ->
            assertTrue(session.active)
            val line = fragment.requireView().findViewById<TextView>(R.id.audioFormatText)
            assertEquals(View.VISIBLE, line.visibility)
            assertEquals("USB Mic • 48 kHz • 16-bit • Mono", line.text.toString())
        }
        session.stop()
    }
}
