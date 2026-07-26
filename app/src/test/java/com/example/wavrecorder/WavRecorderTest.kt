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
}
