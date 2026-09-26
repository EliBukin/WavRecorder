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
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
 * Covers what happens to an *active* microphone test when [RecordingService] unexpectedly
 * disconnects: unlike the known-busy-service-connects path ([stopMicTestForRecordingConflict],
 * covered by [RecordFragmentServiceStateGateTest]), nothing renders a replacement UI here --
 * `recordingService` becomes null, so the leftover "Testing microphone" status/waveform/level
 * section would otherwise stay stuck indefinitely. Also covers that a subsequent reconnection
 * (idle or busy) correctly replaces whatever this screen showed while disconnected, and that a
 * stale callback from the already-stopped session can never resurrect it.
 */
@RunWith(RobolectricTestRunner::class)
class RecordFragmentServiceDisconnectMicTestTest {

    private fun app(): Application = ApplicationProvider.getApplicationContext()
    private val componentName get() = ComponentName(app(), RecordingService::class.java)

    @Before
    fun setUp() {
        shadowOf(app()).grantPermissions(Manifest.permission.RECORD_AUDIO)
        shadowOf(app()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        // Registers an external mic so the mismatch check (a separately-covered fix; see
        // RecordFragmentMicTestMismatchTest) never fires for these fakes -- see their own
        // describeMicrophone() overrides below.
        val audioManager = app().getSystemService(AudioManager::class.java)
        shadowOf(audioManager).setInputDevices(
            listOf(AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_USB_DEVICE).build())
        )
        val service = Robolectric.buildService(RecordingService::class.java).create().get()
        shadowOf(app()).setComponentNameAndServiceForBindService(componentName, service.LocalBinder())
    }

    private fun blockingFake(): AudioSource = object : AudioSource {
        override fun startRecording() {}
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int { Thread.sleep(20); return 4 }
        override fun stop() {}
        override fun release() {}
        override fun describeMicrophone(): MicrophoneInfo =
            MicrophoneInfo(label = "USB Mic", isExternal = true, verified = true)
    }

