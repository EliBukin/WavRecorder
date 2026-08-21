package com.example.wavrecorder

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.view.View
import android.widget.Button
import androidx.fragment.app.testing.launchFragmentInContainer
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Covers the "is real recording busy?" gate microphone testing relies on: [RecordFragment] must
 * never read a not-yet-connected (or disconnected) [RecordingService] binding as "definitely
 * idle" -- see [RecordFragment.serviceStateKnown] -- and must defensively shut down a test that's
 * unexpectedly still active the moment a busy service turns out to be connected, without
 * clobbering the real recording UI that's rendered right after. [RecordFragmentMicTestTest]
 * already covers the ordinary (already-known-idle) mutual-exclusion path; this covers the
 * state-unknown window and the defensive-shutdown path specifically.
 */
@RunWith(RobolectricTestRunner::class)
class RecordFragmentServiceStateGateTest {

    private fun app(): Application = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        shadowOf(app()).grantPermissions(Manifest.permission.RECORD_AUDIO)
        shadowOf(app()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun registerService(service: RecordingService) {
        shadowOf(app()).setComponentNameAndServiceForBindService(
            ComponentName(app(), RecordingService::class.java),
            service.LocalBinder()
        )
    }

    /** A [WavRecorder] whose [AudioSource] blocks in read() until stop() releases it -- mirrors
     * RecordFragmentLifecycleTest.blockingRecorder(), lets a real RECORDING/FINALIZING state be
     * reached under Robolectric without a real microphone. */
    private fun blockingRecorder(): WavRecorder {
        val blockForever = CountDownLatch(1)
        return WavRecorder(
            openAudioSource = {
                val source = object : AudioSource {
                    override fun startRecording() {}
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        blockForever.await(5, TimeUnit.SECONDS)
                        return -1
                    }
                    override fun stop() { blockForever.countDown() }
                    override fun release() {}
                }
                WavRecorder.RecorderConfig(source, sampleRate = 48000, bufferSize = 4)
            }
        )
    }

    private fun testButtonEnabled(fragment: RecordFragment): Boolean =
        fragment.view!!.findViewById<Button>(R.id.testMicButton).isEnabled

    @Test
    fun `a pre-existing recording revealed by the connection keeps microphone testing disabled`() {
        val service = Robolectric.buildService(RecordingService::class.java).create().get()
        service.recorder = blockingRecorder()
        registerService(service)
        service.startRecording(1L)
        assertTrue("sanity: the service must genuinely be recording before the fragment connects", service.isRecording)

        val scenario = launchFragmentInContainer<RecordFragment>(themeResId = R.style.Theme_WavRecorder)

        var enabled = true
        scenario.onFragment { fragment -> enabled = testButtonEnabled(fragment) }
        assertFalse(
            "microphone testing must stay disabled once a delayed connection reveals a pre-existing recording",
            enabled
        )
        service.stopRecording()
    }

    @Test
    fun `a service already PREPARING when the connection arrives keeps microphone testing disabled`() {
        // Reaches PREPARING the same legitimate way RecordingServiceTest does -- a real ACTION_START
        // Intent through onStartCommand() -- rather than writing startRequestPending directly (its
        // setter is deliberately private; see RecordingService's own doc for why).
        val controller = Robolectric.buildService(RecordingService::class.java)
        val service = controller.create().get()
        val startIntent = Intent(app(), RecordingService::class.java)
            .setAction(RecordingService.ACTION_START)
            .putExtra(RecordingService.EXTRA_REQUEST_ID, 1L)
        controller.withIntent(startIntent).startCommand(0, 1)
        assertTrue("sanity: expected the service to genuinely be PREPARING", service.startRequestPending)
        registerService(service)

        val scenario = launchFragmentInContainer<RecordFragment>(themeResId = R.style.Theme_WavRecorder)

        var enabled = true
        scenario.onFragment { fragment -> enabled = testButtonEnabled(fragment) }
        assertFalse("microphone testing must stay disabled for a PREPARING service found at connection time", enabled)
    }

