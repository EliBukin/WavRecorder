package com.example.wavrecorder

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Looper
import android.view.View
import android.widget.Button
import androidx.fragment.app.testing.launchFragmentInContainer
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.AudioDeviceInfoBuilder
import org.robolectric.shadows.ShadowToast
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Covers the live microphone test button on [RecordFragment]: it must never touch a file, the
 * crash-recovery journal, or [RecordingService] (no foreground-service start, no notification),
 * must request only RECORD_AUDIO (never POST_NOTIFICATIONS, which real recording alone needs),
 * and must stay mutually exclusive with real recording in both directions. [MicTestSessionTest]
 * already covers [MicTestSession]'s own start/stop/route-verification/generation behavior
 * directly; this covers the Fragment-level wiring around it.
 */
@RunWith(RobolectricTestRunner::class)
class RecordFragmentMicTestTest {

    private fun app(): Application = ApplicationProvider.getApplicationContext()

    // Bound unconditionally in setUp(), like RecordFragmentPermissionTest's identical setup:
    // RecordFragment always binds in onStart() regardless of what a given test actually needs, and
    // an unregistered bindService() call still delivers onServiceConnected with a null binder in
    // Robolectric, crashing the fragment's own cast -- every test here needs a real target.
    private lateinit var boundService: RecordingService

    @Before
    fun setUp() {
        shadowOf(app()).grantPermissions(Manifest.permission.RECORD_AUDIO)
        shadowOf(app()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        // Keeps the "no external mic detected" confirmation dialog out of these tests' way,
        // mirroring RecordFragmentPermissionTest's identical setup rationale.
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

    private fun awaitInactiveAndDeliverCallbacks(session: MicTestSession, timeoutMs: Long = 2000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (session.active && System.currentTimeMillis() < deadline) Thread.sleep(5)
        repeat(20) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(2)
        }
    }

    private fun fakeSession(fake: AudioSource) = MicTestSession(
        openAudioSource = { WavRecorder.RecorderConfig(fake, 48000, 4) }
    )

    // Reports itself as a verified external mic so it matches the external input device setUp()
    // registers below -- otherwise RecordFragment's own post-start mic-route mismatch check (a
    // separately-covered fix; see RecordFragmentMicTestMismatchTest) would immediately stop a test
    // using this fake right back out again, before whatever this file's own test is actually
    // trying to exercise ever gets a chance to run.
    private fun blockingSource(): AudioSource = object : AudioSource {
        override fun startRecording() {}
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            Thread.sleep(20)
            return 4
        }
        override fun stop() {}
        override fun release() {}
        override fun describeMicrophone(): MicrophoneInfo =
            MicrophoneInfo(label = "USB Mic", isExternal = true, verified = true)
    }

    @Test
    fun `tapping Test microphone starts a test that never starts the foreground service or creates a journal entry`() {
        val scenario = launchFragmentInContainer<RecordFragment>(themeResId = R.style.Theme_WavRecorder)
        shadowOf(app()).nextStartedService // drain the bindService() from onStart()
        scenario.onFragment { fragment ->
            fragment.micTestSession = fakeSession(blockingSource())
            fragment.view!!.findViewById<Button>(R.id.testMicButton).performClick()
        }
        shadowOf(Looper.getMainLooper()).idle()

        assertNull(
            "starting a microphone test must never start RecordingService (no foreground service, no notification)",
            shadowOf(app()).nextStartedService
        )
        assertNull(
            "a microphone test must never create a crash-recovery journal entry",
            ActiveSegmentJournal(ApplicationProvider.getApplicationContext()).peekActive()
        )
        var buttonText: String? = null
        scenario.onFragment { fragment ->
            buttonText = fragment.view!!.findViewById<Button>(R.id.testMicButton).text.toString()
            fragment.micTestSession.stop()
        }
        assertEquals(app().getString(R.string.stop_test), buttonText)
    }

