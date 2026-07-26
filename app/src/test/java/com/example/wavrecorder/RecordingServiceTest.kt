package com.example.wavrecorder

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
        service.startRecording()
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
        service.startRecording()
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

        service.startRecording()
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

        service.startRecording()
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

        service.startRecording()
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
}
