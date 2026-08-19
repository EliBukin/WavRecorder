package com.example.wavrecorder

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Covers [MicTestSession] directly: reuses [FakeAudioSource] (see its own updated doc) exactly
 * like [WavRecorderTest] does, so a fake standing in for the real microphone models the same
 * [AudioSource] contract both classes actually depend on. Never touches a real file,
 * [ActiveSegmentJournal], or [RecordingService] -- see the individual tests below for direct
 * proof of that.
 */
@RunWith(RobolectricTestRunner::class)
class MicTestSessionTest {

    private fun context() = ApplicationProvider.getApplicationContext<android.content.Context>()

    /** Idles the main looper (delivering anything already posted) after first waiting for the
     * background test thread to actually go idle/inactive -- mirrors
     * WavRecorderTest.awaitTerminatedAndDeliverCallbacks, same rationale: the background thread
     * runs uncontrolled by Robolectric, so this must poll rather than idle() once blind.
     *
     * Idles repeatedly (not once) after that: `isActive` flips false and the corresponding
     * `handler.post()` both happen on the background thread, sequentially, but *not* atomically
     * from this polling thread's perspective -- observing `isActive == false` is no guarantee the
     * post() call has actually completed enqueuing yet. A single idle() timed exactly in that gap
     * would see nothing queued and silently miss the callback. Retrying a few times with a tiny
     * sleep between each closes that gap reliably without weakening what's actually being tested. */
    private fun awaitInactiveAndDeliverCallbacks(session: MicTestSession, timeoutMs: Long = 2000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (session.active && System.currentTimeMillis() < deadline) Thread.sleep(5)
        repeat(20) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(2)
        }
    }

    @Test
    fun `starting and stopping a test never opens a file, journal entry, or the recording service`() {
        val fake = FakeAudioSource(scriptedReads = listOf(byteArrayOf(1, 2, 3, 4)))
        val session = MicTestSession(openAudioSource = { WavRecorder.RecorderConfig(fake, 48000, 4) })

        var micInfoCalls = 0
        session.start(context(), onMicrophoneInfo = { micInfoCalls++ }, onLevel = {}, onError = {})
        session.stop()

        assertTrue("expected onMicrophoneInfo to fire synchronously from start()", micInfoCalls == 1)
        assertNull(
            "a microphone test must never touch the crash-recovery journal -- ACTIVE must stay empty",
            ActiveSegmentJournal(context()).peekActive()
        )
        assertTrue(ActiveSegmentJournal(context()).peekRecoveryCandidates().isEmpty())
    }

    @Test
    fun `stop releases the AudioSource and the session reports inactive`() {
        val fake = FakeAudioSource(scriptedReads = listOf(byteArrayOf(1, 2, 3, 4)))
        val session = MicTestSession(openAudioSource = { WavRecorder.RecorderConfig(fake, 48000, 4) })

        session.start(context(), onMicrophoneInfo = {}, onLevel = {}, onError = {})
        assertTrue(session.active)
        session.stop()

        assertFalse(session.active)
        assertTrue(fake.stopCalled)
        assertTrue(fake.releaseCalled)
    }

    @Test
    fun `repeated start-stop cycles release every previous AudioSource exactly once, never leaking`() {
        val sources = List(5) { FakeAudioSource(scriptedReads = listOf(byteArrayOf(1, 2, 3, 4))) }
        var index = 0
        val session = MicTestSession(openAudioSource = { WavRecorder.RecorderConfig(sources[index++], 48000, 4) })

        repeat(5) {
            session.start(context(), onMicrophoneInfo = {}, onLevel = {}, onError = {})
            session.stop()
        }

        sources.forEachIndexed { i, source ->
            assertTrue("source #$i's startRecording() was never called", source.startCalled)
            assertTrue("source #$i was never released -- a repeated cycle leaked it", source.releaseCalled)
        }
        assertFalse(session.active)
    }

    @Test
    fun `a disconnect detected mid-test reports MicrophoneDisconnectedException, and the caller's stop() then releases resources`() {
        val chunk = byteArrayOf(1, 2, 3, 4)
        val fake = FakeAudioSource(scriptedReads = List(50) { chunk }, connected = { false })
        val session = MicTestSession(
            openAudioSource = { WavRecorder.RecorderConfig(fake, 48000, 4) },
            routeCheckIntervalMs = 0
        )

        var error: Exception? = null
        session.start(context(), onMicrophoneInfo = {}, onLevel = {}, onError = { e ->
            error = e
            // Matches MicTestSession.start()'s documented onError contract: the caller must call
            // stop() itself to actually release the AudioSource.
            session.stop()
        })

        awaitInactiveAndDeliverCallbacks(session)

        assertTrue("expected MicrophoneDisconnectedException, got: $error", error is MicrophoneDisconnectedException)
        assertTrue(fake.releaseCalled)
        assertFalse(session.active)
    }

    @Test
    fun `a route change detected mid-test reports MicrophoneRouteChangedException, never silently falling back to the phone mic`() {
        val chunk = byteArrayOf(1, 2, 3, 4)
        val fake = FakeAudioSource(scriptedReads = List(50) { chunk }, routeUnchanged = { false })
        val session = MicTestSession(
            openAudioSource = { WavRecorder.RecorderConfig(fake, 48000, 4) },
            routeCheckIntervalMs = 0
        )

        var error: Exception? = null
        session.start(context(), onMicrophoneInfo = {}, onLevel = {}, onError = { e -> error = e; session.stop() })

        awaitInactiveAndDeliverCallbacks(session)

        assertTrue("expected MicrophoneRouteChangedException, got: $error", error is MicrophoneRouteChangedException)
    }

    @Test
    fun `a negative read result reports a fatal error and stops the test`() {
        val fake = FakeAudioSource(scriptedReads = listOf(byteArrayOf(1, 2, 3, 4))) // exhausted -> -1 next read
        val session = MicTestSession(openAudioSource = { WavRecorder.RecorderConfig(fake, 48000, 4) })

        var error: Exception? = null
        session.start(context(), onMicrophoneInfo = {}, onLevel = {}, onError = { e -> error = e; session.stop() })

        awaitInactiveAndDeliverCallbacks(session)

        assertTrue("expected a fatal error for the -1 read result, got: $error", error is IllegalStateException)
    }

    @Test
    fun `a read() that throws is reported via onError`() {
        val boom = IOException("simulated: driver died")
        val fake = FakeAudioSource(readException = boom)
        val session = MicTestSession(openAudioSource = { WavRecorder.RecorderConfig(fake, 48000, 4) })

        var error: Exception? = null
        session.start(context(), onMicrophoneInfo = {}, onLevel = {}, onError = { e -> error = e; session.stop() })

        awaitInactiveAndDeliverCallbacks(session)

        assertEquals(boom, error)
    }

    @Test
    fun `level updates are rate-limited, not posted for every buffer read`() {
        val chunk = byteArrayOf(1, 2, 3, 4)
        val fake = FakeAudioSource(scriptedReads = List(200) { chunk })
        // A deliberately huge interval: within this fast test, at most the very first read's
        // level can ever cross it -- proving updates are throttled, not posted once per read.
        val session = MicTestSession(
            openAudioSource = { WavRecorder.RecorderConfig(fake, 48000, 4) },
            levelUpdateIntervalMs = 60_000L
        )

        var levelCalls = 0
        session.start(context(), onMicrophoneInfo = {}, onLevel = { levelCalls++ }, onError = {})
        // Exhausting all 200 scripted reads ends with a -1 (error) read, which flips isActive
        // false on its own -- wait for that, then stop() to release cleanly.
        awaitInactiveAndDeliverCallbacks(session)
        session.stop()

        assertTrue("expected at least one level update", levelCalls >= 1)
        assertTrue(
            "expected far fewer level posts (throttled) than the 200 reads that occurred, got $levelCalls",
            levelCalls < 10
        )
    }

    @Test
    fun `a callback from a stopped session never reaches a caller once stop() has already run`() {
        val readGate = CountDownLatch(1)
        val readReleased = CountDownLatch(1)
        val blockingSource = object : AudioSource {
            override fun startRecording() {}
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                readGate.countDown()
                readReleased.await(5, TimeUnit.SECONDS)
                buffer[0] = 1; buffer[1] = 0; buffer[2] = 0; buffer[3] = 0
                return 4
            }
            override fun stop() { readReleased.countDown() } // unblocks the pending read, like SystemAudioSource
            override fun release() {}
        }
        val session = MicTestSession(
            openAudioSource = { WavRecorder.RecorderConfig(blockingSource, 48000, 4) },
            levelUpdateIntervalMs = 0
        )

        var levelCallsAfterStop = 0
        session.start(context(), onMicrophoneInfo = {}, onLevel = { levelCallsAfterStop++ }, onError = {})
        assertTrue("sanity: the background thread must have actually entered its first read",
            readGate.await(2, TimeUnit.SECONDS))

        // Stop while the read is still blocked -- this is exactly the race the generation counter
        // must close: the read below returns real data *after* stop() already ran.
        session.stop()
        shadowOf(Looper.getMainLooper()).idle()
        val callsRightAfterStop = levelCallsAfterStop

        // Now let the blocked read actually return -- if the loop were still live (or a stale
        // callback slipped through), this would post a level update attributed to the
        // already-stopped session.
        readReleased.countDown()
        Thread.sleep(50)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(
            "a read completing after stop() must never still post a level update",
            callsRightAfterStop, levelCallsAfterStop
        )
    }

    @Test
    fun `starting while already active is a no-op`() {
        // Blocks indefinitely on its first read (until released) so the session is still
        // genuinely active -- not already exhausted and inactive -- by the time the second
        // start() call below runs.
        val release = CountDownLatch(1)
        val fake1 = object : AudioSource {
            override fun startRecording() {}
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                release.await(5, TimeUnit.SECONDS)
                return -1
            }
            override fun stop() { release.countDown() }
            override fun release() {}
        }
        val fake2 = FakeAudioSource(scriptedReads = listOf(byteArrayOf(1, 2, 3, 4)))
        var callCount = 0
        val session = MicTestSession(openAudioSource = {
            callCount++
            WavRecorder.RecorderConfig(if (callCount == 1) fake1 else fake2, 48000, 4)
        })

        session.start(context(), onMicrophoneInfo = {}, onLevel = {}, onError = {})
        session.start(context(), onMicrophoneInfo = {}, onLevel = {}, onError = {}) // must be ignored

        assertEquals("a second start() while already active must never open a second source", 1, callCount)
        session.stop()
    }

    @Test
    fun `openAudioSource throwing is reported via onError without leaving the session active`() {
        val boom = IllegalStateException("Microphone is unavailable")
        val session = MicTestSession(openAudioSource = { throw boom })

        var error: Exception? = null
        session.start(context(), onMicrophoneInfo = {}, onLevel = {}, onError = { e -> error = e })

        assertEquals(boom, error)
        assertFalse(session.active)
    }

    @Test
    fun `a startRecording failure releases the just-opened source and reports onError`() {
        val boom = IllegalStateException("AudioRecord failed to initialize")
        val fake = FakeAudioSource(startRecordingException = boom)
        val session = MicTestSession(openAudioSource = { WavRecorder.RecorderConfig(fake, 48000, 4) })

        var error: Exception? = null
        session.start(context(), onMicrophoneInfo = {}, onLevel = {}, onError = { e -> error = e })

        assertEquals(boom, error)
        assertTrue(fake.releaseCalled)
        assertFalse(session.active)
    }
}