    @Test
    fun `permission denial shows the mic-test-specific message and never starts a test`() {
        shadowOf(app()).denyPermissions(Manifest.permission.RECORD_AUDIO)
        val scenario = launchFragmentInContainer<RecordFragment>(themeResId = R.style.Theme_WavRecorder)
        shadowOf(app()).nextStartedService
        scenario.onFragment { fragment ->
            fragment.view!!.findViewById<Button>(R.id.testMicButton).performClick()
        }

        // Only RECORD_AUDIO should have been requested -- the test never needs POST_NOTIFICATIONS.
        scenario.onFragment { fragment ->
            fragment.handlePermissionResult(mapOf(Manifest.permission.RECORD_AUDIO to false))
        }

        assertEquals(app().getString(R.string.mic_test_permission_denied), ShadowToast.getTextOfLatestToast())
        var buttonText: String? = null
        scenario.onFragment { fragment ->
            buttonText = fragment.view!!.findViewById<Button>(R.id.testMicButton).text.toString()
        }
        assertEquals(app().getString(R.string.test_microphone), buttonText)
    }

    @Test
    fun `real recording is disabled while a microphone test is active`() {
        val service = boundService
        val scenario = launchFragmentInContainer<RecordFragment>(themeResId = R.style.Theme_WavRecorder)
        scenario.onFragment { fragment ->
            fragment.micTestSession = fakeSession(blockingSource())
            fragment.view!!.findViewById<Button>(R.id.testMicButton).performClick()
        }
        shadowOf(Looper.getMainLooper()).idle()

        var recordButtonEnabled = true
        scenario.onFragment { fragment ->
            recordButtonEnabled = fragment.view!!.findViewById<View>(R.id.recordButton).isEnabled
        }
        assertFalse("the real record button must be disabled while a microphone test is active",
            recordButtonEnabled)
        assertFalse("real recording must not actually be reachable while a test is active", service.isRecording)
    }

    @Test
    fun `microphone testing is disabled while a real recording is preparing`() {
        val scenario = launchFragmentInContainer<RecordFragment>(themeResId = R.style.Theme_WavRecorder)
        shadowOf(app()).nextStartedService
        scenario.onFragment { fragment ->
            // Simulates a start already queued (service not yet connected) -- exactly the
            // PREPARING window recordingBusy() must also cover for the test button.
            fragment.pendingStart = true
        }

        var testButtonEnabled = true
        scenario.onFragment { fragment ->
            fragment.refreshTestButtonEnabled()
            testButtonEnabled = fragment.view!!.findViewById<Button>(R.id.testMicButton).isEnabled
        }
        assertFalse("microphone testing must be disabled while recording is preparing", testButtonEnabled)
    }

    @Test
    fun `microphone testing is disabled while genuinely recording`() {
        val service = boundService
        service.recorder = WavRecorder(
            openAudioSource = {
                val fake = object : AudioSource {
                    override fun startRecording() {}
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        Thread.sleep(50); return 4
                    }
                    override fun stop() {}
                    override fun release() {}
                }
                WavRecorder.RecorderConfig(fake, 48000, 4)
            }
        )
        val scenario = launchFragmentInContainer<RecordFragment>(themeResId = R.style.Theme_WavRecorder)
        service.startRecording(1L)
        assertTrue(service.isRecording)

