package com.example.wavrecorder

import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import android.os.Looper
import android.util.Log
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
import org.robolectric.shadows.ShadowLog
import java.io.RandomAccessFile
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.measureTimeMillis

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

    /** stopRecording()/onError now finalize asynchronously (see RecordingService.beginAsyncFinalize):
     * the actual join happens on a real background thread, so this waits for it to actually finish
     * (rather than assuming a single idle() call would already find its result queued) before
     * draining the main looper to deliver whatever it posted. Mirrors WavRecorderTest's own
     * awaitTerminatedAndDeliverCallbacks for the same reason. */
    private fun awaitFinalizationAndIdle(service: RecordingService, timeoutMs: Long = 3000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (service.isFinalizing && System.currentTimeMillis() < deadline) Thread.sleep(5)
        shadowOf(Looper.getMainLooper()).idle()
    }

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
        awaitFinalizationAndIdle(service)

        assertTrue("expected session 1 to report a real saved file", session1StoppedCalled)
        assertTrue("expected session 1's onStopped target to be a real file, not null",
            session1StoppedTarget != null)

        // --- Session 2: start, then stop immediately without ever draining the main looper before
        // stopRecording() itself captures currentTarget -- so even if the background recording
        // thread managed to queue an onSegmentStarted post in the meantime, it can't have been
        // delivered (and mutated currentTarget) yet at the moment beginAsyncFinalize snapshots it.
        // (The later awaitFinalizationAndIdle below does drain the looper, but by then that stale
        // post's own generation check already fails, since requestStop() bumped it first.) ---
        onSession1 = false
        service.startRecording(1L)
        service.stopRecording()
        awaitFinalizationAndIdle(service)

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
        shadowOf(Looper.getMainLooper()).idle() // delivers the posted onError(e), which kicks off beginAsyncFinalize
        awaitFinalizationAndIdle(service) // then waits for its own background join + delivers its result

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
        awaitFinalizationAndIdle(service)

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
        awaitFinalizationAndIdle(service)

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
    fun `onCreate runs a startup recovery check for a segment left by a previous process`() {
        // Simulates a real interrupted segment (crash right after opening: header declares 0
        // bytes, but 50 real bytes already made it to disk) left behind by a now-gone previous
        // process -- then confirms onCreate() alone (not any explicit recording action) is what
        // discovers and repairs it, proving the recovery check is actually wired into service
        // startup rather than just unit-tested in isolation.
        val segmentFile = tempFolder.newFile("interrupted.wav")
        RandomAccessFile(segmentFile, "rw").use { raf ->
            val header = WavHeaderWriter.build(sampleRate = 48000, channels = 1, bitsPerSample = 16, audioDataLen = 0L)
            val headerBytes = ByteArray(header.remaining())
            header.get(headerBytes)
            raf.write(headerBytes)
            raf.write(ByteArray(50))
        }
        // Simulates the *previous* (now-gone) process's own ACTIVE record, exactly as
        // WavRecorder.openSegment() would have left it -- onCreate() is what's responsible for
        // atomically claiming this as a recovery candidate before anything else can touch it.
        val journal = ActiveSegmentJournal(app())
        // Tagged with a fake foreign owner id (never this test JVM's own ProcessInstanceId), so
        // claimActiveAsRecoveryCandidate() genuinely recognizes it as belonging to a real,
        // previous, now-gone process rather than this same one -- see persistActiveWithOwnerForTest's doc.
        journal.persistActiveWithOwnerForTest(
            token = "previous-process-token", ownerProcessId = "simulated-previous-process",
            target = OutputTarget.FileTarget(segmentFile), sampleRate = 48000, channels = 1, bitsPerSample = 16
        )

        Robolectric.buildService(RecordingService::class.java).create().get()

        val deadline = System.currentTimeMillis() + 2000
        while (journal.peekRecoveryCandidates().isNotEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(5)

        assertTrue(
            "expected onCreate() to claim the leftover ACTIVE record and run WavRecoveryManager, " +
                "clearing the recovery-pending record once recovery succeeded",
            journal.peekRecoveryCandidates().isEmpty()
        )
        assertNull("the ACTIVE slot itself must also be empty -- it was claimed away, not left behind",
            journal.peekActive())
        val format = WavRiffParser.parse(segmentFile.inputStream())
        assertEquals("expected the header to now declare the real audio bytes found on disk",
            50L, format?.dataSize)
    }

    @Test
    fun `stopRecording returns without waiting for a deliberately blocked recorder stop`() {
        val service = Robolectric.buildService(RecordingService::class.java).create().get()
        val blockForever = CountDownLatch(1) // never released -- source ignores stop()
        val source = object : AudioSource {
            override fun startRecording() {}
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                blockForever.await(5, TimeUnit.SECONDS)
                return -1
            }
            override fun stop() {} // deliberately does NOT unblock read(), standing in for a
            // genuinely wedged driver
            override fun release() {}
        }
        service.recorder = WavRecorder(
            threadJoinTimeoutMs = 2000, // the real production default -- what stopRecording() must not block on
            openAudioSource = { WavRecorder.RecorderConfig(source, sampleRate = 48000, bufferSize = 4) }
        )
        service.startRecording(1L)
        val segmentOpenedDeadline = System.currentTimeMillis() + 2000
        while (service.lastTarget == null && System.currentTimeMillis() < segmentOpenedDeadline) {
            Thread.sleep(5)
            shadowOf(Looper.getMainLooper()).idle()
        }

        val elapsedMs = measureTimeMillis { service.stopRecording() }

        assertTrue(
            "stopRecording() must return promptly rather than blocking anywhere near the " +
                "recorder's own 2000ms join timeout; took ${elapsedMs}ms",
            elapsedMs < 500
        )

        blockForever.countDown() // release the wedged background thread so it doesn't linger past the test
    }

    @Test
    fun `duplicate Stop calls do not launch overlapping finalization work`() {
        val service = Robolectric.buildService(RecordingService::class.java).create().get()
        val chunk = byteArrayOf(1, 2, 3, 4)
        val blockUntilStopped = CountDownLatch(1)
        val stopCallCount = AtomicInteger(0)
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
            override fun stop() {
                stopCallCount.incrementAndGet()
                blockUntilStopped.countDown()
            }
            override fun release() {}
        }
        val segmentFile = tempFolder.newFile("segment.wav")
        service.destinationManager = object : DestinationManager(ApplicationProvider.getApplicationContext()) {
            override fun createOutputFile(fileName: String): OutputTarget = OutputTarget.FileTarget(segmentFile)
        }
        service.recorder = WavRecorder(
            openAudioSource = { WavRecorder.RecorderConfig(source, sampleRate = 48000, bufferSize = chunk.size) }
        )
        var onStoppedCount = 0
        service.listener = object : StubListener() {
            override fun onStopped(lastTarget: OutputTarget?) { onStoppedCount++ }
        }

        service.startRecording(1L)
        val deadline = System.currentTimeMillis() + 2000
        while (reads < 1 && System.currentTimeMillis() < deadline) Thread.sleep(5)
        Thread.sleep(20)

        // A rapid triple-tap of Stop (or a notification-tap racing an in-app tap) -- only the
        // first must actually do anything.
        service.stopRecording()
        service.stopRecording()
        service.stopRecording()
        awaitFinalizationAndIdle(service)

        assertEquals(
            "expected the underlying AudioSource to be stopped exactly once, not once per " +
                "duplicate Stop call",
            1, stopCallCount.get()
        )
        assertEquals("expected exactly one outcome delivery for the duplicate Stop calls combined",
            1, onStoppedCount)
    }

    @Test
    fun `finalization outcome is delivered exactly once and on the main thread`() {
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
            openAudioSource = { WavRecorder.RecorderConfig(source, sampleRate = 48000, bufferSize = chunk.size) }
        )
        var deliveryCount = 0
        var deliveredOnMainThread = false
        service.listener = object : StubListener() {
            override fun onStopped(lastTarget: OutputTarget?) {
                deliveryCount++
                deliveredOnMainThread = Looper.myLooper() == Looper.getMainLooper()
            }
        }

        service.startRecording(1L)
        val deadline = System.currentTimeMillis() + 2000
        while (reads < 1 && System.currentTimeMillis() < deadline) Thread.sleep(5)
        Thread.sleep(20)
        service.stopRecording()
        awaitFinalizationAndIdle(service)

        assertEquals(1, deliveryCount)
        assertTrue("expected the outcome to be delivered on the main thread, not the background " +
            "finalize thread", deliveredOnMainThread)
    }

    // ---- Area 1 (serialized sessions): a new start attempt during FINALIZING is rejected, never
    // silently allowed to open a second, competing WavRecorder session. Supersedes two older tests
    // that asserted the *opposite* -- that a newer session was allowed to start immediately while
    // an older one was still finalizing -- which is exactly the race this service-level state
    // machine (see ServiceState) now closes. ----

    @Test
    fun `a start attempt while a previous session is still finalizing is rejected -- it never opens a new microphone or output file`() {
        val chunk = byteArrayOf(1, 2, 3, 4)
        var session1Reads = 0
        val session1Source = object : AudioSource {
            override fun startRecording() {}
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                session1Reads++
                if (session1Reads == 1) {
                    System.arraycopy(chunk, 0, buffer, offset, chunk.size)
                    return chunk.size
                }
                Thread.sleep(10_000) // never unblocked by stop() -- wedged, stays "finalizing"
                return -1
            }
            override fun stop() {}
            override fun release() {}
        }
        val service = Robolectric.buildService(RecordingService::class.java).create().get()
        var openAudioSourceCallCount = 0
        service.destinationManager = object : DestinationManager(ApplicationProvider.getApplicationContext()) {
            override fun createOutputFile(fileName: String): OutputTarget =
                OutputTarget.FileTarget(tempFolder.newFile("session1.wav"))
        }
        service.recorder = WavRecorder(
            threadJoinTimeoutMs = 300, // short and deterministic: the join gives up quickly, yielding Unknown
            openAudioSource = {
                openAudioSourceCallCount++
                WavRecorder.RecorderConfig(session1Source, sampleRate = 48000, bufferSize = chunk.size)
            }
        )
        var rejectedCount = 0
        var session1UnknownDelivered = false
        service.listener = object : StubListener() {
            override fun onFinalizationUnknown(target: OutputTarget?) { session1UnknownDelivered = true }
            override fun onStartRejected() { rejectedCount++ }
        }

        service.startRecording(1L)
        val session1Deadline = System.currentTimeMillis() + 2000
        while (session1Reads < 1 && System.currentTimeMillis() < session1Deadline) Thread.sleep(5)
        Thread.sleep(20)
        service.stopRecording()
        assertTrue("sanity: session 1 must genuinely still be finalizing", service.isFinalizing)

        // The critical action: attempt a second session while session 1 is still finalizing.
        service.startRecording(2L)

        assertFalse("a start attempt during FINALIZING must never actually begin recording",
            service.isRecording)
        assertEquals("must never open a second microphone/AudioSource while an older session is " +
            "still finalizing", 1, openAudioSourceCallCount)
        assertEquals("currentRequestId must remain session 1's own -- rejecting a start must " +
            "never mutate ownership", 1L, service.currentRequestId)
        assertEquals(1, rejectedCount)

        // Session 1's own finalization must still resolve normally and deliver its real outcome,
        // proving the rejected attempt didn't corrupt or discard it.
        val outcomeDeadline = System.currentTimeMillis() + 3000
        while (!session1UnknownDelivered && System.currentTimeMillis() < outcomeDeadline) {
            Thread.sleep(10)
            shadowOf(Looper.getMainLooper()).idle()
        }
        assertTrue("session 1's own finalization outcome must still be delivered, not discarded",
            session1UnknownDelivered)
        assertFalse(service.isFinalizing)

        // After session 1 has genuinely reached a terminal outcome, a fresh request must succeed.
        service.recorder = blockingRecorder()
        service.startRecording(3L)
        assertTrue("a genuinely new request after the old session reached a terminal outcome " +
            "must succeed normally", service.isRecording)
        service.stopRecording() // cleanup
    }

    @Test
    fun `a FinalizationFailed outcome and its journal record survive a rejected start attempt during finalization`() {
        val chunk = byteArrayOf(1, 2, 3, 4)
        val releaseSession1 = CountDownLatch(1)
        var reads = 0
        val session1Source = object : AudioSource {
            override fun startRecording() {}
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                reads++
                if (reads == 1) {
                    System.arraycopy(chunk, 0, buffer, offset, chunk.size)
                    return chunk.size
                }
                releaseSession1.await(5, TimeUnit.SECONDS)
                return -1
            }
            override fun stop() {} // deliberately does not unblock -- the test controls timing explicitly
            override fun release() {}
        }
        val segmentFile = tempFolder.newFile("failing-session.wav")
        val service = Robolectric.buildService(RecordingService::class.java).create().get()
        service.destinationManager = object : DestinationManager(ApplicationProvider.getApplicationContext()) {
            override fun createOutputFile(fileName: String): OutputTarget = OutputTarget.FileTarget(segmentFile)
        }
        service.recorder = WavRecorder(
            threadJoinTimeoutMs = 5000, // comfortably longer than this test's own explicit release timing
            openAudioSource = { WavRecorder.RecorderConfig(session1Source, sampleRate = 48000, bufferSize = chunk.size) },
            wrapChannel = { channel -> AlwaysFailingHeaderWriter(channel) }
        )
        var finalizationFailedDelivered = false
        service.listener = object : StubListener() {
            override fun onFinalizationFailed(target: OutputTarget?, cause: Exception) { finalizationFailedDelivered = true }
        }

        service.startRecording(1L)
        val deadline = System.currentTimeMillis() + 2000
        while (reads < 1 && System.currentTimeMillis() < deadline) Thread.sleep(5)
        Thread.sleep(20)
        service.stopRecording()
        assertTrue(service.isFinalizing)

        service.startRecording(2L) // rejected -- session 1's own segment/journal record must survive this
        assertFalse(service.isRecording)

        val journal = ActiveSegmentJournal(app())
        assertEquals("session 1's own still-active journal record must be completely undisturbed " +
            "by the rejected start attempt", segmentFile.absolutePath,
            (journal.peekActive()?.target as? OutputTarget.FileTarget)?.file?.absolutePath)

        releaseSession1.countDown() // now let session 1 actually finish finalizing (and fail to patch its header)
        awaitFinalizationAndIdle(service)

        assertTrue("expected FinalizationFailed to still be delivered for session 1", finalizationFailedDelivered)
        assertNotNull("a failed finalization must still leave its journal record in place for a " +
            "later recovery pass -- it must never be lost", journal.peekActive())
        assertEquals(segmentFile.absolutePath,
            (journal.peekActive()?.target as? OutputTarget.FileTarget)?.file?.absolutePath)
    }

    @Test
    fun `a FinalizationUnknown outcome survives a rejected start attempt during finalization`() {
        val chunk = byteArrayOf(1, 2, 3, 4)
        val blockForever = CountDownLatch(1) // never released -- the thread stays wedged past the join timeout
        val session1Source = object : AudioSource {
            override fun startRecording() {}
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                blockForever.await(5, TimeUnit.SECONDS)
                return -1
            }
            override fun stop() {}
            override fun release() {}
        }
        val segmentFile = tempFolder.newFile("unknown-session.wav")
        val service = Robolectric.buildService(RecordingService::class.java).create().get()
        service.destinationManager = object : DestinationManager(ApplicationProvider.getApplicationContext()) {
            override fun createOutputFile(fileName: String): OutputTarget = OutputTarget.FileTarget(segmentFile)
        }
        service.recorder = WavRecorder(
            threadJoinTimeoutMs = 200,
            openAudioSource = { WavRecorder.RecorderConfig(session1Source, sampleRate = 48000, bufferSize = chunk.size) }
        )
        var unknownDelivered = false
        var unknownTarget: OutputTarget? = null
        service.listener = object : StubListener() {
            override fun onFinalizationUnknown(target: OutputTarget?) {
                unknownDelivered = true
                unknownTarget = target
            }
        }

        service.startRecording(1L)
        val segmentOpenedDeadline = System.currentTimeMillis() + 2000
        while (service.lastTarget == null && System.currentTimeMillis() < segmentOpenedDeadline) {
            Thread.sleep(5)
            shadowOf(Looper.getMainLooper()).idle()
        }
        service.stopRecording()
        assertTrue(service.isFinalizing)

        service.startRecording(2L) // rejected while still finalizing
        assertFalse(service.isRecording)

        awaitFinalizationAndIdle(service)

        assertTrue("expected FinalizationUnknown to still be delivered for session 1", unknownDelivered)
        assertEquals(segmentFile.absolutePath, (unknownTarget as? OutputTarget.FileTarget)?.file?.absolutePath)

        blockForever.countDown() // release the wedged background thread so it doesn't linger past the test
    }

    @Test
    fun `an ACTION_START arriving during FINALIZING cannot change the old session's request id or make its result stale`() {
        val chunk = byteArrayOf(1, 2, 3, 4)
        var reads = 0
        val session1Source = object : AudioSource {
            override fun startRecording() {}
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                reads++
                if (reads == 1) {
                    System.arraycopy(chunk, 0, buffer, offset, chunk.size)
                    return chunk.size
                }
                Thread.sleep(10_000)
                return -1
            }
            override fun stop() {}
            override fun release() {}
        }
        val controller = Robolectric.buildService(RecordingService::class.java)
        val service = controller.create().get()
        service.destinationManager = object : DestinationManager(ApplicationProvider.getApplicationContext()) {
            override fun createOutputFile(fileName: String): OutputTarget = OutputTarget.FileTarget(tempFolder.newFile("s1.wav"))
        }
        service.recorder = WavRecorder(
            threadJoinTimeoutMs = 200,
            openAudioSource = { WavRecorder.RecorderConfig(session1Source, sampleRate = 48000, bufferSize = chunk.size) }
        )
        var rejectedCount = 0
        var unknownDelivered = false
        service.listener = object : StubListener() {
            override fun onFinalizationUnknown(target: OutputTarget?) { unknownDelivered = true }
            override fun onStartRejected() { rejectedCount++ }
        }

        service.startRecording(1L)
        val deadline = System.currentTimeMillis() + 2000
        while (reads < 1 && System.currentTimeMillis() < deadline) Thread.sleep(5)
        Thread.sleep(20)
        service.stopRecording()
        assertTrue(service.isFinalizing)

        // A brand new ACTION_START Intent (not a direct startRecording() call) for an unrelated,
        // much higher id arrives while session 1 is still finalizing.
        controller.withIntent(startIntent(99L)).startCommand(0, 1)

        assertEquals("an ACTION_START during FINALIZING must never overwrite currentRequestId -- " +
            "the still-in-flight background join checks this to decide whether its own outcome " +
            "is still current", 1L, service.currentRequestId)
        assertFalse("must never enter a pending state for the rejected attempt", service.startRequestPending)
        assertEquals(1, rejectedCount)

        awaitFinalizationAndIdle(service)
        assertTrue("session 1's outcome must not have been made stale by the rejected ACTION_START",
            unknownDelivered)
    }

    @Test
    fun `rapid repeated start attempts during finalization never open more than one AudioSource`() {
        val chunk = byteArrayOf(1, 2, 3, 4)
        var reads = 0
        val session1Source = object : AudioSource {
            override fun startRecording() {}
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                reads++
                if (reads == 1) {
                    System.arraycopy(chunk, 0, buffer, offset, chunk.size)
                    return chunk.size
                }
                Thread.sleep(10_000)
                return -1
            }
            override fun stop() {}
            override fun release() {}
        }
        val service = Robolectric.buildService(RecordingService::class.java).create().get()
        var openAudioSourceCallCount = 0
        service.destinationManager = object : DestinationManager(ApplicationProvider.getApplicationContext()) {
            override fun createOutputFile(fileName: String): OutputTarget = OutputTarget.FileTarget(tempFolder.newFile("s1.wav"))
        }
        service.recorder = WavRecorder(
            threadJoinTimeoutMs = 200,
            openAudioSource = {
                openAudioSourceCallCount++
                WavRecorder.RecorderConfig(session1Source, sampleRate = 48000, bufferSize = chunk.size)
            }
        )
        service.startRecording(1L)
        val deadline = System.currentTimeMillis() + 2000
        while (reads < 1 && System.currentTimeMillis() < deadline) Thread.sleep(5)
        Thread.sleep(20)
        service.stopRecording()
        assertTrue(service.isFinalizing)

        // A rapid burst of Record taps landing while session 1 is still finalizing.
        repeat(5) { service.startRecording(100L + it) }

        assertFalse(service.isRecording)
        assertEquals("every rapid start attempt during FINALIZING must be rejected -- never more " +
            "than the one already-open AudioSource from session 1 itself", 1, openAudioSourceCallCount)

        awaitFinalizationAndIdle(service)
    }

    @Test
    fun `ACTION_STOP from the notification and a direct stopRecording call from the UI follow the same path`() {
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
        val service = Robolectric.buildService(RecordingService::class.java).create().get()
        service.destinationManager = object : DestinationManager(ApplicationProvider.getApplicationContext()) {
            override fun createOutputFile(fileName: String): OutputTarget = OutputTarget.FileTarget(segmentFile)
        }
        service.recorder = WavRecorder(
            openAudioSource = { WavRecorder.RecorderConfig(source, sampleRate = 48000, bufferSize = chunk.size) }
        )
        var onStoppedCalled = false
        service.listener = object : StubListener() {
            override fun onStopped(lastTarget: OutputTarget?) { onStoppedCalled = true }
        }

        service.startRecording(1L)
        val deadline = System.currentTimeMillis() + 2000
        while (reads < 1 && System.currentTimeMillis() < deadline) Thread.sleep(5)
        Thread.sleep(20)

        val elapsedMs = measureTimeMillis {
            // Exactly what the notification's Stop action's PendingIntent triggers.
            service.onStartCommand(Intent(app(), RecordingService::class.java).setAction("com.example.wavrecorder.action.STOP"), 0, 1)
        }

        assertTrue("ACTION_STOP must go through the same async, non-blocking path as a direct " +
            "stopRecording() call", elapsedMs < 500)
        awaitFinalizationAndIdle(service)
        assertTrue(onStoppedCalled)
        assertFalse(service.isRecording)
    }

    @Test
    fun `a synchronous microphone-open failure produces a plain Failed outcome, never FailedButSaved without a file`() {
        val service = Robolectric.buildService(RecordingService::class.java).create().get()
        service.recorder = alwaysFailsSynchronously()
        // Deliberately no listener attached, so the outcome is durably persisted and can be
        // inspected directly as a RecordingOutcome (Listener.onError can't distinguish Failed
        // from FailedButSaved -- both map to the same callback -- so this is the only way to
        // assert on the actual outcome type).

        service.startRecording(1L)
        awaitFinalizationAndIdle(service)

        val outcome = service.consumePendingOutcome()
        assertTrue("expected a plain Failed outcome (no segment ever existed), got: $outcome",
            outcome is RecordingOutcome.Failed)
        assertFalse("must never be classified as FailedButSaved when no file was ever created",
            outcome is RecordingOutcome.FailedButSaved)
    }

    @Test
    fun `onDestroy does not block the calling thread waiting for the recording thread join timeout`() {
        val blockForever = CountDownLatch(1) // never released -- source ignores stop()
        val source = object : AudioSource {
            override fun startRecording() {}
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                blockForever.await(5, TimeUnit.SECONDS)
                return -1
            }
            override fun stop() {} // deliberately does not unblock read()
            override fun release() {}
        }
        val controller = Robolectric.buildService(RecordingService::class.java)
        val service = controller.create().get()
        service.recorder = WavRecorder(
            threadJoinTimeoutMs = 2000, // the real production default
            openAudioSource = { WavRecorder.RecorderConfig(source, sampleRate = 48000, bufferSize = 4) }
        )
        service.startRecording(1L)
        val deadline = System.currentTimeMillis() + 2000
        while (service.lastTarget == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
            shadowOf(Looper.getMainLooper()).idle()
        }
        assertTrue("sanity: expected recording to actually be active before destroying the service",
            service.isRecording)

        val elapsedMs = measureTimeMillis { controller.destroy() }

        assertTrue(
            "onDestroy() must not block anywhere near the recorder's own 2000ms join timeout; " +
                "took ${elapsedMs}ms",
            elapsedMs < 500
        )

        blockForever.countDown() // release the wedged background thread so it doesn't linger past the test
    }

    @Test
    fun `Failed, FinalizationFailed, and FinalizationUnknown outcomes are each delivered on the main thread exactly once`() {
        // FinalizationFailed case.
        run {
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
            service.destinationManager = object : DestinationManager(ApplicationProvider.getApplicationContext()) {
                override fun createOutputFile(fileName: String): OutputTarget =
                    OutputTarget.FileTarget(tempFolder.newFile("failed.wav"))
            }
            service.recorder = WavRecorder(
                openAudioSource = { WavRecorder.RecorderConfig(source, sampleRate = 48000, bufferSize = chunk.size) },
                wrapChannel = { channel -> AlwaysFailingHeaderWriter(channel) }
            )
            var deliveryCount = 0
            var onMainThread = false
            service.listener = object : StubListener() {
                override fun onFinalizationFailed(target: OutputTarget?, cause: Exception) {
                    deliveryCount++
                    onMainThread = Looper.myLooper() == Looper.getMainLooper()
                }
            }
            service.startRecording(1L)
            val deadline = System.currentTimeMillis() + 2000
            while (reads < 1 && System.currentTimeMillis() < deadline) Thread.sleep(5)
            Thread.sleep(20)
            service.stopRecording()
            awaitFinalizationAndIdle(service)
            assertEquals(1, deliveryCount)
            assertTrue(onMainThread)
        }
        // This block's own segment failed to finalize, so its journal ACTIVE record was
        // deliberately never cleared (see WavRecorder.closeSegment's doc) -- and since every
        // `run{}` block here shares one Application/journal within this single @Test method
        // (not genuinely separate processes, the boundary persistActive()'s protection actually
        // cares about), the next block's own fresh service would otherwise be correctly refused
        // when it tries to persist its own first segment. Clearing here simulates the record
        // having already been dealt with (recovered, or genuinely a different process) before the
        // next scenario begins -- not a workaround for a bug, but for this test's own deliberately
        // shared state.
        ActiveSegmentJournal(app()).clearActiveForTest()

        // FinalizationUnknown case.
        run {
            val service = Robolectric.buildService(RecordingService::class.java).create().get()
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
            service.destinationManager = object : DestinationManager(ApplicationProvider.getApplicationContext()) {
                override fun createOutputFile(fileName: String): OutputTarget =
                    OutputTarget.FileTarget(tempFolder.newFile("unknown.wav"))
            }
            service.recorder = WavRecorder(
                threadJoinTimeoutMs = 50,
                openAudioSource = { WavRecorder.RecorderConfig(source, sampleRate = 48000, bufferSize = 4) }
            )
            var deliveryCount = 0
            var onMainThread = false
            service.listener = object : StubListener() {
                override fun onFinalizationUnknown(target: OutputTarget?) {
                    deliveryCount++
                    onMainThread = Looper.myLooper() == Looper.getMainLooper()
                }
            }
            service.startRecording(1L)
            val deadline = System.currentTimeMillis() + 2000
            while (service.lastTarget == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(5)
                shadowOf(Looper.getMainLooper()).idle()
            }
            service.stopRecording()
            awaitFinalizationAndIdle(service)
            assertEquals(1, deliveryCount)
            assertTrue(onMainThread)
            blockForever.countDown()
        }
        // This block's own segment never resolved as a normal clean stop either (the join timed
        // out as Unknown) -- see the identical note above for why this reset is needed between
        // blocks that deliberately share one Application/journal.
        ActiveSegmentJournal(app()).clearActiveForTest()

        // Failed case (synchronous mic-open failure, no listener -- checked via consumePendingOutcome).
        run {
            val service = Robolectric.buildService(RecordingService::class.java).create().get()
            service.recorder = alwaysFailsSynchronously()
            service.startRecording(1L)
            awaitFinalizationAndIdle(service)
            assertTrue(service.consumePendingOutcome() is RecordingOutcome.Failed)
        }
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

    /** Covers the foreground "Recording…" channel/notification/Stop-action itself -- the one piece
     * of notification behavior removing the terminal-outcome notification must never regress --
     * independent of the (now-deleted) result channel/notification this file used to also test. */
    @Test
    fun `the foreground recording channel exists with low importance after the service is created`() {
        val service = Robolectric.buildService(RecordingService::class.java).create().get()
        val manager = app().getSystemService(NotificationManager::class.java)

        val channel = manager.getNotificationChannel("recording_channel")

        assertNotNull("expected the foreground recording channel to exist", channel)
        assertEquals(
            "the ongoing-recording channel must stay silent/low-importance, unlike the removed result channel",
            NotificationManager.IMPORTANCE_LOW, channel!!.importance
        )
        service.onDestroy()
    }

    @Test
    fun `the active recording notification is ongoing, on the recording channel, and offers a Stop action`() {
        val service = Robolectric.buildService(RecordingService::class.java).create().get()
        val source = object : AudioSource {
            override fun startRecording() {}
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int { Thread.sleep(20); return 4 }
            override fun stop() {}
            override fun release() {}
        }
        service.recorder = WavRecorder(
            openAudioSource = { WavRecorder.RecorderConfig(source, sampleRate = 48000, bufferSize = 4) }
        )

        service.startRecording(1L)

        val posted = app().getSystemService(NotificationManager::class.java).activeNotifications
            .firstOrNull { it.id == 1001 }
        assertNotNull("expected the foreground recording notification (id 1001) to be active", posted)
        val notification = posted!!.notification
        assertEquals("recording_channel", notification.channelId)
        assertTrue(
            "the recording notification must stay ongoing (undismissable) while capture is active",
            notification.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0
        )
        assertEquals(
            app().getString(R.string.status_recording),
            notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString()
        )
        assertEquals(
            "expected exactly one action (Stop) on the active recording notification",
            1, notification.actions?.size ?: 0
        )
        assertEquals(
            app().getString(R.string.stop_recording),
            notification.actions[0].title.toString()
        )
        service.stopRecording()
        awaitFinalizationAndIdle(service)
    }

    @Test
    fun `a session that ends with no listener attached persists its outcome without raising any notification`() {
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
        awaitFinalizationAndIdle(service)

        val pending = service.consumePendingOutcome()
        assertTrue("expected a Saved outcome to be persisted for a later bind to pick up",
            pending is RecordingOutcome.Saved)
        assertNull("consumePendingOutcome must clear the outcome so it isn't redelivered",
            service.consumePendingOutcome())

        // finishRecording() already removed the foreground notification by the time finalization
        // completes above -- an unattended recording must leave zero active notifications behind,
        // not raise a replacement "terminal outcome" one (see LegacyNotificationCleanup for why
        // that was removed).
        assertTrue(
            "an unattended recording must not raise any notification, got: ${postedNotificationTitles()}",
            postedNotificationTitles().isEmpty()
        )
    }

    @Test
    fun `a session that ends with a listener attached does not persist an outcome or raise any notification`() {
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
        awaitFinalizationAndIdle(service)

        assertNull("no outcome should be persisted when a listener was attached to receive it live",
            service.consumePendingOutcome())
        assertTrue(
            "no notification should remain once a live-delivered session's foreground notification is removed",
            postedNotificationTitles().isEmpty()
        )
    }

    @Test
    fun `live outcome delivery still reports this session's own outcome even when clearing an older stale one fails, and logs it`() {
        ShadowLog.stream = null
        ShadowLog.clear()
        val service = Robolectric.buildService(RecordingService::class.java).create().get()
        // A pending outcome store whose commitEditor seam calls the real editor.commit() (so its
        // in-memory mutation is genuine) and then forces the caller-visible result to false -- see
        // PendingOutcomeStoreTest's identical falseReportingCommitStore() for why this (not a
        // hand-rolled fake) is the right way to exercise same-process behavior after a false
        // result. This does not reproduce an actual failed disk write or process restart.
        val failingStore = PendingOutcomeStore(app(), commitEditor = { editor -> editor.commit(); false })
        // Simulates a genuinely stale, still-unconsumed outcome from an *older*, unrelated session
        // that nobody ever picked up.
        failingStore.persist(11L, RecordingOutcome.FinalizationUnknown(null), null)
        service.pendingOutcomeStore = failingStore

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
        var stoppedTarget: OutputTarget? = null
        var onStoppedCalled = false
        service.listener = object : StubListener() {
            override fun onStopped(lastTarget: OutputTarget?) {
                onStoppedCalled = true
                stoppedTarget = lastTarget
            }
        }

        service.startRecording(1L)
        val segmentOpenedDeadline = System.currentTimeMillis() + 2000
        while (service.lastTarget == null && System.currentTimeMillis() < segmentOpenedDeadline) {
            Thread.sleep(5)
            shadowOf(Looper.getMainLooper()).idle()
        }
        service.stopRecording()
        awaitFinalizationAndIdle(service)

        assertTrue("this session's own outcome must still be delivered live, never blocked by a " +
            "failure clearing a different, older stale record", onStoppedCalled)
        assertNotNull(stoppedTarget)
        val warnings = ShadowLog.getLogsForTag("RecordingService").filter { it.type == Log.WARN }
        assertTrue("expected a warning naming the stale session's id when clearing it fails, got: " +
            "${ShadowLog.getLogs().map { it.msg }}",
            warnings.any { it.msg.contains("11") })
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
        shadowOf(Looper.getMainLooper()).idle() // delivers the posted onError(e), which kicks off beginAsyncFinalize
        awaitFinalizationAndIdle(service) // then waits for its own background join + delivers its result

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
        awaitFinalizationAndIdle(service1)

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
        // The synchronous mic-open failure inside recorder.start() already triggered onError ->
        // beginAsyncFinalize() before startRecording() returned, but that call's own
        // finishRecording() (and thus the self-stop) is delivered asynchronously via
        // mainHandler.post -- see beginAsyncFinalize()'s doc for why a second, synchronous
        // finishRecording() call right here was removed (it used to race the async one).
        awaitFinalizationAndIdle(service)
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
        // The synchronous failure's own beginAsyncFinalize() still runs its (here, effectively
        // instant, since there's no real recording thread to join) background completion
        // asynchronously -- see beginAsyncFinalize()'s doc. Attempt 1 must reach its own terminal
        // state (state != FINALIZING) before a retry is accepted; a retry arriving in that brief
        // window is correctly rejected by the same FINALIZING guard that protects every other
        // still-finalizing session, exactly like `a delayed ACTION_START for an attempt that
        // already failed synchronously is ignored` above -- this test is about a genuinely new,
        // higher id being accepted only once attempt 1 is actually done, not about racing it.
        awaitFinalizationAndIdle(service)

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