    /** A [WavRecorder] whose recording thread never responds to stop() at all (its read() blocks
     * on a latch nothing ever releases) and a generous threadJoinTimeoutMs -- unlike
     * [blockingRecorder] (whose stop() promptly unblocks the read so finalization completes and
     * clears FINALIZING within a few real milliseconds), this keeps the service's background
     * finalize-join thread genuinely blocked for the rest of this test, so `isFinalizing` cannot
     * flip false out from under this test no matter how much of launchFragmentInContainer()'s own
     * internal main-looper draining happens to run in between. [readGate] must be awaited before
     * calling stopRecording(): requestStop() flips isRecording/generation synchronously, on the
     * *calling* thread, so if the recording thread hasn't yet reached its first read() call by
     * then, its own while-loop condition is already false and it exits without ever calling
     * read() (or blocking) at all -- silently finalizing almost instantly instead of staying
     * genuinely in progress. */
    private fun neverFinalizingRecorder(readGate: CountDownLatch): WavRecorder {
        val neverReleased = CountDownLatch(1)
        return WavRecorder(
            threadJoinTimeoutMs = 10_000L,
            openAudioSource = {
                val source = object : AudioSource {
                    override fun startRecording() {}
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        readGate.countDown()
                        neverReleased.await(30, TimeUnit.SECONDS)
                        return -1
                    }
                    override fun stop() {} // deliberately does not unblock read()
                    override fun release() {}
                }
                WavRecorder.RecorderConfig(source, sampleRate = 48000, bufferSize = 4)
            }
        )
    }

    @Test
    fun `a service already FINALIZING when the connection arrives keeps microphone testing disabled`() {
        val readGate = CountDownLatch(1)
        val service = Robolectric.buildService(RecordingService::class.java).create().get()
        service.recorder = neverFinalizingRecorder(readGate)
        registerService(service)
        service.startRecording(1L)
        assertTrue(service.isRecording)
        assertTrue(
            "sanity: the recording thread must have genuinely entered its blocking read before stopping it",
            readGate.await(2, TimeUnit.SECONDS)
        )
        service.stopRecording()
        assertTrue("sanity: expected the stop to leave the service finalizing", service.isFinalizing)

        val scenario = launchFragmentInContainer<RecordFragment>(themeResId = R.style.Theme_WavRecorder)

        assertTrue(
            "sanity: the service must still genuinely be finalizing when this assertion runs",
            service.isFinalizing
        )
        var enabled = true
        scenario.onFragment { fragment -> enabled = testButtonEnabled(fragment) }
        assertFalse("microphone testing must stay disabled for a FINALIZING service found at connection time", enabled)
    }

    @Test
    fun `tapping Test microphone while the service state is still unknown never starts a test`() {
        val service = Robolectric.buildService(RecordingService::class.java).create().get()
        registerService(service)
        val scenario = launchFragmentInContainer<RecordFragment>(themeResId = R.style.Theme_WavRecorder)

        val session = MicTestSession(
            openAudioSource = { WavRecorder.RecorderConfig(FakeAudioSource(scriptedReads = List(50) { byteArrayOf(1, 2, 3, 4) }), 48000, 4) }
        )
        scenario.onFragment { fragment ->
            fragment.micTestSession = session
            // Forces the exact window this gate exists for -- a bind that hasn't (yet, or ever)
            // resolved -- directly, rather than fighting Robolectric's own synchronous bindService()
            // dispatch timing (which resolves the real connection well before this line would run).
            fragment.serviceStateKnown = false
            fragment.refreshTestButtonEnabled()
        }

        var enabled = true
        scenario.onFragment { fragment -> enabled = testButtonEnabled(fragment) }
        assertFalse("the button itself must be disabled while state is unknown", enabled)

        scenario.onFragment { fragment ->
            fragment.view!!.findViewById<Button>(R.id.testMicButton).performClick()
        }
        assertFalse(
            "a tap must never start a test while recording's busy state can't yet be established",
            session.active
        )
    }

    @Test
    fun `a test already active when a busy service connects is stopped and its source released exactly once`() {
        val idleService = Robolectric.buildService(RecordingService::class.java).create().get()
        registerService(idleService)
        val scenario = launchFragmentInContainer<RecordFragment>(themeResId = R.style.Theme_WavRecorder)

        var releaseCount = 0
        val fake = object : AudioSource {
            override fun startRecording() {}
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int { Thread.sleep(20); return 4 }
            override fun stop() {}
            override fun release() { releaseCount++ }
        }
        val session = MicTestSession(openAudioSource = { WavRecorder.RecorderConfig(fake, 48000, 4) })
        scenario.onFragment { fragment ->
            fragment.micTestSession = session
            fragment.view!!.findViewById<Button>(R.id.testMicButton).performClick()
        }
        assertTrue("sanity: the test must genuinely be active before the busy connection lands", session.active)

        // A second, busy service connecting -- the delayed-connection race the gate above defends
        // against, but observed from the "test already running" side this time.
        val busyService = Robolectric.buildService(RecordingService::class.java).create().get()
        busyService.recorder = blockingRecorder()
        busyService.startRecording(1L)
        assertTrue(busyService.isRecording)
        scenario.onFragment { fragment ->
            fragment.serviceConnection.onServiceConnected(
                ComponentName(app(), RecordingService::class.java),
                busyService.LocalBinder()
            )
        }

        assertFalse("the test must be stopped once a busy service is found connected", session.active)
        assertEquals("the test's AudioSource must be released exactly once", 1, releaseCount)
        var enabled = true
        var buttonText: String? = null
        scenario.onFragment { fragment ->
            enabled = testButtonEnabled(fragment)
            buttonText = fragment.view!!.findViewById<Button>(R.id.testMicButton).text.toString()
        }
        assertFalse(enabled)
        assertEquals(app().getString(R.string.test_microphone), buttonText)
        busyService.stopRecording()
    }

    @Test
    fun `the real recording UI is preserved, not reset to idle, after a defensive test shutdown`() {
        val idleService = Robolectric.buildService(RecordingService::class.java).create().get()
        registerService(idleService)
        val scenario = launchFragmentInContainer<RecordFragment>(themeResId = R.style.Theme_WavRecorder)

        val fake = object : AudioSource {
            override fun startRecording() {}
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int { Thread.sleep(20); return 4 }
            override fun stop() {}
            override fun release() {}
        }
        val session = MicTestSession(openAudioSource = { WavRecorder.RecorderConfig(fake, 48000, 4) })
        scenario.onFragment { fragment ->
            fragment.micTestSession = session
            fragment.view!!.findViewById<Button>(R.id.testMicButton).performClick()
        }
        assertTrue(session.active)

        val busyService = Robolectric.buildService(RecordingService::class.java).create().get()
        busyService.recorder = blockingRecorder()
        busyService.startRecording(1L)
        assertTrue(busyService.isRecording)
        scenario.onFragment { fragment ->
            fragment.serviceConnection.onServiceConnected(
                ComponentName(app(), RecordingService::class.java),
                busyService.LocalBinder()
            )
        }

        var statusText: String? = null
        var levelSectionVisible = false
        scenario.onFragment { fragment ->
            statusText = fragment.view!!.findViewById<android.widget.TextView>(R.id.statusText).text.toString()
            levelSectionVisible =
                fragment.view!!.findViewById<View>(R.id.audioLevelSection).visibility == View.VISIBLE
        }
        assertEquals(
            "the real recording's own status must be shown, not the mic-test idle status",
            app().getString(R.string.status_recording), statusText
        )
        assertTrue(
            "the audio level section must stay visible for the real recording, not be hidden by the test's own reset",
            levelSectionVisible
        )
        busyService.stopRecording()
    }
}
