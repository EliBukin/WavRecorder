package com.example.wavrecorder

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** A fake [AudioSource] that hands back a scripted sequence of PCM buffers, then signals
 * exhaustion and ends the recording loop — standing in for a real microphone so segment
 * rollover and failure handling can be tested without touching AudioRecord. */
private class FakeAudioSource(
    private val scriptedReads: List<ByteArray> = emptyList(),
    private val onExhausted: () -> Unit = {},
    private val startRecordingException: Exception? = null,
    private val readException: Exception? = null
) : AudioSource {
    var startCalled = false
        private set
    var releaseCalled = false
        private set
    var stopCalled = false
        private set
    private var index = 0

    override fun startRecording() {
        startRecordingException?.let { throw it }
        startCalled = true
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        readException?.let { throw it }
        if (index >= scriptedReads.size) {
            onExhausted()
            return -1
        }
        val chunk = scriptedReads[index++]
        System.arraycopy(chunk, 0, buffer, offset, chunk.size)
        return chunk.size
    }

    override fun stop() {
        stopCalled = true
    }

    override fun release() {
        releaseCalled = true
    }
}

@RunWith(RobolectricTestRunner::class)
class WavRecorderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    /** Waits (without touching the main looper) for the background recording thread to flip
     * [WavRecorder.isActive] false after a fatal error, then drains the main looper once so any
     * `handler.post { onError(...) }` from that thread gets to run. */
    private fun awaitTerminatedAndDeliverCallbacks(recorder: WavRecorder, timeoutMs: Long = 2000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (recorder.isActive && System.currentTimeMillis() < deadline) Thread.sleep(5)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `crossing the segment size rolls over to a new file each time`() {
        // sampleRate=1 and segmentMaxSeconds=1 makes segmentMaxBytes = 1 * 1 channel * 2
        // bytes/sample * 1s = 2 bytes, so every 4-byte chunk below immediately triggers rollover.
        val chunk = byteArrayOf(1, 2, 3, 4)
        val doneLatch = CountDownLatch(1)
        val fake = FakeAudioSource(scriptedReads = listOf(chunk, chunk), onExhausted = { doneLatch.countDown() })

        val segmentFiles = mutableListOf<File>()
        val nextTarget = WavRecorder.NextTarget {
            val file = tempFolder.newFile("segment${segmentFiles.size + 1}.wav")
            segmentFiles += file
            OutputTarget.FileTarget(file)
        }

        val recorder = WavRecorder(
            segmentMaxSeconds = 1,
            openAudioSource = { WavRecorder.RecorderConfig(fake, sampleRate = 1, bufferSize = chunk.size) }
        )

        recorder.start(
            context = ApplicationProvider.getApplicationContext(),
            nextTarget = nextTarget,
            onSegmentStarted = {},
            onAmplitude = {},
            onError = {}
        )

        assertTrue("recording loop never finished", doneLatch.await(2, TimeUnit.SECONDS))
        // The exhausted signal fires from inside the last read() call; give the same background
        // thread a moment to fall through its while loop and finish closing/deleting that segment.
        Thread.sleep(50)

        // 2 chunks each triggering a rollover => the initial segment, one rollover, plus a 3rd
        // segment opened for the read that signalled the end and never got any audio written to it.
        assertEquals(3, segmentFiles.size)

        val firstSegment = WavRiffParser.parse(segmentFiles[0].inputStream())
        assertEquals(chunk.size.toLong(), firstSegment?.dataSize)
        val secondSegment = WavRiffParser.parse(segmentFiles[1].inputStream())
        assertEquals(chunk.size.toLong(), secondSegment?.dataSize)

        // The 3rd segment never received any audio before recording ended, so it must not be
        // left behind as a pointless, silent 0-byte-audio WAV file in the user's library.
        assertFalse("empty trailing segment should have been deleted", segmentFiles[2].exists())
    }

    @Test
    fun `stopping before any audio is ever read deletes the empty segment`() {
        val fake = FakeAudioSource() // no scripted reads: exhausted (returns -1) on the very first call
        val segmentFile = tempFolder.newFile("segment.wav")
        val recorder = WavRecorder(
            openAudioSource = { WavRecorder.RecorderConfig(fake, sampleRate = 48000, bufferSize = 4) }
        )

        recorder.start(
            context = ApplicationProvider.getApplicationContext(),
            nextTarget = WavRecorder.NextTarget { OutputTarget.FileTarget(segmentFile) },
            onSegmentStarted = {},
            onAmplitude = {},
            onError = {}
        )

        awaitTerminatedAndDeliverCallbacks(recorder)

        assertFalse("a segment with zero audio bytes should be deleted, not left as a silent file",
            segmentFile.exists())
    }

    @Test
    fun `a failure from AudioSource read() is caught, cleaned up, and reported`() {
        val failure = IOException("device disconnected")
        val fake = FakeAudioSource(readException = failure)
        val segmentFile = tempFolder.newFile("segment.wav")
        val recorder = WavRecorder(
            openAudioSource = { WavRecorder.RecorderConfig(fake, sampleRate = 48000, bufferSize = 4) }
        )

        var reportedError: Exception? = null
        recorder.start(
            context = ApplicationProvider.getApplicationContext(),
            nextTarget = WavRecorder.NextTarget { OutputTarget.FileTarget(segmentFile) },
            onSegmentStarted = {},
            onAmplitude = {},
            onError = { e -> reportedError = e }
        )

        awaitTerminatedAndDeliverCallbacks(recorder)

        assertEquals(failure, reportedError)
        assertFalse("isActive must not remain true after a fatal read() failure", recorder.isActive)
        // Mirrors what RecordingService actually does in its onError handler: call stop() to
        // finish releasing the mic. Confirms that's safe and effective after a mid-loop failure.
        recorder.stop()
        assertTrue("the AudioSource must be released once stop() runs", fake.releaseCalled)
    }

    @Test
    fun `a failure opening the destination for a new segment mid-recording is caught, cleaned up, and reported`() {
        // A genuine mid-write OS-level failure (disk full, revoked storage permission, an
        // unplugged SD card) isn't reliably reproducible across platforms in a unit test. Making
        // the *next* segment's destination itself impossible to open (a directory can never be
        // opened as a file) exercises the exact same code path and the exact same catch block in
        // recordLoop() that a write() failure would: segment creation and in-progress writes are
        // deliberately funneled through one unified try/catch there.
        val chunk = byteArrayOf(1, 2, 3, 4)
        val goodFile = tempFolder.newFile("segment1.wav")
        val undoableTarget = tempFolder.newFolder("not-a-file")
        var createCount = 0
        val nextTarget = WavRecorder.NextTarget {
            createCount++
            OutputTarget.FileTarget(if (createCount == 1) goodFile else undoableTarget)
        }
        // sampleRate=1 + segmentMaxSeconds=1 => segmentMaxBytes=2, so the first 4-byte chunk
        // immediately triggers a rollover into the broken second target.
        val fake = FakeAudioSource(scriptedReads = listOf(chunk, chunk))
        val recorder = WavRecorder(
            segmentMaxSeconds = 1,
            openAudioSource = { WavRecorder.RecorderConfig(fake, sampleRate = 1, bufferSize = chunk.size) }
        )

        var reportedError: Exception? = null
        recorder.start(
            context = ApplicationProvider.getApplicationContext(),
            nextTarget = nextTarget,
            onSegmentStarted = {},
            onAmplitude = {},
            onError = { e -> reportedError = e }
        )

        awaitTerminatedAndDeliverCallbacks(recorder)

        assertNotNull("expected the broken second segment's open failure to be reported", reportedError)
        assertFalse(recorder.isActive)
        // The first segment had real audio and a working destination, so it must survive intact
        // even though the recording as a whole ended in failure right after it.
        val firstSegment = WavRiffParser.parse(goodFile.inputStream())
        assertEquals(chunk.size.toLong(), firstSegment?.dataSize)
    }

    /** Wraps a real [FileChannel] but fails every [write], standing in for a WAV-header write
     * that fails partway through segment creation (disk full, a broken destination) without
     * needing an OS-level failure that's hard to reproduce portably. */
    private class FailingWriteWrapper(private val channel: FileChannel) : SegmentWriter {
        override fun write(buffer: ByteBuffer): Int = throw IOException("simulated header write failure")
        override fun position(): Long = channel.position()
        override fun position(newPosition: Long) { channel.position(newPosition) }
        override fun close() = channel.close()
    }

    /** Fails only the seek-back-to-0 that [WavRecorder] always does immediately before (re)writing
     * the WAV header -- regular audio writes never reposition -- so this simulates a header patch
     * failure (periodic or final) without touching normal data writes at all. */
    private class FailingHeaderPatchWriter(private val channel: FileChannel) : SegmentWriter {
        override fun write(buffer: ByteBuffer): Int = channel.write(buffer)
        override fun position(): Long = channel.position()
        override fun position(newPosition: Long) {
            if (newPosition == 0L) throw IOException("simulated header patch failure")
            channel.position(newPosition)
        }
        override fun close() = channel.close()
    }

    /** Simulates a writer/destination that reports a close failure -- e.g. a network-backed SAF
     * provider failing to flush on close -- while still actually releasing the real channel
     * underneath so the test itself doesn't leak a file handle. */
    private class FailingCloseWriter(private val channel: FileChannel) : SegmentWriter {
        override fun write(buffer: ByteBuffer): Int = channel.write(buffer)
        override fun position(): Long = channel.position()
        override fun position(newPosition: Long) { channel.position(newPosition) }
        override fun close() {
            channel.close()
            throw IOException("simulated close failure")
        }
    }

    @Test
    fun `a failure while opening the first segment closes resources and deletes the incomplete file`() {
        // Regression test for openSegment(): before its resource cleanup was added, a failure
        // partway through construction (here, the placeholder WAV header write) left the
        // just-opened RandomAccessFile/FileChannel unclosed and the empty/partial file it had
        // already created sitting in the user's library. On Windows specifically, a leaked
        // RandomAccessFile holds an exclusive lock, so segmentFile.delete() below would itself
        // fail (rather than just leave a stray file behind) if the channel weren't closed first
        // -- making this assertion a direct check of the resource cleanup, not just the deletion.
        val segmentFile = tempFolder.newFile("segment.wav")
        val fake = FakeAudioSource() // recording never gets past opening the first segment
        val recorder = WavRecorder(
            openAudioSource = { WavRecorder.RecorderConfig(fake, sampleRate = 48000, bufferSize = 4) },
            wrapChannel = { channel -> FailingWriteWrapper(channel) }
        )

        var reportedError: Exception? = null
        recorder.start(
            context = ApplicationProvider.getApplicationContext(),
            nextTarget = WavRecorder.NextTarget { OutputTarget.FileTarget(segmentFile) },
            onSegmentStarted = {},
            onAmplitude = {},
            onError = { e -> reportedError = e }
        )

        awaitTerminatedAndDeliverCallbacks(recorder)

        assertNotNull("expected the header-write failure to be reported", reportedError)
        assertFalse(recorder.isActive)
        assertFalse("the incomplete segment file must be deleted, not left behind as a partial WAV",
            segmentFile.exists())
    }

    @Test
    fun `a failure from AudioSource startRecording is caught, released, and reported`() {
        val failure = IllegalStateException("mic busy")
        val fake = FakeAudioSource(startRecordingException = failure)
        val recorder = WavRecorder(
            openAudioSource = { WavRecorder.RecorderConfig(fake, sampleRate = 48000, bufferSize = 4) }
        )

        var reportedError: Exception? = null
        recorder.start(
            context = ApplicationProvider.getApplicationContext(),
            nextTarget = WavRecorder.NextTarget {
                throw AssertionError("no segment should be opened if startRecording() never succeeds")
            },
            onSegmentStarted = {},
            onAmplitude = {},
            onError = { e -> reportedError = e }
        )

        assertEquals(failure, reportedError)
        assertFalse("isActive must not remain true after a failed startRecording()", recorder.isActive)
        assertTrue("the AudioSource must be released on startup failure", fake.releaseCalled)
    }

    @Test
    fun `stop() releases the AudioSource before joining the recording thread`() {
        // Regression test for stop() ordering: if AudioSource#stop() were called *after* joining
        // the recording thread, a read() that blocks forever (a hung driver/disconnected mic)
        // would hang stop() itself. Here read() blocks until the fake's stop() is called, which
        // is exactly what a real AudioRecord does to unblock a pending read -- so stop() must
        // call it first, or this test times out.
        val blockUntilStopped = CountDownLatch(1)
        val source = object : AudioSource {
            override fun startRecording() {}
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                blockUntilStopped.await(5, TimeUnit.SECONDS)
                return -1
            }
            override fun stop() {
                blockUntilStopped.countDown()
            }
            override fun release() {}
        }
        val recorder = WavRecorder(
            openAudioSource = { WavRecorder.RecorderConfig(source, sampleRate = 48000, bufferSize = 4) }
        )
        recorder.start(
            context = ApplicationProvider.getApplicationContext(),
            nextTarget = WavRecorder.NextTarget { OutputTarget.FileTarget(tempFolder.newFile("segment.wav")) },
            onSegmentStarted = {},
            onAmplitude = {},
            onError = {}
        )

        val stopCompleted = CountDownLatch(1)
        Thread { recorder.stop(); stopCompleted.countDown() }.start()

        // Correct ordering unblocks the read() near-instantly. This is intentionally much
        // tighter than WavRecorder's own internal join timeout (2s): if stop() ever regresses to
        // joining before stopping the source, this fails fast instead of coincidentally sneaking
        // in under that internal timeout.
        assertTrue("stop() should return promptly once the blocked read() is released",
            stopCompleted.await(500, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `an intentional stop that unblocks a pending read does not report onError`() {
        // Stopping/releasing the AudioSource to unblock a pending read (see the ordering test
        // above) means that read() then returns an error code or throws -- exactly like a real
        // failure would. Without checking whether the stop was intentional, the recording loop
        // would report this as onError right after (or even instead of) the normal "stopped"
        // path the caller (RecordingService) already runs.
        val chunk = byteArrayOf(1, 2, 3, 4)
        var readCount = 0
        val blockUntilStopped = CountDownLatch(1)
        val stopWasCalled = AtomicBoolean(false)
        val source = object : AudioSource {
            override fun startRecording() {}
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                readCount++
                if (readCount == 1) {
                    System.arraycopy(chunk, 0, buffer, offset, chunk.size)
                    return chunk.size
                }
                blockUntilStopped.await(5, TimeUnit.SECONDS)
                return -1
            }
            override fun stop() {
                stopWasCalled.set(true)
                blockUntilStopped.countDown()
            }
            override fun release() {}
        }

        val segmentFile = tempFolder.newFile("segment.wav")
        val recorder = WavRecorder(
            openAudioSource = { WavRecorder.RecorderConfig(source, sampleRate = 48000, bufferSize = chunk.size) }
        )

        var errorReported: Exception? = null
        recorder.start(
            context = ApplicationProvider.getApplicationContext(),
            nextTarget = WavRecorder.NextTarget { OutputTarget.FileTarget(segmentFile) },
            onSegmentStarted = {},
            onAmplitude = {},
            onError = { e -> errorReported = e }
        )

        val deadline = System.currentTimeMillis() + 2000
        while (readCount < 1 && System.currentTimeMillis() < deadline) Thread.sleep(5)
        Thread.sleep(20) // let the first read's write land before stopping

        val stopCompleted = CountDownLatch(1)
        Thread { recorder.stop(); stopCompleted.countDown() }.start()
        assertTrue(stopCompleted.await(2, TimeUnit.SECONDS))
        assertTrue(stopWasCalled.get())

        shadowOf(Looper.getMainLooper()).idle()

        assertNull("an intentional stop must never be reported through onError", errorReported)
        assertFalse(recorder.isActive)

        // The segment with real audio must still be finalized normally -- only the ordinary
        // stopped/saved path should have run, not any error cleanup.
        val finalized = WavRiffParser.parse(segmentFile.inputStream())
        assertEquals(chunk.size.toLong(), finalized?.dataSize)
    }

    @Test
    fun `a stale thread that outlives stop()'s join timeout cannot corrupt a newer session`() {
        val chunk = byteArrayOf(1, 2, 3, 4)

        // Session 1's AudioSource ignores stop()/release() and even interruption, standing in
        // for a genuinely wedged driver -- worse than the "stop() unblocks it" happy path
        // covered elsewhere -- and only lets go once the test explicitly says so.
        var session1ReadCount = 0
        val session1StopCalled = AtomicBoolean(false)
        val letSession1Resume = CountDownLatch(1)
        val session1Source = object : AudioSource {
            override fun startRecording() {}
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                session1ReadCount++
                if (session1ReadCount == 1) {
                    System.arraycopy(chunk, 0, buffer, offset, chunk.size)
                    return chunk.size
                }
                var released = false
                while (!released) {
                    released = try { letSession1Resume.await(5, TimeUnit.SECONDS); true } catch (_: InterruptedException) { false }
                }
                return -1
            }
            override fun stop() {
                session1StopCalled.set(true) // deliberately does NOT unblock read()
            }
            override fun release() {}
        }

        // Session 2's AudioSource: a normal, healthy source that does one successful read and
        // then deliberately blocks (like session 1's initial reads) so it stays reliably "active"
        // for as long as the test needs, instead of racing through to its own natural end before
        // the assertions below run.
        var session2ReadCount = 0
        val session2Blocked = CountDownLatch(1)
        val session2Source = object : AudioSource {
            override fun startRecording() {}
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                session2ReadCount++
                if (session2ReadCount == 1) {
                    System.arraycopy(chunk, 0, buffer, offset, chunk.size)
                    return chunk.size
                }
                session2Blocked.await(5, TimeUnit.SECONDS)
                return -1
            }
            override fun stop() {}
            override fun release() {}
        }

        var startCallCount = 0
        val recorder = WavRecorder(
            threadJoinTimeoutMs = 50, // fast, deterministic timeout for the test
            openAudioSource = {
                startCallCount++
                val source = if (startCallCount == 1) session1Source else session2Source
                WavRecorder.RecorderConfig(source, sampleRate = 48000, bufferSize = chunk.size)
            }
        )

        var session1ErrorReported: Exception? = null
        recorder.start(
            context = ApplicationProvider.getApplicationContext(),
            nextTarget = WavRecorder.NextTarget { OutputTarget.FileTarget(tempFolder.newFile("session1.wav")) },
            onSegmentStarted = {},
            onAmplitude = {},
            onError = { e -> session1ErrorReported = e }
        )
        val session1Deadline = System.currentTimeMillis() + 2000
        while (session1ReadCount < 1 && System.currentTimeMillis() < session1Deadline) Thread.sleep(5)
        Thread.sleep(20)

        // stop() must give up after its (short, injected) join timeout even though session 1's
        // thread never actually terminates.
        recorder.stop()
        assertTrue("expected AudioSource#stop() to have been called", session1StopCalled.get())
        assertFalse(recorder.isActive)

        // Start a brand new session while session 1's background thread is still alive, blocked
        // inside read().
        var session2ErrorReported: Exception? = null
        recorder.start(
            context = ApplicationProvider.getApplicationContext(),
            nextTarget = WavRecorder.NextTarget { OutputTarget.FileTarget(tempFolder.newFile("session2.wav")) },
            onSegmentStarted = {},
            onAmplitude = {},
            onError = { e -> session2ErrorReported = e }
        )
        assertTrue("expected a new session to be able to start", recorder.isActive)

        // Now let session 1's long-blocked read() finally return, simulating the wedged driver
        // eventually giving up well after it's been superseded.
        letSession1Resume.countDown()
        Thread.sleep(200)
        shadowOf(Looper.getMainLooper()).idle()

        assertNull("a stale session must never report an error against a newer session", session1ErrorReported)
        assertTrue("session 2 must still be active; a stale session must not be able to stop it", recorder.isActive)

        // Sanity check: releasing session 2's own blocked read (simulating its own real failure,
        // not an intentional stop) must still be reported normally -- proving the generation
        // guard only suppresses stale sessions, not the current one.
        session2Blocked.countDown()
        val session2Deadline = System.currentTimeMillis() + 2000
        while (recorder.isActive && System.currentTimeMillis() < session2Deadline) Thread.sleep(5)
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull("session 2's own failure should still be reported normally", session2ErrorReported)
    }

    @Test
    fun `a stale thread whose blocked read later returns valid data must not touch a newer session`() {
        // Companion to the test above: there, the stale read is released by returning an error,
        // which the existing generation check on the *exception* path already suppresses. This
        // covers the gap where the blocked read instead eventually returns real, positive audio
        // data -- which recordLoop() would otherwise write, roll over, and finalize with whatever
        // the shared sampleRate/segmentMaxBytes fields say *now* (session 2's), not what was true
        // when session 1 started.
        val chunk = byteArrayOf(1, 2, 3, 4)
        // Bigger than either session's segmentMaxBytes (44100 or 48000 Hz * 2 bytes * 1s), so if
        // this stale data were wrongly accepted it would force a rollover no matter which
        // session's threshold was in effect at the time -- making an extra segment a reliable
        // signal of the bug rather than a coincidence of timing.
        val staleChunk = ByteArray(100_000) { 9 }

        var session1ReadCount = 0
        val session1StopCalled = AtomicBoolean(false)
        val letSession1Resume = CountDownLatch(1)
        val session1Source = object : AudioSource {
            override fun startRecording() {}
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                session1ReadCount++
                if (session1ReadCount == 1) {
                    System.arraycopy(chunk, 0, buffer, offset, chunk.size)
                    return chunk.size
                }
                var released = false
                while (!released) {
                    released = try { letSession1Resume.await(5, TimeUnit.SECONDS); true } catch (_: InterruptedException) { false }
                }
                // The blocked read finally "succeeds" instead of erroring out -- valid, positive
                // audio data arriving well after this session was superseded.
                System.arraycopy(staleChunk, 0, buffer, offset, staleChunk.size)
                return staleChunk.size
            }
            override fun stop() {
                session1StopCalled.set(true) // deliberately does NOT unblock read()
            }
            override fun release() {}
        }

        var session2ReadCount = 0
        val session2Blocked = CountDownLatch(1)
        val session2Source = object : AudioSource {
            override fun startRecording() {}
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                session2ReadCount++
                if (session2ReadCount == 1) {
                    System.arraycopy(chunk, 0, buffer, offset, chunk.size)
                    return chunk.size
                }
                session2Blocked.await(5, TimeUnit.SECONDS)
                return -1
            }
            override fun stop() {}
            override fun release() {}
        }

        var startCallCount = 0
        val recorder = WavRecorder(
            segmentMaxSeconds = 1,
            threadJoinTimeoutMs = 50, // fast, deterministic timeout for the test
            openAudioSource = {
                startCallCount++
                if (startCallCount == 1) {
                    WavRecorder.RecorderConfig(session1Source, sampleRate = 44100, bufferSize = staleChunk.size)
                } else {
                    WavRecorder.RecorderConfig(session2Source, sampleRate = 48000, bufferSize = chunk.size)
                }
            }
        )

        val session1Files = mutableListOf<File>()
        var session1SegmentCreateCount = 0
        var session1SegmentStartedCount = 0
        var session1AmplitudeCount = 0
        var session1ErrorReported: Exception? = null
        recorder.start(
            context = ApplicationProvider.getApplicationContext(),
            nextTarget = WavRecorder.NextTarget {
                session1SegmentCreateCount++
                val file = tempFolder.newFile("session1-seg$session1SegmentCreateCount.wav")
                session1Files += file
                OutputTarget.FileTarget(file)
            },
            onSegmentStarted = { session1SegmentStartedCount++ },
            onAmplitude = { session1AmplitudeCount++ },
            onError = { e -> session1ErrorReported = e }
        )
        val session1Deadline = System.currentTimeMillis() + 2000
        while (session1ReadCount < 1 && System.currentTimeMillis() < session1Deadline) Thread.sleep(5)
        Thread.sleep(20)
        shadowOf(Looper.getMainLooper()).idle() // deliver the onSegmentStarted/onAmplitude posts from the accepted chunk

        // Snapshot right after the one legitimate chunk was accepted, before the stale resume.
        val segmentStartedBeforeResume = session1SegmentStartedCount
        val amplitudeBeforeResume = session1AmplitudeCount
        assertEquals(1, segmentStartedBeforeResume)
        assertEquals(1, amplitudeBeforeResume)

        recorder.stop()
        assertTrue("expected AudioSource#stop() to have been called", session1StopCalled.get())
        assertFalse(recorder.isActive)

        var session2ErrorReported: Exception? = null
        var session2SegmentCreateCount = 0
        recorder.start(
            context = ApplicationProvider.getApplicationContext(),
            nextTarget = WavRecorder.NextTarget {
                session2SegmentCreateCount++
                OutputTarget.FileTarget(tempFolder.newFile("session2-seg$session2SegmentCreateCount.wav"))
            },
            onSegmentStarted = {},
            onAmplitude = {},
            onError = { e -> session2ErrorReported = e }
        )
        assertTrue("expected a new session to be able to start", recorder.isActive)

        // Now let session 1's long-blocked read() finally return -- with valid, positive data.
        letSession1Resume.countDown()
        Thread.sleep(300)
        shadowOf(Looper.getMainLooper()).idle()

        assertNull("a stale session must never report an error against a newer session", session1ErrorReported)
        assertNull("a stale session's discarded data must not surface as a newer session's error",
            session2ErrorReported)
        assertTrue("session 2 must still be active; a stale session must not be able to stop it", recorder.isActive)

        assertEquals("the stale read must not roll over into a second segment for session 1",
            1, session1SegmentCreateCount)
        assertEquals("no additional onSegmentStarted callback should fire for the stale session",
            segmentStartedBeforeResume, session1SegmentStartedCount)
        assertEquals("no additional onAmplitude callback should fire for the stale session",
            amplitudeBeforeResume, session1AmplitudeCount)

        val session1Format = WavRiffParser.parse(session1Files[0].inputStream())
        assertNotNull(session1Format)
        assertEquals("session 1's header must keep its own sample rate, not session 2's",
            44100, session1Format?.sampleRate)
        assertEquals("session 1's finalized file must contain only the audio accepted before stop",
            chunk.size.toLong(), session1Format?.dataSize)
    }

    /** Wraps a real [FileChannel] but only ever lets [maxBytesPerCall] bytes through per write(),
     * forcing callers to loop to fully drain a larger buffer -- exercising WavRecorder's
     * writeFully() without needing an OS-level short write, which real local files essentially
     * never produce. */
    private class PartialWriteWrapper(private val channel: FileChannel, private val maxBytesPerCall: Int) : SegmentWriter {
        override fun write(buffer: ByteBuffer): Int {
            val originalLimit = buffer.limit()
            val cappedLimit = minOf(originalLimit, buffer.position() + maxBytesPerCall)
            buffer.limit(cappedLimit)
            val written = channel.write(buffer)
            buffer.limit(originalLimit)
            return written
        }
        override fun position(): Long = channel.position()
        override fun position(newPosition: Long) { channel.position(newPosition) }
        override fun close() = channel.close()
    }

    @Test
    fun `writes are looped to completion even when the writer only makes partial progress`() {
        val chunk = ByteArray(37) { it.toByte() } // deliberately not a multiple of the 5-byte cap below
        val doneLatch = CountDownLatch(1)
        val fake = FakeAudioSource(scriptedReads = listOf(chunk), onExhausted = { doneLatch.countDown() })
        val segmentFile = tempFolder.newFile("segment.wav")

        val recorder = WavRecorder(
            openAudioSource = { WavRecorder.RecorderConfig(fake, sampleRate = 48000, bufferSize = chunk.size) },
            wrapChannel = { channel -> PartialWriteWrapper(channel, maxBytesPerCall = 5) }
        )

        recorder.start(
            context = ApplicationProvider.getApplicationContext(),
            nextTarget = WavRecorder.NextTarget { OutputTarget.FileTarget(segmentFile) },
            onSegmentStarted = {},
            onAmplitude = {},
            onError = {}
        )

        assertTrue(doneLatch.await(2, TimeUnit.SECONDS))
        Thread.sleep(50)

        segmentFile.inputStream().use { input ->
            val format = WavRiffParser.parse(input)
            assertEquals(chunk.size.toLong(), format?.dataSize)
            assertArrayEquals("audio data must be complete and byte-for-byte correct despite " +
                "every write() call only draining 5 bytes at a time", chunk, input.readBytes())
        }
    }

    @Test
    fun `onMicrophoneInfo reports the AudioSource's verified microphone info immediately`() {
        // The real device-verification logic lives in the production-only SystemAudioSource
        // (needs a real AudioRecord/AudioManager), so this covers the contract WavRecorder itself
        // is responsible for: asking the source what it verified once recording has started, and
        // handing that straight to the caller -- not assuming AudioSource.MIC/UNPROCESSED implies
        // any particular physical device.
        val expectedInfo = MicrophoneInfo(label = "Insta360 Mic Air", isExternal = true, verified = true)
        val fake = object : AudioSource {
            override fun startRecording() {}
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = -1
            override fun stop() {}
            override fun release() {}
            override fun describeMicrophone(): MicrophoneInfo = expectedInfo
        }
        val recorder = WavRecorder(
            openAudioSource = { WavRecorder.RecorderConfig(fake, sampleRate = 48000, bufferSize = 4) }
        )

        var reportedInfo: MicrophoneInfo? = null
        recorder.start(
            context = ApplicationProvider.getApplicationContext(),
            nextTarget = WavRecorder.NextTarget { OutputTarget.FileTarget(tempFolder.newFile("segment.wav")) },
            onSegmentStarted = {},
            onAmplitude = {},
            onError = {},
            onMicrophoneInfo = { info -> reportedInfo = info }
        )

        // Delivered synchronously from start() itself (before the recording thread even exists),
        // not via the generation-guarded handler.post() path the other callbacks use -- so no
        // idle()/await is needed to observe it.
        assertEquals(expectedInfo, reportedInfo)
        recorder.stop()
    }

    @Test
    fun `an unverifiable AudioSource reports MicrophoneInfo_UNVERIFIED rather than assuming external`() {
        // A fake/test source (or a real device that can't determine routing) must never be
        // reported as a confidently-external mic it can't actually back up.
        val fake = FakeAudioSource() // no describeMicrophone() override: uses the interface default
        val recorder = WavRecorder(
            openAudioSource = { WavRecorder.RecorderConfig(fake, sampleRate = 48000, bufferSize = 4) }
        )

        var reportedInfo: MicrophoneInfo? = null
        recorder.start(
            context = ApplicationProvider.getApplicationContext(),
            nextTarget = WavRecorder.NextTarget { OutputTarget.FileTarget(tempFolder.newFile("segment.wav")) },
            onSegmentStarted = {},
            onAmplitude = {},
            onError = {},
            onMicrophoneInfo = { info -> reportedInfo = info }
        )

        assertEquals(MicrophoneInfo.UNVERIFIED, reportedInfo)
        assertFalse(reportedInfo?.isExternal ?: true)
        recorder.stop()
    }

    @Test
    fun `a verified external microphone disconnecting mid-recording stops safely, finalizes, and preserves audio`() {
        // Standing in for SystemAudioSource's real AudioDeviceCallback-backed monitoring: a real
        // AudioRecord can keep handing back samples for a moment after its device is actually
        // gone, so isDeviceConnected() flipping false is what recordLoop() must notice and act on,
        // not read() itself erroring out.
        val chunk = byteArrayOf(1, 2, 3, 4)
        val connected = AtomicBoolean(true)
        val fake = object : AudioSource {
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
        val recorder = WavRecorder(
            headerFlushIntervalMs = 10, // fast disconnect-check cadence for the test
            openAudioSource = { WavRecorder.RecorderConfig(fake, sampleRate = 48000, bufferSize = chunk.size) }
        )

        var reportedError: Exception? = null
        recorder.start(
            context = ApplicationProvider.getApplicationContext(),
            nextTarget = WavRecorder.NextTarget { OutputTarget.FileTarget(segmentFile) },
            onSegmentStarted = {},
            onAmplitude = {},
            onError = { e -> reportedError = e }
        )

        Thread.sleep(50) // let some real audio actually get captured first
        connected.set(false) // simulate the external mic being unplugged

        awaitTerminatedAndDeliverCallbacks(recorder)

        assertTrue("expected a MicrophoneDisconnectedException, got $reportedError",
            reportedError is MicrophoneDisconnectedException)
        assertFalse("recording must stop, not silently continue on the phone mic", recorder.isActive)

        // The audio already captured before the disconnect must not be lost: the file must still
        // exist with a valid, non-empty header, not be deleted or left as a 0-byte placeholder.
        val format = WavRiffParser.parse(segmentFile.inputStream())
        assertNotNull("expected the segment to still finalize with the audio captured before " +
            "the disconnect was noticed", format)
        assertTrue("expected some real audio to have been preserved", (format?.dataSize ?: 0L) > 0L)
    }

    @Test
    fun `a silent route change away from the verified external microphone stops safely without any device-removal callback`() {
        // Standing in for the gap isDeviceConnected() alone can't cover: Android silently
        // rerouting an active AudioRecord to a different device (typically the built-in mic)
        // without ever reporting the original device as removed. isDeviceConnected() deliberately
        // stays at its default (true) throughout this test -- only isRouteUnchanged()'s own direct
        // re-check flips, proving recordLoop() reacts to that seam on its own, independent of any
        // removal callback ever firing.
        val chunk = byteArrayOf(1, 2, 3, 4)
        val routeUnchanged = AtomicBoolean(true)
        val fake = object : AudioSource {
            override fun startRecording() {}
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                System.arraycopy(chunk, 0, buffer, offset, chunk.size)
                return chunk.size
            }
            override fun stop() {}
            override fun release() {}
            override fun describeMicrophone() =
                MicrophoneInfo(label = "USB Mic", isExternal = true, verified = true)
            override fun isRouteUnchanged(): Boolean = routeUnchanged.get()
        }
        val segmentFile = tempFolder.newFile("segment.wav")
        val recorder = WavRecorder(
            headerFlushIntervalMs = 10, // fast route-check cadence for the test
            openAudioSource = { WavRecorder.RecorderConfig(fake, sampleRate = 48000, bufferSize = chunk.size) }
        )

        var reportedError: Exception? = null
        recorder.start(
            context = ApplicationProvider.getApplicationContext(),
            nextTarget = WavRecorder.NextTarget { OutputTarget.FileTarget(segmentFile) },
            onSegmentStarted = {},
            onAmplitude = {},
            onError = { e -> reportedError = e }
        )

        Thread.sleep(50) // let some real audio actually get captured first
        routeUnchanged.set(false) // simulate a silent OS-level reroute, e.g. to the phone mic

        awaitTerminatedAndDeliverCallbacks(recorder)

        assertTrue("expected a MicrophoneRouteChangedException, got $reportedError",
            reportedError is MicrophoneRouteChangedException)
        assertFalse("recording must stop, not silently continue on whatever it got rerouted to",
            recorder.isActive)

        // The audio already captured before the reroute was noticed must not be lost.
        val format = WavRiffParser.parse(segmentFile.inputStream())
        assertNotNull("expected the segment to still finalize with the audio captured before the " +
            "route change was noticed", format)
        assertTrue("expected some real audio to have been preserved", (format?.dataSize ?: 0L) > 0L)
    }

    @Test
    fun `a failure patching the final WAV header is reported via stop() as Failed, not a silent success`() {
        val chunk = byteArrayOf(1, 2, 3, 4)
        val blockUntilStopped = CountDownLatch(1)
        var readCount = 0
        val source = object : AudioSource {
            override fun startRecording() {}
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                readCount++
                if (readCount <= 2) {
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
        val recorder = WavRecorder(
            openAudioSource = { WavRecorder.RecorderConfig(source, sampleRate = 48000, bufferSize = chunk.size) },
            // position(0) is only ever called to seek back and (re)write the header -- regular
            // audio writes never reposition -- so failing exactly that call simulates a header
            // patch failure without disturbing normal data writes at all.
            wrapChannel = { channel -> FailingHeaderPatchWriter(channel) }
        )

        var onErrorCalled = false
        recorder.start(
            context = ApplicationProvider.getApplicationContext(),
            nextTarget = WavRecorder.NextTarget { OutputTarget.FileTarget(segmentFile) },
            onSegmentStarted = {},
            onAmplitude = {},
            onError = { onErrorCalled = true }
        )

        val deadline = System.currentTimeMillis() + 2000
        while (readCount < 2 && System.currentTimeMillis() < deadline) Thread.sleep(5)
        Thread.sleep(20)

        val result = recorder.stop()

        assertTrue("expected the header-patch failure to surface as FinalizeResult.Failed",
            result is WavRecorder.FinalizeResult.Failed)
        val failed = result as WavRecorder.FinalizeResult.Failed
        assertTrue(failed.cause is WavFinalizationException)
        assertEquals(segmentFile.absolutePath, (failed.target as? OutputTarget.FileTarget)?.file?.absolutePath)
        // Finalization failures are reported through stop()'s return value, not the live-recording
        // onError callback -- RecordingService is what turns this into "needs recovery" instead
        // of a plain onError toast or a "Saved" status.
        assertFalse("a stop()-time finalization failure must not also fire the generic onError " +
            "callback", onErrorCalled)

        // The audio captured before the failed patch must still be recoverable on disk, even
        // though the header's declared size is now stale.
        assertTrue("expected the raw audio bytes to still be on disk despite the header not " +
            "being patched", segmentFile.length() > WavHeaderWriter.HEADER_SIZE)
    }

    @Test
    fun `a failure closing the writer during final close is reported via stop() as Failed`() {
        val chunk = byteArrayOf(1, 2, 3, 4)
        val blockUntilStopped = CountDownLatch(1)
        var readCount = 0
        val source = object : AudioSource {
            override fun startRecording() {}
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                readCount++
                if (readCount <= 2) {
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
        val recorder = WavRecorder(
            openAudioSource = { WavRecorder.RecorderConfig(source, sampleRate = 48000, bufferSize = chunk.size) },
            wrapChannel = { channel -> FailingCloseWriter(channel) }
        )

        recorder.start(
            context = ApplicationProvider.getApplicationContext(),
            nextTarget = WavRecorder.NextTarget { OutputTarget.FileTarget(segmentFile) },
            onSegmentStarted = {},
            onAmplitude = {},
            onError = {}
        )

        val deadline = System.currentTimeMillis() + 2000
        while (readCount < 2 && System.currentTimeMillis() < deadline) Thread.sleep(5)
        Thread.sleep(20)

        val result = recorder.stop()

        assertTrue("expected the writer-close failure to surface as FinalizeResult.Failed",
            result is WavRecorder.FinalizeResult.Failed)
        assertTrue((result as WavRecorder.FinalizeResult.Failed).cause is WavFinalizationException)
        assertTrue("expected the audio bytes already written to still be on disk",
            segmentFile.length() > WavHeaderWriter.HEADER_SIZE)
    }

    @Test
    fun `a finalization failure during rollover stops the recording instead of silently opening another segment`() {
        val chunk = byteArrayOf(1, 2, 3, 4)
        var segmentCreateCount = 0
        // sampleRate=1 + segmentMaxSeconds=1 => segmentMaxBytes=2, so the first 4-byte chunk
        // immediately triggers a rollover -- straight into the header patch failure below.
        val fake = FakeAudioSource(scriptedReads = listOf(chunk, chunk, chunk))
        val recorder = WavRecorder(
            segmentMaxSeconds = 1,
            openAudioSource = { WavRecorder.RecorderConfig(fake, sampleRate = 1, bufferSize = chunk.size) },
            wrapChannel = { channel -> FailingHeaderPatchWriter(channel) }
        )

        var reportedError: Exception? = null
        recorder.start(
            context = ApplicationProvider.getApplicationContext(),
            nextTarget = WavRecorder.NextTarget {
                segmentCreateCount++
                OutputTarget.FileTarget(tempFolder.newFile("segment$segmentCreateCount.wav"))
            },
            onSegmentStarted = {},
            onAmplitude = {},
            onError = { e -> reportedError = e }
        )

        awaitTerminatedAndDeliverCallbacks(recorder)

        assertTrue("expected the rollover's finalization failure to be reported like any other " +
            "fatal error", reportedError is WavFinalizationException)
        assertFalse(recorder.isActive)
        assertEquals("a rollover that fails to finalize must not silently open a second segment " +
            "and keep going", 1, segmentCreateCount)

        // Confirms the finalization outcome is consistently "failed", not just that an error was
        // reported once: a caller checking stop()'s result afterward (as RecordingService does)
        // must also see this recording as needing recovery, never a plain success.
        val result = recorder.stop()
        assertTrue(result is WavRecorder.FinalizeResult.Failed)
    }

    /** Regression writer for flushHeader()'s position-safety redesign: [position] (0L) always
     * succeeds -- standing in for "successfully seeks to zero" -- but every [write] attempted
     * exactly at file position 0 fails *after* the first one, simulating a header write that
     * fails right after a successful seek during a later periodic flush (the very first
     * position-0 write is always the initial placeholder header in openSegment(), which must
     * succeed or no segment -- and no periodic flush -- would ever happen at all). Once a header
     * write has actually failed, any *later* attempt to restore the writer to a non-zero position
     * also fails, standing in for a channel left broken by the earlier failure -- exactly the
     * case flushHeader must never silently swallow, since continuing to write PCM data from an
     * unconfirmed position risks overwriting the header or prior audio. */
    private class HeaderWriteThenBrokenRestoreWriter(private val channel: FileChannel) : SegmentWriter {
        private var position0WriteCount = 0
        private var headerWriteFailed = false
        override fun write(buffer: ByteBuffer): Int {
            if (channel.position() == 0L) {
                position0WriteCount++
                if (position0WriteCount > 1) {
                    headerWriteFailed = true
                    throw IOException("simulated header write failure")
                }
            }
            return channel.write(buffer)
        }
        override fun position(): Long = channel.position()
        override fun position(newPosition: Long) {
            if (headerWriteFailed && newPosition != 0L) {
                throw IOException("simulated failure restoring write position after a broken header write")
            }
            channel.position(newPosition)
        }
        override fun close() = channel.close()
    }

    @Test
    fun `a header flush that seeks to zero, fails the header write, and then fails to restore position stops recording instead of risking corruption`() {
        val chunk = byteArrayOf(1, 2, 3, 4)
        val blockUntilStopped = CountDownLatch(1)
        var readCount = 0
        val source = object : AudioSource {
            override fun startRecording() {}
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                readCount++
                if (readCount <= 20) {
                    // A brief per-read delay so the loop actually spends real wall-clock time
                    // *actively iterating* (rather than racing through in under a millisecond) --
                    // the periodic header-flush check only ever runs between reads, never while
                    // blocked in one, so without this the 10ms flush cadence below would never
                    // get a chance to fire before read 21 blocks forever.
                    Thread.sleep(5)
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
        val recorder = WavRecorder(
            headerFlushIntervalMs = 10, // fast flush cadence so the test doesn't wait out the 3s default
            openAudioSource = { WavRecorder.RecorderConfig(source, sampleRate = 48000, bufferSize = chunk.size) },
            wrapChannel = { channel -> HeaderWriteThenBrokenRestoreWriter(channel) }
        )

        var reportedError: Exception? = null
        recorder.start(
            context = ApplicationProvider.getApplicationContext(),
            nextTarget = WavRecorder.NextTarget { OutputTarget.FileTarget(segmentFile) },
            onSegmentStarted = {},
            onAmplitude = {},
            onError = { e -> reportedError = e }
        )

        awaitTerminatedAndDeliverCallbacks(recorder)

        assertNotNull("a header flush that can no longer confirm a safe write position must be " +
            "surfaced as a fatal error, not silently swallowed", reportedError)
        assertFalse("recording must stop rather than keep writing from an unconfirmed position",
            recorder.isActive)

        // No PCM data can have landed at/over the header: recordLoop breaks out of its loop the
        // moment the exception is thrown, before any further write() is attempted at all.
        assertTrue("expected the audio captured before the broken flush to still be on disk",
            segmentFile.length() >= WavHeaderWriter.HEADER_SIZE)
    }

    /** Regression writer for flushHeader()'s recoverable case: the header write fails exactly
     * once (simulating a transient glitch during a later periodic flush -- the very first
     * position-0 write is always the initial placeholder header in openSegment(), which must
     * succeed here too), then succeeds on every subsequent attempt -- including the position
     * restore, which this writer never fails. */
    private class TransientHeaderWriteFailureWriter(private val channel: FileChannel) : SegmentWriter {
        private var position0WriteCount = 0
        private var failuresRemaining = 1
        override fun write(buffer: ByteBuffer): Int {
            if (channel.position() == 0L) {
                position0WriteCount++
                if (position0WriteCount > 1 && failuresRemaining > 0) {
                    failuresRemaining--
                    throw IOException("simulated transient header write failure")
                }
            }
            return channel.write(buffer)
        }
        override fun position(): Long = channel.position()
        override fun position(newPosition: Long) { channel.position(newPosition) }
        override fun close() = channel.close()
    }

    @Test
    fun `a header flush whose header write fails but whose position restore succeeds does not stop the recording`() {
        val chunk = byteArrayOf(1, 2, 3, 4)
        val blockUntilStopped = CountDownLatch(1)
        var readCount = 0
        val source = object : AudioSource {
            override fun startRecording() {}
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                readCount++
                if (readCount <= 20) {
                    // A brief per-read delay so the loop actually spends real wall-clock time
                    // *actively iterating* (rather than racing through in under a millisecond) --
                    // the periodic header-flush check only ever runs between reads, never while
                    // blocked in one, so without this the 10ms flush cadence below would never
                    // get a chance to fire (let alone fire more than once, to prove recovery)
                    // before read 21 blocks forever.
                    Thread.sleep(5)
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
        val recorder = WavRecorder(
            headerFlushIntervalMs = 10,
            openAudioSource = { WavRecorder.RecorderConfig(source, sampleRate = 48000, bufferSize = chunk.size) },
            wrapChannel = { channel -> TransientHeaderWriteFailureWriter(channel) }
        )

        var reportedError: Exception? = null
        recorder.start(
            context = ApplicationProvider.getApplicationContext(),
            nextTarget = WavRecorder.NextTarget { OutputTarget.FileTarget(segmentFile) },
            onSegmentStarted = {},
            onAmplitude = {},
            onError = { e -> reportedError = e }
        )

        val deadline = System.currentTimeMillis() + 2000
        while (readCount < 20 && System.currentTimeMillis() < deadline) Thread.sleep(5)
        shadowOf(Looper.getMainLooper()).idle()

        assertNull("a header-write failure that still restores its write position must not be " +
            "treated as fatal", reportedError)
        assertTrue("recording must continue normally after a recoverable header-flush failure",
            recorder.isActive)

        val result = recorder.stop()
        assertEquals("expected the segment to still finalize cleanly once a later flush (or the " +
            "final close) succeeds", WavRecorder.FinalizeResult.Ok, result)
        val finalized = WavRiffParser.parse(segmentFile.inputStream())
        assertNotNull(finalized)
        assertTrue("expected the real audio written before/after the transient failure to be intact",
            (finalized?.dataSize ?: 0L) > 0L)
    }

    @Test
    fun `a synchronous startup failure never reuses a previous session's finalization result`() {
        // Session A: a real recording that ends with a genuine finalization failure at stop() --
        // reusing the same setup as the header-patch-failure test above, whose result is already
        // known to be FinalizeResult.Failed.
        val chunk = byteArrayOf(1, 2, 3, 4)
        val blockUntilStopped = CountDownLatch(1)
        var readCount = 0
        val sessionASource = object : AudioSource {
            override fun startRecording() {}
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                readCount++
                if (readCount <= 2) {
                    System.arraycopy(chunk, 0, buffer, offset, chunk.size)
                    return chunk.size
                }
                blockUntilStopped.await(5, TimeUnit.SECONDS)
                return -1
            }
            override fun stop() { blockUntilStopped.countDown() }
            override fun release() {}
        }
        val sessionAFile = tempFolder.newFile("sessionA.wav")

        var startCount = 0
        val startupFailure = IllegalStateException("simulated: microphone busy")
        // Session B (the second start() call on this same recorder) fails synchronously while
        // "opening the microphone" -- before any recording thread, segment, or finalization
        // attempt of its own could ever exist.
        val recorder = WavRecorder(
            openAudioSource = {
                startCount++
                if (startCount == 1) {
                    WavRecorder.RecorderConfig(sessionASource, sampleRate = 48000, bufferSize = chunk.size)
                } else {
                    throw startupFailure
                }
            },
            wrapChannel = { channel -> FailingHeaderPatchWriter(channel) }
        )

        // --- Session A ---
        recorder.start(
            context = ApplicationProvider.getApplicationContext(),
            nextTarget = WavRecorder.NextTarget { OutputTarget.FileTarget(sessionAFile) },
            onSegmentStarted = {},
            onAmplitude = {},
            onError = {}
        )
        val deadline = System.currentTimeMillis() + 2000
        while (readCount < 2 && System.currentTimeMillis() < deadline) Thread.sleep(5)
        Thread.sleep(20)
        val sessionAResult = recorder.stop()
        assertTrue("expected session A to end with a genuine finalization failure (test setup " +
            "check)", sessionAResult is WavRecorder.FinalizeResult.Failed)
        val sessionAFailure = sessionAResult as WavRecorder.FinalizeResult.Failed

        // --- Session B: fails synchronously at startup. ---
        var sessionBError: Exception? = null
        recorder.start(
            context = ApplicationProvider.getApplicationContext(),
            nextTarget = WavRecorder.NextTarget {
                throw AssertionError("session B must never get far enough to open a segment")
            },
            onSegmentStarted = {},
            onAmplitude = {},
            onError = { e -> sessionBError = e }
        )

        assertEquals("expected session B's own startup failure to be reported, not swallowed",
            startupFailure, sessionBError)

        // Mirrors exactly what RecordingService.onError does: call stop() to inspect the
        // finalize result after an error.
        val sessionBResult = recorder.stop()
        assertEquals(
            "a synchronous startup failure has nothing of its own to finalize -- must never " +
                "resurrect a previous session's Failed result",
            WavRecorder.FinalizeResult.Ok, sessionBResult
        )
        assertTrue(
            "session B's result must not reference session A's target or cause in any way",
            sessionBResult != sessionAFailure &&
                (sessionBResult as? WavRecorder.FinalizeResult.Failed)?.target !=
                    sessionAFailure.target
        )
    }
}
