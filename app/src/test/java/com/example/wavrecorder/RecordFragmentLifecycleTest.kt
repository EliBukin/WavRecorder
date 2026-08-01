package com.example.wavrecorder

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Looper
import android.view.View
import androidx.fragment.app.testing.launchFragmentInContainer
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.AudioDeviceInfoBuilder
import java.time.Duration

/**
 * Covers the pending-service-binding lifecycle race: bindService() only reports whether the
 * *request* was accepted, not whether onServiceConnected has actually run yet, and that callback
 * can land well after this screen has already stopped or been destroyed. The fix must not let a
 * late connection wire a listener nothing will ever clear, resync UI that no longer exists, or
 * start a recording nobody is around to show -- even when a start was queued (pendingStart) right
 * before the screen went away.
 */
@RunWith(RobolectricTestRunner::class)
class RecordFragmentLifecycleTest {

    private fun app(): Application = ApplicationProvider.getApplicationContext()

    private fun bindRealService(): RecordingService {
        val service = Robolectric.buildService(RecordingService::class.java).create().get()
        shadowOf(app()).setComponentNameAndServiceForBindService(
            ComponentName(app(), RecordingService::class.java),
            service.LocalBinder()
        )
        return service
    }

    @Test
    fun `a connection that lands after onStop must not wire a listener or start a queued recording`() {
        val service = bindRealService()
        val scenario = launchFragmentInContainer<RecordFragment>(themeResId = R.style.Theme_WavRecorder)
        var fragment: RecordFragment? = null
        scenario.onFragment { fragment = it }
        val theFragment = requireNotNull(fragment)

        // Simulates the moment right before a delayed onServiceConnected would have started a
        // recording the user asked for while this screen was still visible.
        theFragment.pendingStart = true

        // onStop() must cancel that queued start outright, not just leave it for whatever
        // connection eventually lands to act on.
        scenario.moveToState(Lifecycle.State.CREATED)
        assertFalse("onStop() must cancel a queued start, not leave it pending for a later connection",
            theFragment.pendingStart)

        // Now simulate the connection actually landing late, exactly as Android can deliver it
        // after an unbindService() call for a bind that was already in flight.
        theFragment.serviceConnection.onServiceConnected(
            ComponentName(app(), RecordingService::class.java),
            service.LocalBinder()
        )

        assertNull("a delayed connection after onStop must not wire a listener the fragment can " +
            "no longer clear", service.listener)
        assertFalse("a delayed connection after onStop must not start a recording nobody is " +
            "around to show", service.isRecording)
    }

    @Test
    fun `a connection that lands after the view is destroyed must not wire a listener or start a queued recording`() {
        val service = bindRealService()
        val scenario = launchFragmentInContainer<RecordFragment>(themeResId = R.style.Theme_WavRecorder)
        var fragment: RecordFragment? = null
        scenario.onFragment { fragment = it }
        val theFragment = requireNotNull(fragment)

        theFragment.pendingStart = true
        scenario.moveToState(Lifecycle.State.DESTROYED)

        theFragment.serviceConnection.onServiceConnected(
            ComponentName(app(), RecordingService::class.java),
            service.LocalBinder()
        )

        assertNull("a delayed connection after view destruction must not wire a listener",
            service.listener)
        assertFalse("a delayed connection after view destruction must not start a recording",
            service.isRecording)
    }