    /** A [WavRecorder] whose AudioSource blocks in read() until stop() releases it -- mirrors
     * RecordFragmentLifecycleTest/RecordFragmentServiceStateGateTest's identical helper, lets a
     * real RECORDING state be reached under Robolectric without a real microphone. */
    private fun blockingRecorder(): WavRecorder {
        val blockForever = CountDownLatch(1)
        return WavRecorder(startup = ImmediateStartup,
            openAudioSource = {
                val source = object : AudioSource {
                    override fun startRecording() {}
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        blockForever.await(5, TimeUnit.SECONDS)
                        return -1
                    }
                    override fun stop() { blockForever.countDown() }
                    override fun release() {}
                    override fun describeMicrophone() =
                        MicrophoneInfo(label = "USB Mic", isExternal = true, verified = true)
                }
                WavRecorder.RecorderConfig(source, sampleRate = 48000, bufferSize = 4)
            }
        )
    }

    private fun statusText(fragment: RecordFragment): String =
        fragment.view!!.findViewById<TextView>(R.id.statusText).text.toString()

    @Test
    fun `an active mic test is fully stopped and its UI reset when the service unexpectedly disconnects`() {
        val scenario = launchFragmentInContainer<RecordFragment>(themeResId = R.style.Theme_WavRecorder)
        var releaseCount = 0
        val fake = object : AudioSource {
            override fun startRecording() {}
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int { Thread.sleep(20); return 4 }
            override fun stop() {}
            override fun release() { releaseCount++ }
            override fun describeMicrophone(): MicrophoneInfo =
                MicrophoneInfo(label = "USB Mic", isExternal = true, verified = true)
        }
        val session = MicTestSession(startup = ImmediateStartup, openAudioSource = { WavRecorder.RecorderConfig(fake, 48000, 4) })
        scenario.onFragment { fragment ->
            fragment.micTestSession = session
            fragment.view!!.findViewById<Button>(R.id.testMicButton).performClick()
        }
        assertTrue("sanity: the test must genuinely be active before the disconnect", session.active)

        scenario.onFragment { fragment -> fragment.serviceConnection.onServiceDisconnected(componentName) }

        assertFalse("the session must be stopped once the service disconnects", session.active)
        assertEquals("the AudioSource must be released exactly once", 1, releaseCount)

        var buttonText: String? = null
        var levelSectionVisible = true
        var testButtonEnabled = true
        var status: String? = null
        scenario.onFragment { fragment ->
            buttonText = fragment.view!!.findViewById<Button>(R.id.testMicButton).text.toString()
            levelSectionVisible =
                fragment.view!!.findViewById<View>(R.id.audioLevelSection).visibility == View.VISIBLE
            testButtonEnabled = fragment.view!!.findViewById<Button>(R.id.testMicButton).isEnabled
            status = statusText(fragment)
        }
        assertEquals(
            "must not still claim 'Stop test' once the session has actually stopped",
            app().getString(R.string.test_microphone), buttonText
        )
        assertFalse("the leftover waveform/level section must not stay visible", levelSectionVisible)
        assertFalse("mic testing must remain disabled while service state is unknown", testButtonEnabled)
        assertNotEquals(
            "must not falsely claim a known, verified idle status while the service is unreachable",
            app().getString(R.string.status_idle), status
        )
        assertEquals(app().getString(R.string.status_reconnecting), status)
    }

    @Test
    fun `a subsequent idle reconnection restores the idle UI and enables mic testing`() {
        val scenario = launchFragmentInContainer<RecordFragment>(themeResId = R.style.Theme_WavRecorder)
        val session = MicTestSession(startup = ImmediateStartup, openAudioSource = { WavRecorder.RecorderConfig(blockingFake(), 48000, 4) })
        scenario.onFragment { fragment ->
            fragment.micTestSession = session
            fragment.view!!.findViewById<Button>(R.id.testMicButton).performClick()
        }
        scenario.onFragment { fragment -> fragment.serviceConnection.onServiceDisconnected(componentName) }

        val idleService = Robolectric.buildService(RecordingService::class.java).create().get()
        scenario.onFragment { fragment ->
            fragment.serviceConnection.onServiceConnected(componentName, idleService.LocalBinder())
        }

        var status: String? = null
        var testButtonEnabled = false
        var recordButtonEnabled = false
        scenario.onFragment { fragment ->
            status = statusText(fragment)
            testButtonEnabled = fragment.view!!.findViewById<Button>(R.id.testMicButton).isEnabled
            recordButtonEnabled = fragment.view!!.findViewById<View>(R.id.recordButton).isEnabled
        }
        assertEquals(
            "a genuinely idle reconnection must replace the stale 'Reconnecting…' status",
            app().getString(R.string.status_idle), status
        )
        assertTrue("mic testing must be enabled again once idle is genuinely confirmed", testButtonEnabled)
        assertTrue(recordButtonEnabled)
    }

    @Test
    fun `a subsequent busy reconnection displays the real recording UI, not a stale mic-test reset`() {
        val scenario = launchFragmentInContainer<RecordFragment>(themeResId = R.style.Theme_WavRecorder)
        val session = MicTestSession(startup = ImmediateStartup, openAudioSource = { WavRecorder.RecorderConfig(blockingFake(), 48000, 4) })
        scenario.onFragment { fragment ->
            fragment.micTestSession = session
            fragment.view!!.findViewById<Button>(R.id.testMicButton).performClick()
        }
        scenario.onFragment { fragment -> fragment.serviceConnection.onServiceDisconnected(componentName) }

        val busyService = Robolectric.buildService(RecordingService::class.java).create().get()
        busyService.recorder = blockingRecorder()
        busyService.startRecording(1L)
        assertTrue("sanity: the reconnecting service must genuinely be recording", busyService.isRecording)
        scenario.onFragment { fragment ->
            fragment.serviceConnection.onServiceConnected(componentName, busyService.LocalBinder())
        }

        var status: String? = null
        var levelSectionVisible = false
        var testButtonEnabled = true
        scenario.onFragment { fragment ->
            status = statusText(fragment)
            levelSectionVisible =
                fragment.view!!.findViewById<View>(R.id.audioLevelSection).visibility == View.VISIBLE
            testButtonEnabled = fragment.view!!.findViewById<Button>(R.id.testMicButton).isEnabled
        }
        assertEquals(
            "the real recording's own status must be shown, not the stale reconnecting/idle mic-test text",
            app().getString(R.string.status_recording), status
        )
        assertTrue("the audio level section must reflect the real recording, not stay hidden", levelSectionVisible)
        assertFalse("mic testing must stay disabled while a real recording is active", testButtonEnabled)
        busyService.stopRecording()
    }

    @Test
    fun `a callback from the stopped session cannot update the UI after a disconnect-triggered reset`() {
        val scenario = launchFragmentInContainer<RecordFragment>(themeResId = R.style.Theme_WavRecorder)
        val readGate = CountDownLatch(1)
        val readReleased = CountDownLatch(1)
        val fake = object : AudioSource {
            override fun startRecording() {}
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                readGate.countDown()
                readReleased.await(5, TimeUnit.SECONDS)
                // A loud sample -- if this somehow still reached the UI, it would be impossible to
                // mistake for a no-op.
                buffer[0] = -1; buffer[1] = 0x7F; buffer[2] = 0; buffer[3] = 0
                return 4
            }
            override fun stop() { readReleased.countDown() } // unblocks the pending read, like SystemAudioSource
            override fun release() {}
            override fun describeMicrophone(): MicrophoneInfo =
                MicrophoneInfo(label = "USB Mic", isExternal = true, verified = true)
        }
        val session = MicTestSession(startup = ImmediateStartup,
            openAudioSource = { WavRecorder.RecorderConfig(fake, 48000, 4) },
            levelUpdateIntervalMs = 0
        )
        scenario.onFragment { fragment ->
            fragment.micTestSession = session
            fragment.view!!.findViewById<Button>(R.id.testMicButton).performClick()
        }
        assertTrue(
            "sanity: the background thread must have actually entered its first read",
            readGate.await(2, TimeUnit.SECONDS)
        )

        scenario.onFragment { fragment -> fragment.serviceConnection.onServiceDisconnected(componentName) }
        var statusRightAfterDisconnect: String? = null
        scenario.onFragment { fragment -> statusRightAfterDisconnect = statusText(fragment) }
        assertEquals(app().getString(R.string.status_reconnecting), statusRightAfterDisconnect)

        // Now let the blocked read actually return real (loud) data -- this is exactly the race
        // MicTestSession's own generation counter must close: a read completing after stop() must
        // never still reach onLevel/onMicrophoneInfo.
        readReleased.countDown()
        Thread.sleep(50)
        shadowOf(Looper.getMainLooper()).idle()

        var statusAfter: String? = null
        var levelSectionVisible = true
        scenario.onFragment { fragment ->
            statusAfter = statusText(fragment)
            levelSectionVisible =
                fragment.view!!.findViewById<View>(R.id.audioLevelSection).visibility == View.VISIBLE
        }
        assertEquals(
            "a stale callback from the already-stopped session must never change the status again",
            app().getString(R.string.status_reconnecting), statusAfter
        )
        assertFalse("a stale callback must never re-show the level section", levelSectionVisible)
    }
}
