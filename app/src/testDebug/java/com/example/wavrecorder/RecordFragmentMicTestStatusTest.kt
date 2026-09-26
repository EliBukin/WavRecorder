package com.example.wavrecorder

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.testing.launchFragmentInContainer
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Covers the microphone test's own Status-section feedback: a clear "testing is active" headline,
 * a neutral "Waiting for signal…" state before any meaningful input arrives (never worded as a
 * failure), a one-way "Signal detected" transition once a modest amplitude threshold is crossed,
 * and a clean reset back to idle on both a normal stop and a mid-test error. The transition to
 * *real* recording's own status (never the mic-test idle status) is covered separately in
 * RecordFragmentServiceStateGateTest, alongside the rest of that defensive-shutdown behavior.
 */
@RunWith(RobolectricTestRunner::class)
class RecordFragmentMicTestStatusTest {

    private fun app(): Application = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        shadowOf(app()).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val service = Robolectric.buildService(RecordingService::class.java).create().get()
        shadowOf(app()).setComponentNameAndServiceForBindService(
            ComponentName(app(), RecordingService::class.java),
            service.LocalBinder()
        )
    }

    /** Loops forever returning silence (an untouched, zero-initialized buffer) -- never exhausts,
     * so the test stays active for as long as this test needs without racing a scripted-reads
     * exhaustion error. */
    private fun silentBlockingSource(): AudioSource = object : AudioSource {
        override fun startRecording() {}
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            Thread.sleep(5)
            return length
        }
        override fun stop() {}
        override fun release() {}
    }

    /** Loops forever returning a fixed, comfortably-above-threshold-loud 16-bit sample. */
    private fun loudBlockingSource(): AudioSource = object : AudioSource {
        private val chunk = byteArrayOf(0xFF.toByte(), 0x7F, 0, 0)
        override fun startRecording() {}
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            Thread.sleep(5)
            System.arraycopy(chunk, 0, buffer, offset, minOf(chunk.size, length))
            return length
        }
        override fun stop() {}
        override fun release() {}
    }

    private fun statusText(scenario: androidx.fragment.app.testing.FragmentScenario<RecordFragment>): String {
        var text = ""
        scenario.onFragment { fragment ->
            text = fragment.view!!.findViewById<TextView>(R.id.statusText).text.toString()
        }
        return text
    }

    private fun detailText(scenario: androidx.fragment.app.testing.FragmentScenario<RecordFragment>): String {
        var text = ""
        scenario.onFragment { fragment ->
            text = fragment.view!!.findViewById<TextView>(R.id.statusDetailText).text.toString()
        }
        return text
    }

    @Test
    fun `starting a test shows the active headline and a neutral waiting-for-signal status`() {
        val scenario = launchFragmentInContainer<RecordFragment>(themeResId = R.style.Theme_WavRecorder)
        val session = MicTestSession(startup = ImmediateStartup,
            openAudioSource = { WavRecorder.RecorderConfig(silentBlockingSource(), 48000, 4) },
            levelUpdateIntervalMs = 0
        )
        scenario.onFragment { fragment ->
            fragment.micTestSession = session
            fragment.view!!.findViewById<Button>(R.id.testMicButton).performClick()
        }
        Thread.sleep(30)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(app().getString(R.string.mic_test_status_active), statusText(scenario))
        assertEquals(
            "silence must read as a neutral wait, never as an immediate failure",
            app().getString(R.string.mic_test_status_waiting), detailText(scenario)
        )
        session.stop()
    }

    @Test
    fun `a level crossing the threshold shows Signal detected`() {
        val scenario = launchFragmentInContainer<RecordFragment>(themeResId = R.style.Theme_WavRecorder)
        val session = MicTestSession(startup = ImmediateStartup,
            openAudioSource = { WavRecorder.RecorderConfig(loudBlockingSource(), 48000, 4) },
            levelUpdateIntervalMs = 0
        )
        scenario.onFragment { fragment ->
            fragment.micTestSession = session
            fragment.view!!.findViewById<Button>(R.id.testMicButton).performClick()
        }
        Thread.sleep(30)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(app().getString(R.string.mic_test_status_signal_detected), detailText(scenario))
        session.stop()
    }

    @Test
    fun `stopping the test resets the status back to idle`() {
        val scenario = launchFragmentInContainer<RecordFragment>(themeResId = R.style.Theme_WavRecorder)
        val session = MicTestSession(startup = ImmediateStartup, openAudioSource = { WavRecorder.RecorderConfig(silentBlockingSource(), 48000, 4) })
        scenario.onFragment { fragment ->
            fragment.micTestSession = session
            fragment.view!!.findViewById<Button>(R.id.testMicButton).performClick()
        }

        scenario.onFragment { fragment ->
            fragment.view!!.findViewById<Button>(R.id.testMicButton).performClick() // Stop test
        }

        assertEquals(app().getString(R.string.status_idle), statusText(scenario))
        var detailVisible = true
        scenario.onFragment { fragment ->
            detailVisible = fragment.view!!.findViewById<View>(R.id.statusDetailText).visibility == View.VISIBLE
        }
        assertFalse("the waiting/signal sub-status must be hidden again once the test stops", detailVisible)
    }

    @Test
    fun `a mid-test error resets the status back to idle`() {
        val scenario = launchFragmentInContainer<RecordFragment>(themeResId = R.style.Theme_WavRecorder)
        val chunk = byteArrayOf(1, 2, 3, 4)
        val fake = FakeAudioSource(scriptedReads = List(50) { chunk }, connected = { false })
        val session = MicTestSession(startup = ImmediateStartup,
            openAudioSource = { WavRecorder.RecorderConfig(fake, 48000, 4) },
            routeCheckIntervalMs = 0
        )
        scenario.onFragment { fragment ->
            fragment.micTestSession = session
            fragment.view!!.findViewById<Button>(R.id.testMicButton).performClick()
        }

        val deadline = System.currentTimeMillis() + 2000
        while (session.active && System.currentTimeMillis() < deadline) Thread.sleep(5)
        repeat(20) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(2)
        }

        assertEquals(app().getString(R.string.status_idle), statusText(scenario))
    }
}
