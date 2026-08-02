package com.example.wavrecorder

import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** A no-op [RecordingService.Listener]; tests override only the callbacks they care about. */
private open class StubListener : RecordingService.Listener {
    override fun onAmplitude(amplitude: Float) {}
    override fun onSegmentStarted(target: OutputTarget, partNumber: Int) {}
    override fun onError(e: Exception) {}
    override fun onStopped(lastTarget: OutputTarget?) {}
}

@RunWith(RobolectricTestRunner::class)
class RecordingServiceTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `starting a new session resets currentTarget so stopping before its first segment starts never reports the previous session's file`() {
        val service = Robolectric.buildService(RecordingService::class.java).create().get()
        val chunk = byteArrayOf(1, 2, 3, 4)

        // Session 1's source: hands back real audio immediately and repeatedly (never blocks,
        // never errors) so the test -- not the source running out on its own -- decides when the
        // session ends, via an explicit stopRecording() below.
        val session1Source = object : AudioSource {
            override fun startRecording() {}
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                System.arraycopy(chunk, 0, buffer, offset, chunk.size)
                return chunk.size
            }
            override fun stop() {}
            override fun release() {}
        }

        // Session 2's source: blocks forever on its very first read(), so its first segment can
        // never finish being reported via onSegmentStarted before the test calls stopRecording().
        val blockForever = CountDownLatch(1)
        val session2Source = object : AudioSource {
            override fun startRecording() {}
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                blockForever.await(5, TimeUnit.SECONDS)
                return -1
            }
            override fun stop() { blockForever.countDown() }
            override fun release() {}
        }

        var startCount = 0
        service.recorder = WavRecorder(
            threadJoinTimeoutMs = 500,
            openAudioSource = {
                startCount++
                val source = if (startCount == 1) session1Source else session2Source
                WavRecorder.RecorderConfig(source, sampleRate = 48000, bufferSize = chunk.size)
            }
        )

        var session1StoppedTarget: OutputTarget? = null
        var session1StoppedCalled = false
        var session2StoppedTarget: OutputTarget? = null
        var session2StoppedCalled = false
        var onSession1 = true
        service.listener = object : StubListener() {
            override fun onStopped(lastTarget: OutputTarget?) {
                if (onSession1) {
                    session1StoppedCalled = true
                    session1StoppedTarget = lastTarget
                } else {
                    session2StoppedCalled = true
                    session2StoppedTarget = lastTarget
                }
            }
        }

        // --- Session 1: let it open a real segment, then stop it normally. ---
        service.startRecording(1L)
        // The background thread needs real wall-clock time to actually reach openSegment()'s
        // postIfCurrent() before there's anything for idle() to deliver, so this polls both
        // together rather than assuming a single idle() call right after startRecording() would
        // already find it queued.
        val segmentOpenedDeadline = System.currentTimeMillis() + 2000
        while (service.lastTarget == null && System.currentTimeMillis() < segmentOpenedDeadline) {
            Thread.sleep(5)
            shadowOf(Looper.getMainLooper()).idle()
        }
        service.stopRecording()

        assertTrue("expected session 1 to report a real saved file", session1StoppedCalled)
        assertTrue("expected session 1's onStopped target to be a real file, not null",
            session1StoppedTarget != null)

        // --- Session 2: start, then stop immediately without ever draining the main looper, so
        // even if the background thread managed to queue an onSegmentStarted post, it can't have
        // been delivered yet -- currentTarget can only be whatever startRecording() itself just
        // set synchronously. ---
        onSession1 = false
        service.startRecording(1L)
        service.stopRecording()

        assertTrue("expected session 2 to report a stop (even with no file yet)", session2StoppedCalled)
        assertNull(
            "a session stopped before its first segment starts must never report the *previous* " +
                "session's file as its own newly saved result",
            session2StoppedTarget
        )
    }

    @Test
    fun `a revoked SAF permission during recording surfaces as an error instead of failing silently`() {
        val service = Robolectric.buildService(RecordingService::class.java).create().get()

        // Simulates the folder having become inaccessible (permission revoked from Settings, or
        // the folder deleted) between choosing it and actually starting to record: DestinationManager's
        // real createOutputFile() would surface this the same way, via a SecurityException /
        // IllegalStateException out of DocumentFile.
        service.destinationManager = object : DestinationManager(ApplicationProvider.getApplicationContext()) {
            override fun createOutputFile(fileName: String): OutputTarget {
                throw SecurityException("Permission to the selected folder has been revoked")
            }
        }
        service.recorder = WavRecorder(
            openAudioSource = {
                val silentSource = object : AudioSource {
                    override fun startRecording() {}
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = -1
                    override fun stop() {}
                    override fun release() {}
                }
                WavRecorder.RecorderConfig(silentSource, sampleRate = 48000, bufferSize = 4)
            }
        )

        var reportedError: Exception? = null
        var onStoppedCalled = false
        service.listener = object : StubListener() {
            override fun onError(e: Exception) { reportedError = e }
            override fun onStopped(lastTarget: OutputTarget?) { onStoppedCalled = true }
        }

        service.startRecording(1L)
        val deadline = System.currentTimeMillis() + 2000
        while (service.isRecording && System.currentTimeMillis() < deadline) Thread.sleep(5)
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue("expected the revoked-permission failure to be reported as a SecurityException",
            reportedError is SecurityException)
        assertTrue("the service must not be left recording after its destination became inaccessible",
            !service.isRecording)
        assertTrue("a failed session must not be reported as a normal stop", !onStoppedCalled)
    }

    @Test
    fun `a finalization failure at stop() is reported via onFinalizationFailed, never as a normal Saved status`() {
        val service = Robolectric.buildService(RecordingService::class.java).create().get()
        val chunk = byteArrayOf(1, 2, 3, 4)
        val blockUntilStopped = CountDownLatch(1)
        var reads = 0
        val source = object : AudioSource {
            override fun startRecording() {}
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                reads++
                if (reads == 1) {
                    System.arraycopy(chunk, 0, buffer, offset, chunk.size)
                    return chunk.size
                }
                blockUntilStopped.await(5, TimeUnit.SECONDS)
                return -1
            }
            override fun stop() { blockUntilStopped.countDown() }
            override fun release() {}
        }
        val segmentFile = tempFolder.newFile("segment.wav")
        service.destinationManager = object : DestinationManager(ApplicationProvider.getApplicationContext()) {
            override fun createOutputFile(fileName: String): OutputTarget = OutputTarget.FileTarget(segmentFile)
        }
        service.recorder = WavRecorder(
            openAudioSource = { WavRecorder.RecorderConfig(source, sampleRate = 48000, bufferSize = chunk.size) },
            wrapChannel = { channel -> AlwaysFailingHeaderWriter(channel) }
        )

        var finalizationFailedTarget: OutputTarget? = null
        var finalizationFailedCause: Exception? = null
        var onStoppedCalled = false
        service.listener = object : StubListener() {
            override fun onStopped(lastTarget: OutputTarget?) { onStoppedCalled = true }
            override fun onFinalizationFailed(target: OutputTarget?, cause: Exception) {
                finalizationFailedTarget = target
                finalizationFailedCause = cause
            }
        }

        service.startRecording(1L)
        shadowOf(Looper.getMainLooper()).idle()
        val deadline = System.currentTimeMillis() + 2000
        while (reads < 1 && System.currentTimeMillis() < deadline) Thread.sleep(5)
        Thread.sleep(20)
        service.stopRecording()

        assertTrue("expected onFinalizationFailed, not a plain onStopped", !onStoppedCalled)
        assertTrue(finalizationFailedCause is WavFinalizationException)
        assertEquals(segmentFile.absolutePath, (finalizationFailedTarget as? OutputTarget.FileTarget)?.file?.absolutePath)
    }

    @Test
    fun `a finalization result of Unknown surfaces via onFinalizationUnknown, never onStopped or a normal Saved status`() {
        val service = Robolectric.buildService(RecordingService::class.java).create().get()
        val chunk = byteArrayOf(1, 2, 3, 4)
        // Never returns, and stop() deliberately does NOT unblock it -- standing in for a
        // genuinely wedged driver that WavRecorder.stop()'s join timeout has to give up waiting
        // on (see threadJoinTimeoutMs below), which is exactly the condition that produces
        // FinalizeResult.Unknown rather than Ok or Failed.
        val blockForever = CountDownLatch(1)
        val source = object : AudioSource {
            override fun startRecording() {}
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                blockForever.await(5, TimeUnit.SECONDS)
                return -1
            }
            override fun stop() {}
            override fun release() {}
        }
        val segmentFile = tempFolder.newFile("segment.wav")
        service.destinationManager = object : DestinationManager(ApplicationProvider.getApplicationContext()) {
            override fun createOutputFile(fileName: String): OutputTarget = OutputTarget.FileTarget(segmentFile)
        }
        service.recorder = WavRecorder(
            threadJoinTimeoutMs = 50, // fast, deterministic timeout so stop() gives up quickly
            openAudioSource = { WavRecorder.RecorderConfig(source, sampleRate = 48000, bufferSize = chunk.size) }
        )

        var onStoppedCalled = false
        var unknownCalled = false
        var unknownTarget: OutputTarget? = null
        service.listener = object : StubListener() {
            override fun onStopped(lastTarget: OutputTarget?) { onStoppedCalled = true }
            override fun onFinalizationUnknown(target: OutputTarget?) {
                unknownCalled = true
                unknownTarget = target
            }
        }

        service.startRecording(1L)
        // openSegment() (and its onSegmentStarted post) runs before the blocked read() ever get
        // called, so this polls for currentTarget to be set exactly like the stale-state-reset
        // test above, rather than assuming a single idle() call would already find it queued.
        val segmentOpenedDeadline = System.currentTimeMillis() + 2000
        while (service.lastTarget == null && System.currentTimeMillis() < segmentOpenedDeadline) {
            Thread.sleep(5)
            shadowOf(Looper.getMainLooper()).idle()
        }
        service.stopRecording()

        assertTrue("expected onFinalizationUnknown to fire for a thread that never joined in time",
            unknownCalled)
        assertTrue("a genuinely unknown finalization result must never be reported as a normal " +
            "onStopped/'Saved' status", !onStoppedCalled)
        assertEquals(segmentFile.absolutePath, (unknownTarget as? OutputTarget.FileTarget)?.file?.absolutePath)

        blockForever.countDown() // release the wedged background thread so it doesn't linger past the test
    }

    /** Always fails the header seek-back-to-0, simulating a header patch failure without
     * disturbing normal data writes. */
    private class AlwaysFailingHeaderWriter(private val channel: java.nio.channels.FileChannel) : SegmentWriter {
        override fun write(buffer: java.nio.ByteBuffer): Int = channel.write(buffer)
        override fun position(): Long = channel.position()
        override fun position(newPosition: Long) {
            if (newPosition == 0L) throw java.io.IOException("simulated header patch failure")
            channel.position(newPosition)
        }
        override fun close() = channel.close()
    }

    @Test
    fun `foregroundStartModeFor uses the two-arg call on API 29 and below`() {
        assertEquals(ForegroundStartMode.TWO_ARG, foregroundStartModeFor(24))
        assertEquals(ForegroundStartMode.TWO_ARG, foregroundStartModeFor(29))
    }

    @Test
    fun `foregroundStartModeFor uses the microphone type on API 30 and above`() {
        assertEquals(ForegroundStartMode.MICROPHONE_TYPE, foregroundStartModeFor(30))
        assertEquals(ForegroundStartMode.MICROPHONE_TYPE, foregroundStartModeFor(34))
    }

    private fun app(): Application = ApplicationProvider.getApplicationContext()

    private fun postedNotificationTitles(): List<String?> =
        app().getSystemService(NotificationManager::class.java).activeNotifications.map {
            it.notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString()
        }

    @Test
    fun `a session that ends with no listener attached persists its outcome and raises a terminal notification`() {
        val service = Robolectric.buildService(RecordingService::class.java).create().get()
        val chunk = byteArrayOf(1, 2, 3, 4)
        val source = object : AudioSource {
            override fun startRecording() {}
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                System.arraycopy(chunk, 0, buffer, offset, chunk.size)
                return chunk.size
            }
            override fun stop() {}
            override fun release() {}
        }
        service.recorder = WavRecorder(
            openAudioSource = { WavRecorder.RecorderConfig(source, sampleRate = 48000, bufferSize = chunk.size) }
        )
        // Deliberately no listener attached -- simulates the app backgrounded/screen off.

        service.startRecording(1L)
        val segmentOpenedDeadline = System.currentTimeMillis() + 2000
        while (service.lastTarget == null && System.currentTimeMillis() < segmentOpenedDeadline) {
            Thread.sleep(5)
            shadowOf(Looper.getMainLooper()).idle()
        }
        service.stopRecording()

        val pending = service.consumePendingOutcome()
        assertTrue("expected a Saved outcome to be persisted for a later bind to pick up",
            pending is RecordingOutcome.Saved)
        assertNull("consumePendingOutcome must clear the outcome so it isn't redelivered",
            service.consumePendingOutcome())

        assertTrue(
            "expected a terminal 'Recording saved' notification, got: ${postedNotificationTitles()}",
            postedNotificationTitles().contains(app().getString(R.string.notification_result_saved_title))
        )
    }

    @Test
    fun `a session that ends with a listener attached does not persist an outcome or raise a terminal notification`() {
        val service = Robolectric.buildService(RecordingService::class.java).create().get()
        val chunk = byteArrayOf(1, 2, 3, 4)
        val source = object : AudioSource {
            override fun startRecording() {}
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                System.arraycopy(chunk, 0, buffer, offset, chunk.size)
                return chunk.size
            }
            override fun stop() {}
            override fun release() {}
        }
        service.recorder = WavRecorder(
            openAudioSource = { WavRecorder.RecorderConfig(source, sampleRate = 48000, bufferSize = chunk.size) }
        )
        service.listener = StubListener()

        service.startRecording(1L)
        val segmentOpenedDeadline = System.currentTimeMillis() + 2000
        while (service.lastTarget == null && System.currentTimeMillis() < segmentOpenedDeadline) {
            Thread.sleep(5)
            shadowOf(Looper.getMainLooper()).idle()
        }
        service.stopRecording()

        assertNull("no outcome should be persisted when a listener was attached to receive it live",
            service.consumePendingOutcome())
        assertFalse(
            "no terminal notification should be raised when the outcome was already delivered live",
            postedNotificationTitles().contains(app().getString(R.string.notification_result_saved_title))
        )
    }

    @Test
    fun `onError inspects stop()'s finalize result -- a finalization failure takes precedence over the generic mid-recording error`() {
        val service = Robolectric.buildService(RecordingService::class.java).create().get()
        val chunk = byteArrayOf(1, 2, 3, 4)
        val connected = AtomicBoolean(true)
        val source = object : AudioSource {
            override fun startRecording() {}
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                System.arraycopy(chunk, 0, buffer, offset, chunk.size)
                return chunk.size
            }
            override fun stop() {}
            override fun release() {}
            override fun describeMicrophone() =
                MicrophoneInfo(label = "USB Mic", isExternal = true, verified = true)
            override fun isDeviceConnected(): Boolean = connected.get()
        }
        val segmentFile = tempFolder.newFile("segment.wav")
        service.destinationManager = object : DestinationManager(ApplicationProvider.getApplicationContext()) {
            override fun createOutputFile(fileName: String): OutputTarget = OutputTarget.FileTarget(segmentFile)
        }
        service.recorder = WavRecorder(
            headerFlushIntervalMs = 10, // fast disconnect-check cadence
            openAudioSource = { WavRecorder.RecorderConfig(source, sampleRate = 48000, bufferSize = chunk.size) },
            wrapChannel = { channel -> AlwaysFailingHeaderWriter(channel) }
        )

        var errorReported: Exception? = null
        var finalizationFailedTarget: OutputTarget? = null
        var finalizationFailedCause: Exception? = null
        service.listener = object : StubListener() {
            override fun onError(e: Exception) { errorReported = e }
            override fun onFinalizationFailed(target: OutputTarget?, cause: Exception) {
                finalizationFailedTarget = target
                finalizationFailedCause = cause
            }
        }

        service.startRecording(1L)
        val segmentOpenedDeadline = System.currentTimeMillis() + 2000
        while (service.lastTarget == null && System.currentTimeMillis() < segmentOpenedDeadline) {
            Thread.sleep(5)
            shadowOf(Looper.getMainLooper()).idle()
        }
        Thread.sleep(50) // let some real audio actually get captured first
        connected.set(false) // simulate the external mic being unplugged mid-recording

        val deadline = System.currentTimeMillis() + 2000
        while (service.isRecording && System.currentTimeMillis() < deadline) Thread.sleep(5)
        shadowOf(Looper.getMainLooper()).idle()

        assertNull(
            "a finalization failure must take precedence -- the generic onError callback must " +
                "not fire when the file also failed to finalize",
            errorReported
        )
        assertNotNull("expected the finalization failure to be reported instead", finalizationFailedCause)
        assertTrue(finalizationFailedCause is WavFinalizationException)
        assertEquals(segmentFile.absolutePath, (finalizationFailedTarget as? OutputTarget.FileTarget)?.file?.absolutePath)
    }

    @Test
    fun `a pending outcome persists across service destruction and recreation, and is consumed exactly once`() {
        // A brand new ServiceController -- not the one from bindRealService()-style helpers used
        // elsewhere -- specifically so this test can destroy() it and build a genuinely separate
        // second instance afterward, standing in for the OS destroying the process/service and a
        // later app reopen recreating it. A purely in-memory pendingOutcome field (the bug this
        // covers) would lose the outcome the moment that happens.
        val controller1 = Robolectric.buildService(RecordingService::class.java)
        val service1 = controller1.create().get()
        val chunk = byteArrayOf(1, 2, 3, 4)
        val source = object : AudioSource {
            override fun startRecording() {}
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                System.arraycopy(chunk, 0, buffer, offset, chunk.size)
                return chunk.size
            }
            override fun stop() {}
            override fun release() {}
        }
        val segmentFile = tempFolder.newFile("segment.wav")
        service1.destinationManager = object : DestinationManager(ApplicationProvider.getApplicationContext()) {
            override fun createOutputFile(fileName: String): OutputTarget = OutputTarget.FileTarget(segmentFile)
        }
        service1.recorder = WavRecorder(
            openAudioSource = { WavRecorder.RecorderConfig(source, sampleRate = 48000, bufferSize = chunk.size) }
        )
        // Deliberately no listener attached -- simulates the app fully backgrounded/killed.

        service1.startRecording(1L)
        val segmentOpenedDeadline = System.currentTimeMillis() + 2000
        while (service1.lastTarget == null && System.currentTimeMillis() < segmentOpenedDeadline) {
            Thread.sleep(5)
            shadowOf(Looper.getMainLooper()).idle()
        }
        service1.stopRecording()

        controller1.destroy()

        val service2 = Robolectric.buildService(RecordingService::class.java).create().get()
        val consumed = service2.consumePendingOutcome()

        assertTrue("expected the outcome to survive service destruction/recreation, got: $consumed",
            consumed is RecordingOutcome.Saved)
        assertEquals("expected the target to be preserved across the durable round-trip",
            segmentFile.absolutePath,
            ((consumed as RecordingOutcome.Saved).target as? OutputTarget.FileTarget)?.file?.absolutePath)
        assertNull("consuming again with nothing new persisted since must return null -- the " +
            "record must be cleared, not just returned, on the first consume",
            service2.consumePendingOutcome())
    }

    /** A [WavRecorder] whose fake [AudioSource] blocks in read() until stop() releases it -- lets
     * RecordingService.startRecording() actually succeed and stay "active" under Robolectric
     * (which can't initialize a real AudioRecord), for tests below that need a genuinely active
     * recording rather than a merely pending one. */
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

    private fun startIntent(requestId: Long) = Intent(app(), RecordingService::class.java)
        .setAction(RecordingService.ACTION_START)
        .putExtra(RecordingService.EXTRA_REQUEST_ID, requestId)
    private fun cancelIntent(requestId: Long) = Intent(app(), RecordingService::class.java)
        .setAction(RecordingService.ACTION_CANCEL_START)
        .putExtra(RecordingService.EXTRA_REQUEST_ID, requestId)

    @Test
    fun `delivering ACTION_START without binding or cancellation immediately enters the foreground with a preparing notification`() {
        // Covers the core gap: onStartCommand(ACTION_START) must satisfy the foreground-service
        // obligation on its own, right here, rather than waiting on some future bound
        // startRecording() call that may be delayed past Android's own foreground-service start
        // deadline, or may never come at all (see the next test).
        val controller = Robolectric.buildService(RecordingService::class.java)
        val service = controller.create().get()

        controller.withIntent(startIntent(1L)).startCommand(0, 1)

        assertNotNull(
            "expected onStartCommand(ACTION_START) to promote to the foreground immediately, " +
                "without depending on a future binder callback",
            shadowOf(service).lastForegroundNotification
        )
        assertTrue("the request is pending, not yet fulfilled by a real recording",
            service.startRequestPending)
        assertFalse(service.isRecording)
        assertEquals("expected the request id from the Intent to be recorded as current",
            1L, service.currentRequestId)
    }

    @Test
    fun `a connection that never arrives is eventually cleaned up without ever recording`() {
        val controller = Robolectric.buildService(RecordingService::class.java)
        val service = controller.create().get()

        controller.withIntent(startIntent(1L)).startCommand(0, 1)
        assertNotNull("sanity: entered the temporary foreground state",
            shadowOf(service).lastForegroundNotification)

        // Nothing ever fulfills (startRecording()) or cancels (ACTION_CANCEL_START) this pending
        // request -- advance Robolectric's paused main-looper clock past the bounded cleanup
        // timeout to simulate a bound connection that simply never arrives at all (e.g. the
        // framework silently drops the bind), rather than waiting out real wall-clock time.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(RecordingService.PENDING_START_TIMEOUT_MS))

        assertFalse("the pending request must have been given up on", service.startRequestPending)
        assertFalse("must never have started an actual recording", service.isRecording)
        assertNull("must no longer be in the foreground once the bounded timeout gives up",
            shadowOf(service).lastForegroundNotification)
        assertTrue("expected a safe self-stop, not a lingering idle foreground service",
            shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun `a late ACTION_START delivered after recording is already active is a no-op and leaves no pending request`() {
        // Simulates the *other* real ordering: a live, already-bound Fragment called
        // startRecording() directly through its binder reference before this Intent-dispatched
        // onStartCommand() call -- for the very same startForegroundService() request -- ever ran.
        // Android gives no ordering guarantee between a direct Binder method call and the async
        // dispatch of the Intent that triggered it.
        val controller = Robolectric.buildService(RecordingService::class.java)
        val service = controller.create().get()
        service.recorder = blockingRecorder()
        service.startRecording(1L)
        assertTrue("sanity: recording is genuinely active before the late ACTION_START arrives",
            service.isRecording)

        controller.withIntent(startIntent(1L)).startCommand(0, 1)

        assertFalse("a late ACTION_START must never mark an already-active recording as pending",
            service.startRequestPending)
        assertTrue("must still be recording, undisturbed", service.isRecording)

        service.stopRecording() // release the blocking fake source so it doesn't linger past the test
    }

    @Test
    fun `a later ACTION_CANCEL_START cannot stop an already-active recording`() {
        val controller = Robolectric.buildService(RecordingService::class.java)
        val service = controller.create().get()
        service.recorder = blockingRecorder()
        service.startRecording(1L)
        assertTrue(service.isRecording)

        controller.withIntent(cancelIntent(1L)).startCommand(0, 1)

        assertTrue("a late cancellation must never stop an active recording", service.isRecording)
        assertFalse("must not have self-stopped", shadowOf(service).isStoppedBySelf)

        service.stopRecording() // release the blocking fake source so it doesn't linger past the test
    }

    /** A [WavRecorder] whose [openAudioSource] always throws synchronously, simulating e.g. the
     * microphone being seized by another app at the exact moment startRecording() runs -- the
     * failure path that resolves entirely inside the direct startRecording() call itself, before
     * an ACTION_START Intent for the same attempt (sent moments earlier, in the real
     * beginRecording() flow) has necessarily even been dispatched to onStartCommand() yet. */
    private fun alwaysFailsSynchronously(): WavRecorder = WavRecorder(
        openAudioSource = { throw IllegalStateException("simulated: microphone busy") }
    )

    @Test
    fun `a delayed ACTION_START for an attempt that already failed synchronously is ignored`() {
        val controller = Robolectric.buildService(RecordingService::class.java)
        val service = controller.create().get()
        service.recorder = alwaysFailsSynchronously()

        // Simulates the already-bound ordering: a live Fragment calls startRecording(1L) directly
        // through its binder reference, and it fails synchronously (see alwaysFailsSynchronously())
        // -- resolving this attempt, self-stopping -- all before the Intent-dispatched
        // ACTION_START for this very same attempt (id 1) has been delivered.
        service.startRecording(1L)
        assertFalse(service.isRecording)
        assertEquals(1L, service.currentRequestId)
        assertTrue("sanity: the synchronous failure already resolved via a safe self-stop",
            shadowOf(service).isStoppedBySelf)

        controller.withIntent(startIntent(1L)).startCommand(0, 1)

        assertFalse("a delayed ACTION_START for an attempt that already resolved must not be " +
            "treated as a new pending request", service.startRequestPending)
        assertFalse(service.isRecording)
        assertNull("must not re-enter the foreground for an attempt that's already over",
            shadowOf(service).lastForegroundNotification)
    }

    @Test
    fun `a genuinely new retry with a fresh request id is accepted after a prior synchronous failure`() {
        val controller = Robolectric.buildService(RecordingService::class.java)
        val service = controller.create().get()
        service.recorder = alwaysFailsSynchronously()

        service.startRecording(1L) // attempt 1 fails synchronously
        assertFalse(service.isRecording)

        // The user retries -- a fresh, strictly higher id, delivered the normal (not-yet-bound)
        // way via ACTION_START.
        controller.withIntent(startIntent(2L)).startCommand(0, 2)

        assertTrue("a fresh, higher request id must be accepted as a genuinely new attempt, even " +
            "though a prior (lower) id already resolved as a failure", service.startRequestPending)
        assertNotNull("expected the retry to promote to the temporary foreground state",
            shadowOf(service).lastForegroundNotification)
        assertEquals(2L, service.currentRequestId)
    }

    @Test
    fun `a stale ACTION_CANCEL_START for a superseded request id does not affect a newer pending request`() {
        val controller = Robolectric.buildService(RecordingService::class.java)
        val service = controller.create().get()

        controller.withIntent(startIntent(1L)).startCommand(0, 1)
        assertEquals(1L, service.currentRequestId)

        // A fresh, higher id supersedes the still-pending id 1 before it's ever resolved --
        // plausible if, say, the id-1 attempt's own cancellation got lost/delayed and the user
        // simply retried; the mechanism under test doesn't depend on exactly how this happens.
        controller.withIntent(startIntent(2L)).startCommand(0, 2)
        assertEquals(2L, service.currentRequestId)
        assertTrue(service.startRequestPending)

        // A stale cancellation for the old, superseded id 1 arrives late.
        controller.withIntent(cancelIntent(1L)).startCommand(0, 3)

        assertTrue("a cancellation naming an old, superseded request id must not retract the " +
            "current one", service.startRequestPending)
        assertNotNull("must still be in the temporary foreground state",
            shadowOf(service).lastForegroundNotification)
        assertFalse("must not have self-stopped", shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun `a stale pending-start timeout for a superseded request id does not affect a newer pending request`() {
        val controller = Robolectric.buildService(RecordingService::class.java)
        val service = controller.create().get()

        // id 1 becomes pending at T+0s and arms its own bounded timeout, due at T+15s.
        controller.withIntent(startIntent(1L)).startCommand(0, 1)
        assertEquals(1L, service.currentRequestId)

        // A genuine 1s gap before id 2 supersedes it (at T+1s, due T+16s) -- so the two timeouts
        // have distinctly different due times, letting this test isolate "id 1's stale timeout,
        // firing on its own, is a no-op" from "id 2's own legitimate timeout happened to fire at
        // the same instant." Deliberately via ACTION_START (not a direct startRecording() call),
        // so id 1's already-posted timeout closure is *not* proactively removed (see
        // pendingStartHandler's doc) -- it stays genuinely scheduled, exercising the real guard
        // rather than relying on cleanup elsewhere to prevent it from ever running.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        controller.withIntent(startIntent(2L)).startCommand(0, 2)
        assertEquals(2L, service.currentRequestId)
        assertTrue(service.startRequestPending)

        // Advances to T+15.5s: past id 1's T+15s deadline (its stale timeout fires and must be a
        // no-op), comfortably before id 2's T+16s deadline (which must not have fired yet).
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(RecordingService.PENDING_START_TIMEOUT_MS - 500))

        assertTrue("id 2 must still be pending -- id 1's stale timeout firing must not have " +
            "retracted it", service.startRequestPending)
        assertFalse("id 2 must not have been stopped by id 1's stale timeout",
            shadowOf(service).isStoppedBySelf)

        // Sanity: id 2's own timeout still works correctly once it genuinely elapses (T+16s) --
        // proves the guard isn't accidentally suppressing legitimate timeouts too.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertFalse("id 2's own timeout must still fire normally once it genuinely elapses",
            service.startRequestPending)
        assertTrue(shadowOf(service).isStoppedBySelf)
    }
}