        var testButtonEnabled = true
        scenario.onFragment { fragment ->
            fragment.refreshTestButtonEnabled()
            testButtonEnabled = fragment.view!!.findViewById<Button>(R.id.testMicButton).isEnabled
        }
        assertFalse("microphone testing must be disabled while genuinely recording", testButtonEnabled)
        service.stopRecording()
    }

    @Test
    fun `a disconnected microphone during the test shows an honest error and resets the button`() {
        val scenario = launchFragmentInContainer<RecordFragment>(themeResId = R.style.Theme_WavRecorder)
        val chunk = byteArrayOf(1, 2, 3, 4)
        val fake = FakeAudioSource(
            scriptedReads = List(50) { chunk },
            connected = { false },
            micInfo = MicrophoneInfo(label = "USB Mic", isExternal = true, verified = true)
        )
        val session = MicTestSession(
            openAudioSource = { WavRecorder.RecorderConfig(fake, 48000, 4) },
            routeCheckIntervalMs = 0
        )
        scenario.onFragment { fragment ->
            fragment.micTestSession = session
            fragment.view!!.findViewById<Button>(R.id.testMicButton).performClick()
        }

        awaitInactiveAndDeliverCallbacks(session)

        assertEquals(app().getString(R.string.mic_test_disconnected_error), ShadowToast.getTextOfLatestToast())
        var buttonText: String? = null
        scenario.onFragment { fragment ->
            buttonText = fragment.view!!.findViewById<Button>(R.id.testMicButton).text.toString()
        }
        assertEquals(app().getString(R.string.test_microphone), buttonText)
    }

    @Test
    fun `a mid-test route change shows an honest error, never silently falling back to the phone mic`() {
        val scenario = launchFragmentInContainer<RecordFragment>(themeResId = R.style.Theme_WavRecorder)
        val chunk = byteArrayOf(1, 2, 3, 4)
        val fake = FakeAudioSource(
            scriptedReads = List(50) { chunk },
            routeUnchanged = { false },
            micInfo = MicrophoneInfo(label = "USB Mic", isExternal = true, verified = true)
        )
        val session = MicTestSession(
            openAudioSource = { WavRecorder.RecorderConfig(fake, 48000, 4) },
            routeCheckIntervalMs = 0
        )
        scenario.onFragment { fragment ->
            fragment.micTestSession = session
            fragment.view!!.findViewById<Button>(R.id.testMicButton).performClick()
        }

        awaitInactiveAndDeliverCallbacks(session)

        assertEquals(app().getString(R.string.mic_test_route_changed_error), ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun `leaving the screen stops an active microphone test`() {
        val scenario = launchFragmentInContainer<RecordFragment>(themeResId = R.style.Theme_WavRecorder)
        var stopCalled = false
        val fake = object : AudioSource {
            override fun startRecording() {}
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int { Thread.sleep(20); return 4 }
            override fun stop() { stopCalled = true }
            override fun release() {}
            override fun describeMicrophone(): MicrophoneInfo =
                MicrophoneInfo(label = "USB Mic", isExternal = true, verified = true)
        }
        val session = MicTestSession(openAudioSource = { WavRecorder.RecorderConfig(fake, 48000, 4) })
        scenario.onFragment { fragment ->
            fragment.micTestSession = session
            fragment.view!!.findViewById<Button>(R.id.testMicButton).performClick()
        }
        assertTrue(session.active)

        scenario.moveToState(Lifecycle.State.CREATED) // triggers onStop()

        assertFalse("the test must not keep running once this screen is no longer visible", session.active)
        assertTrue(stopCalled)
    }

    @Test
    fun `tapping Stop test toggles the button back and stops the session`() {
        val scenario = launchFragmentInContainer<RecordFragment>(themeResId = R.style.Theme_WavRecorder)
        val session = MicTestSession(openAudioSource = { WavRecorder.RecorderConfig(blockingSource(), 48000, 4) })
        scenario.onFragment { fragment ->
            fragment.micTestSession = session
            fragment.view!!.findViewById<Button>(R.id.testMicButton).performClick()
        }
        assertTrue(session.active)

        scenario.onFragment { fragment ->
            fragment.view!!.findViewById<Button>(R.id.testMicButton).performClick()
        }

        assertFalse(session.active)
        var buttonText: String? = null
        var recordButtonEnabled = false
        scenario.onFragment { fragment ->
            buttonText = fragment.view!!.findViewById<Button>(R.id.testMicButton).text.toString()
            recordButtonEnabled = fragment.view!!.findViewById<View>(R.id.recordButton).isEnabled
        }
        assertEquals(app().getString(R.string.test_microphone), buttonText)
        assertTrue("the real record button must be re-enabled once the test stops", recordButtonEnabled)
    }
}