    /** A [WavRecorder] whose [AudioSource] blocks in read() until stop() releases it -- lets
     * RecordingService.startRecording() actually succeed under Robolectric (which can't
     * initialize a real AudioRecord), so [RecordingService.isRecording] genuinely reflects
     * whether a start was fulfilled, not just whether it threw. Reports itself as a verified
     * external mic so it matches the external device registered via setInputDevices() below --
     * otherwise RecordFragment's own post-start mic-route mismatch check (a *different*,
     * already-covered fix) would immediately stop this recording right back out again. */
    private fun blockingRecorder(): WavRecorder {
        val blockForever = java.util.concurrent.CountDownLatch(1)
        return WavRecorder(
            openAudioSource = {
                val source = object : AudioSource {
                    override fun startRecording() {}
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        blockForever.await(5, java.util.concurrent.TimeUnit.SECONDS)
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

    /**
     * Covers the *other* half of the same lifecycle race, at the service-start level rather than
     * the binding level: beginRecording() calls startForegroundService() before the service
     * connection is necessarily ready, and Android requires every such call to promptly lead to a
     * real startForeground() or a stopSelf() -- missing that window is a crash on API 26+. Unlike
     * the tests above, this one exercises the *real* startForegroundService()/startService() calls
     * RecordFragment actually makes (not a manually-assigned pendingStart), driving them into
     * RecordingService.onStartCommand() the same way Robolectric expects a started (not bound)
     * service to be exercised, via ServiceController.startCommand().
     *
     * recordingService is put back to null realistically -- via the same onServiceDisconnected()
     * callback Android itself would invoke for e.g. the service process dying -- rather than
     * racing Robolectric's own bindService() dispatch timing (which, empirically, resolves during
     * FragmentScenario's own onFragment()/moveToState() draining, before a click's listener would
     * ever run, making genuinely catching it "still connecting" impractical to construct directly).
     */
    @Test
    fun `canceling a pending start before the service connection completes leaves no unpromoted foreground service`() {
        val controller = Robolectric.buildService(RecordingService::class.java)
        val service = controller.create().get()
        shadowOf(app()).setComponentNameAndServiceForBindService(
            ComponentName(app(), RecordingService::class.java),
            service.LocalBinder()
        )

        shadowOf(app()).grantPermissions(Manifest.permission.RECORD_AUDIO)
        shadowOf(app()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        // Registering an external input device skips both the permission and no-external-mic
        // confirmation dialogs, so a single tap goes straight to beginRecording() -- keeping this
        // test focused on the start/cancel race, not those other, separately-covered flows.
        val audioManager = app().getSystemService(AudioManager::class.java)
        shadowOf(audioManager).setInputDevices(
            listOf(AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_USB_DEVICE).build())
        )

        val scenario = launchFragmentInContainer<RecordFragment>(themeResId = R.style.Theme_WavRecorder)
        shadowOf(app()).nextStartedService // drain the plain bindService()-adjacent noise, if any
        var fragment: RecordFragment? = null
        scenario.onFragment { fragment = it } // the initial connection resolves here, harmlessly
        val theFragment = requireNotNull(fragment)

        // Simulates the connection subsequently being lost (e.g. the service process died) --
        // recordingService genuinely becomes null again via the real callback, so the click below
        // takes the real pendingStart path rather than the already-bound one.
        theFragment.serviceConnection.onServiceDisconnected(ComponentName(app(), RecordingService::class.java))

        theFragment.view!!.findViewById<View>(R.id.recordButton).performClick()

        // Confirms this test is exercising the genuine code path (the actual requirement: manually
        // setting pendingStart never issues this) -- RecordFragment.beginRecording() really did
        // call startForegroundService() with the explicit start action.
        val startIntent = requireNotNull(shadowOf(app()).nextStartedService) {
            "expected beginRecording() to have issued a real startForegroundService() call"
        }
        assertEquals(RecordingService.ACTION_START, startIntent.action)
        // Delivers it to onStartCommand() exactly as Android would for a real started service --
        // this is the point at which the service must immediately satisfy its foreground-service
        // obligation on its own (a temporary "preparing" notification + a bounded cleanup
        // timeout), rather than waiting on some future bound startRecording() call.
        controller.withIntent(startIntent).startCommand(0, 1)

        assertNotNull("expected onStartCommand(ACTION_START) to promote to a temporary " +
            "'preparing' foreground state immediately, not wait on a future startRecording() " +
            "call", shadowOf(service).lastForegroundNotification)
        assertFalse(service.isRecording)

        // Leave the screen before a new connection (there isn't one pending here -- see above)
        // ever completes.
        scenario.moveToState(Lifecycle.State.DESTROYED)

        val cancelIntent = requireNotNull(shadowOf(app()).nextStartedService) {
            "expected onStop() to explicitly retract the pending start via ACTION_CANCEL_START"
        }
        assertEquals(RecordingService.ACTION_CANCEL_START, cancelIntent.action)
        controller.withIntent(cancelIntent).startCommand(0, 2)

        assertFalse("canceling a pending start must never leave a recording active",
            service.isRecording)
        assertNull("a canceled pending start must no longer be in the foreground once retracted",
            shadowOf(service).lastForegroundNotification)
        assertTrue("expected the service to have safely stopped itself rather than lingering as " +
            "a started-but-never-promoted foreground service", shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun `a pending start that is fulfilled by a completed connection is never canceled by a later onStop`() {
        // Sanity companion to the test above: proves the fix doesn't overreach and cancel/stop an
        // already-active recording just because the screen is later left.
        val controller = Robolectric.buildService(RecordingService::class.java)
        val service = controller.create().get()
        service.recorder = blockingRecorder()
        shadowOf(app()).setComponentNameAndServiceForBindService(
            ComponentName(app(), RecordingService::class.java),
            service.LocalBinder()
        )
        shadowOf(app()).grantPermissions(Manifest.permission.RECORD_AUDIO)
        shadowOf(app()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val audioManager = app().getSystemService(AudioManager::class.java)
        shadowOf(audioManager).setInputDevices(
            listOf(AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_USB_DEVICE).build())
        )

        val scenario = launchFragmentInContainer<RecordFragment>(themeResId = R.style.Theme_WavRecorder)
        shadowOf(app()).nextStartedService
        var fragment: RecordFragment? = null
        // A separate reference-capturing interaction first (rather than clicking directly inside
        // this same onFragment{} call): empirically, it's this call's own implicit draining that
        // resolves the still-outstanding bind connection, so the click below -- performed directly
        // on the cached view, without another onFragment{} wrapper -- runs against an already-
        // connected recordingService, exactly like the real, common-case happy path.
        scenario.onFragment { fragment = it }
        val theFragment = requireNotNull(fragment)

        theFragment.view!!.findViewById<View>(R.id.recordButton).performClick()

        val startIntent = shadowOf(app()).nextStartedService
        if (startIntent != null) controller.withIntent(startIntent).startCommand(0, 1)

        assertTrue("expected the bound connection to have driven startRecording() already",
            service.isRecording)

        scenario.moveToState(Lifecycle.State.DESTROYED)

        // No further started-service Intent should have been sent: onStop()'s cancellation path
        // only fires for a still-*pending* start, and this one was already fulfilled.
        assertNull("must not cancel a start that already completed", shadowOf(app()).nextStartedService)
        assertTrue("leaving the screen must never stop an already-active recording",
            service.isRecording)

        // Also proves startRecording() actually disarmed the bounded pending-start timeout (see
        // RecordingService.PENDING_START_TIMEOUT_MS) once it fulfilled this request above --
        // advancing Robolectric's paused main-looper clock well past it must never retroactively
        // stop a recording that's already active.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(RecordingService.PENDING_START_TIMEOUT_MS))
        assertTrue("the bounded pending-start timeout must never fire once a start has been " +
            "fulfilled", service.isRecording)
    }
}
