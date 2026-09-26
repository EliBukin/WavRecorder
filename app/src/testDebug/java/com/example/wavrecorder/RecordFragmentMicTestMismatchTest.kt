package com.example.wavrecorder

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.widget.Button
import androidx.fragment.app.testing.launchFragmentInContainer
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
 * Covers the microphone test's own route-verification policy, mirroring real recording's: an
 * external microphone detected/preferred before the test began must actually be confirmed in use
 * once [MicTestSession] verifies the real route, or the test must stop with an honest error rather
 * than silently continuing against the built-in mic. Also covers that this check -- which fires
 * from inside [MicTestSession]'s *synchronous* onMicrophoneInfo callback, before [MicTestSession.start]
 * has finished its own start transition -- never calls [MicTestSession.stop] reentrantly from
 * inside that callback (see [RecordFragment.pendingTestMicMismatch]'s doc).
 */
@RunWith(RobolectricTestRunner::class)
class RecordFragmentMicTestMismatchTest {

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

    /** Registers an external USB input device so RecordFragment's own preferred-mic status query
     * reports ExternalConnected -- this is what makes startMicTest() snapshot
     * expectedExternalMicForTest = true, arming the mismatch check. */
    private fun expectExternalMic() {
        val audioManager = app().getSystemService(AudioManager::class.java)
        shadowOf(audioManager).setInputDevices(
            listOf(AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_USB_DEVICE).build())
        )
    }

    private fun fakeReads() = List(50) { byteArrayOf(1, 2, 3, 4) }

    /** Waits until the test loop has consumed every scripted read, so an assertion that the test
     * is still running is made after it has genuinely been metering audio, not before. */
    private fun awaitAllReadsConsumed(exhausted: CountDownLatch) {
        assertTrue("the test loop never consumed its scripted reads", exhausted.await(3, TimeUnit.SECONDS))
    }

    @Test
    fun `an external mic that verifies correctly lets the test keep running`() {
        expectExternalMic()
        val scenario = launchFragmentInContainer<RecordFragment>(themeResId = R.style.Theme_WavRecorder)
        val exhausted = CountDownLatch(1)
        val fake = FakeAudioSource(
            scriptedReads = fakeReads(),
            onExhausted = { exhausted.countDown() },
            micInfo = MicrophoneInfo(label = "USB Mic", isExternal = true, verified = true),
            // Once the scripted reads are used up, the source goes quiet (0-byte reads) instead of
            // failing with end-of-stream, which would stop the test for a reason unrelated to route
            // verification -- the only thing that may stop it here (see the phone-mic test below).
            readReturnsZeroWhenExhausted = true
        )
        val session = MicTestSession(startup = ImmediateStartup, openAudioSource = { WavRecorder.RecorderConfig(fake, 48000, 4) })
        scenario.onFragment { fragment ->
            fragment.micTestSession = session
            fragment.view!!.findViewById<Button>(R.id.testMicButton).performClick()
        }

        assertTrue("a verified external route must let the test keep running", session.active)
        awaitAllReadsConsumed(exhausted)
        assertTrue("...including after it has metered every buffer", session.active)
        session.stop()
    }

    @Test
    fun `an external mic expected but verified as the built-in mic stops the test with a mismatch error`() {
        expectExternalMic()
        val scenario = launchFragmentInContainer<RecordFragment>(themeResId = R.style.Theme_WavRecorder)
        val fake = FakeAudioSource(
            scriptedReads = fakeReads(),
            micInfo = MicrophoneInfo(label = "Phone microphone", isExternal = false, verified = true)
        )
        val session = MicTestSession(startup = ImmediateStartup, openAudioSource = { WavRecorder.RecorderConfig(fake, 48000, 4) })
        scenario.onFragment { fragment ->
            fragment.micTestSession = session
            fragment.view!!.findViewById<Button>(R.id.testMicButton).performClick()
        }

        assertFalse(
            "a built-in-mic route when an external mic was expected must stop the test immediately",
            session.active
        )
        assertEquals(app().getString(R.string.mic_test_route_mismatch_error), ShadowToast.getTextOfLatestToast())
        var buttonText: String? = null
        scenario.onFragment { fragment ->
            buttonText = fragment.view!!.findViewById<Button>(R.id.testMicButton).text.toString()
        }
        assertEquals(app().getString(R.string.test_microphone), buttonText)
    }

    @Test
    fun `an external mic expected but the route is unverified stops the test with a mismatch error`() {
        expectExternalMic()
        val scenario = launchFragmentInContainer<RecordFragment>(themeResId = R.style.Theme_WavRecorder)
        val fake = FakeAudioSource(scriptedReads = fakeReads(), micInfo = MicrophoneInfo.UNVERIFIED)
        val session = MicTestSession(startup = ImmediateStartup, openAudioSource = { WavRecorder.RecorderConfig(fake, 48000, 4) })
        scenario.onFragment { fragment ->
            fragment.micTestSession = session
            fragment.view!!.findViewById<Button>(R.id.testMicButton).performClick()
        }

        assertFalse("an unverified route when an external mic was expected must stop the test", session.active)
        assertEquals(app().getString(R.string.mic_test_route_mismatch_error), ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun `no external mic expected keeps testing the phone mic without a mismatch error`() {
        // Deliberately no expectExternalMic() call: the default (no external input registered)
        // state is exactly "no external mic was ever expected".
        val scenario = launchFragmentInContainer<RecordFragment>(themeResId = R.style.Theme_WavRecorder)
        val exhausted = CountDownLatch(1)
        val fake = FakeAudioSource(
            scriptedReads = fakeReads(),
            onExhausted = { exhausted.countDown() },
            micInfo = MicrophoneInfo(label = "Phone microphone", isExternal = false, verified = true),
            // Goes quiet (0-byte reads, no end-of-stream error) once the scripted reads are used
            // up, so only the route check under test can stop the session.
            readReturnsZeroWhenExhausted = true
        )
        val session = MicTestSession(startup = ImmediateStartup, openAudioSource = { WavRecorder.RecorderConfig(fake, 48000, 4) })
        scenario.onFragment { fragment ->
            fragment.micTestSession = session
            fragment.view!!.findViewById<Button>(R.id.testMicButton).performClick()
        }

        assertTrue("phone-mic testing must proceed normally when no external mic was ever expected", session.active)
        awaitAllReadsConsumed(exhausted)
        assertTrue("...including after it has metered every buffer", session.active)
        session.stop()
    }

    @Test
    fun `a route mismatch releases the AudioSource exactly once, without a reentrant-stop race`() {
        expectExternalMic()
        val scenario = launchFragmentInContainer<RecordFragment>(themeResId = R.style.Theme_WavRecorder)
        var releaseCount = 0
        val fake = object : AudioSource {
            override fun startRecording() {}
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = 4
            override fun stop() {}
            override fun release() { releaseCount++ }
            override fun describeMicrophone(): MicrophoneInfo =
                MicrophoneInfo(label = "Phone microphone", isExternal = false, verified = true)
        }
        val session = MicTestSession(startup = ImmediateStartup, openAudioSource = { WavRecorder.RecorderConfig(fake, 48000, 4) })
        scenario.onFragment { fragment ->
            fragment.micTestSession = session
            fragment.view!!.findViewById<Button>(R.id.testMicButton).performClick()
        }

        assertEquals("the mismatched AudioSource must be released exactly once", 1, releaseCount)
    }

    @Test
    fun `a route mismatch never touches a file, a journal entry, or the foreground service`() {
        expectExternalMic()
        val scenario = launchFragmentInContainer<RecordFragment>(themeResId = R.style.Theme_WavRecorder)
        shadowOf(app()).nextStartedService // drain onStart()'s bindService()-adjacent noise, if any
        val fake = FakeAudioSource(scriptedReads = fakeReads(), micInfo = MicrophoneInfo.UNVERIFIED)
        val session = MicTestSession(startup = ImmediateStartup, openAudioSource = { WavRecorder.RecorderConfig(fake, 48000, 4) })
        scenario.onFragment { fragment ->
            fragment.micTestSession = session
            fragment.view!!.findViewById<Button>(R.id.testMicButton).performClick()
        }

        assertNull(
            "a route mismatch must never start RecordingService (no foreground service, no notification)",
            shadowOf(app()).nextStartedService
        )
        assertNull(
            "a route mismatch must never create a crash-recovery journal entry",
            ActiveSegmentJournal(app()).peekActive()
        )
    }
}
