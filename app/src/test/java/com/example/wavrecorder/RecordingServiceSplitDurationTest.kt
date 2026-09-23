package com.example.wavrecorder

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * End-to-end coverage (through the real [RecordingService] -> [WavRecorder] -> WAV file path) of
 * the per-session split duration: that the service honors each supported choice, rejects anything
 * else in favor of 60 minutes, and keeps a running session's value fixed.
 *
 * Every session here runs at a fake sample rate of 1 Hz, so a split of N minutes is exactly
 * `N * 60 s * 1 sample/s * 2 bytes/sample` bytes of audio -- small enough to write for real, and
 * directly checkable in the finished files' `data` chunk sizes.
 */
@RunWith(RobolectricTestRunner::class)
class RecordingServiceSplitDurationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun segmentBytes(duration: RecordingSplitDuration): Int = (duration.seconds * 2).toInt()

    /** Hands back [chunks] one per read() -- optionally only once [gate] opens -- then blocks
     * until stop() releases it, so the test (not the source running dry) ends the session. */
    private class ChunkedSource(chunks: List<ByteArray>, private val gate: CountDownLatch? = null) : AudioSource {
        private val remaining = ArrayDeque(chunks)
        private val stopped = CountDownLatch(1)
        /** Counted down once every chunk has been handed out *and* the loop has come back for more,
         * i.e. the last chunk has already been fully written to its segment. */
        val drained = CountDownLatch(1)

        override fun startRecording() {}
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            gate?.await(5, TimeUnit.SECONDS)
            val next = synchronized(remaining) { remaining.removeFirstOrNull() }
            if (next == null) {
                drained.countDown()
                stopped.await(5, TimeUnit.SECONDS)
                return -1
            }
            System.arraycopy(next, 0, buffer, offset, next.size)
            return next.size
        }
        override fun stop() {
            stopped.countDown()
            gate?.countDown()
        }
        override fun release() {}
    }

    private class Session(val service: RecordingService, val source: ChunkedSource, val files: MutableList<File>)

    /** A fresh service whose recorder reads [chunks] (each [chunkSize] bytes) at 1 Hz and whose
     * segments land in [tempFolder]. */
    private fun newSession(chunkSize: Int, chunkCount: Int, gate: CountDownLatch? = null): Session {
        val service = Robolectric.buildService(RecordingService::class.java).create().get()
        val source = ChunkedSource(List(chunkCount) { ByteArray(chunkSize) { 7 } }, gate)
        val files = java.util.Collections.synchronizedList(mutableListOf<File>())
        // Its own folder per session: sessions started within the same second share a timestamp,
        // and so would otherwise share file names.
        val dir = tempFolder.newFolder()
        service.recorder = WavRecorder(
            openAudioSource = { WavRecorder.RecorderConfig(source, sampleRate = 1, bufferSize = chunkSize) }
        )
        service.destinationManager = object : DestinationManager(ApplicationProvider.getApplicationContext()) {
            override fun createOutputFile(fileName: String): OutputTarget {
                val file = File(dir, fileName)
                file.createNewFile()
                files += file
                return OutputTarget.FileTarget(file)
            }
        }
        return Session(service, source, files)
    }

    private fun stopAndAwait(service: RecordingService) {
        service.stopRecording()
        val deadline = System.currentTimeMillis() + 5000
        while (service.isFinalizing && System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
            shadowOf(Looper.getMainLooper()).idle()
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun dataSize(file: File): Long? = file.inputStream().use { WavRiffParser.parse(it)?.dataSize }

    /** Feeds three half-segment chunks: the first file must end up holding exactly one full
     * segment of [expected] length, and the remainder must continue in a second file. */
    private fun assertSplitsAt(expected: RecordingSplitDuration, start: (RecordingService) -> Unit) {
        val half = segmentBytes(expected) / 2
        val session = newSession(chunkSize = half, chunkCount = 3)

        start(session.service)
        assertTrue("recording never consumed its audio", session.source.drained.await(5, TimeUnit.SECONDS))
        assertEquals(expected, session.service.sessionSplitDuration)
        stopAndAwait(session.service)

        assertEquals("expected exactly one rollover", 2, session.files.size)
        assertEquals("the first file must be exactly one ${expected.minutes}-minute segment",
            segmentBytes(expected).toLong(), dataSize(session.files[0]))
        assertEquals(half.toLong(), dataSize(session.files[1]))
        assertTrue(session.files[0].name.endsWith("_part01.wav"))
        assertTrue(session.files[1].name.endsWith("_part02.wav"))
    }

    @Test
    fun `a 30-minute session splits after exactly 30 minutes of audio`() {
        assertSplitsAt(RecordingSplitDuration.MINUTES_30) { it.startRecording(1L, 30) }
    }

    @Test
    fun `a 45-minute session splits after exactly 45 minutes of audio`() {
        assertSplitsAt(RecordingSplitDuration.MINUTES_45) { it.startRecording(1L, 45) }
    }

    @Test
    fun `a 60-minute session splits after exactly 60 minutes of audio`() {
        assertSplitsAt(RecordingSplitDuration.MINUTES_60) { it.startRecording(1L, 60) }
    }

    @Test
    fun `a caller that passes no split duration gets the 60-minute default`() {
        assertSplitsAt(RecordingSplitDuration.MINUTES_60) { it.startRecording(1L) }
    }

    @Test
    fun `an unsupported split duration falls back to 60 minutes`() {
        listOf(0, -30, 15, 59, 61, 90, Int.MAX_VALUE).forEach { invalid ->
            assertSplitsAt(RecordingSplitDuration.MINUTES_60) { it.startRecording(1L, invalid) }
        }
    }

    @Test
    fun `a running session keeps the split duration it started with`() {
        val gate = CountDownLatch(1)
        val thirty = RecordingSplitDuration.MINUTES_30
        val half = segmentBytes(thirty) / 2
        val session = newSession(chunkSize = half, chunkCount = 3, gate = gate)
        val service = session.service

        service.startRecording(1L, 30)
        assertTrue(service.isRecording)

        // While it's recording (before any audio has even arrived), everything that could try to
        // change the length points at 60 minutes: the persisted user setting, and a second start
        // request (which the service must reject while a session is already recording).
        RecordingSettings(ApplicationProvider.getApplicationContext()).splitDuration = RecordingSplitDuration.MINUTES_60
        service.startRecording(2L, 60)
        assertEquals(1L, service.currentRequestId)
        assertEquals(thirty, service.sessionSplitDuration)

        gate.countDown()
        assertTrue("recording never consumed its audio", session.source.drained.await(5, TimeUnit.SECONDS))
        assertEquals(thirty, service.sessionSplitDuration)
        stopAndAwait(service)

        assertEquals(2, session.files.size)
        assertEquals("the running session must still split at 30 minutes",
            segmentBytes(thirty).toLong(), dataSize(session.files[0]))
        assertEquals(half.toLong(), dataSize(session.files[1]))
    }

    @Test
    fun `the next session picks up a new split duration`() {
        val service = Robolectric.buildService(RecordingService::class.java).create().get()
        val blockUntilStopped = { latch: CountDownLatch ->
            object : AudioSource {
                override fun startRecording() {}
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    latch.await(5, TimeUnit.SECONDS); return -1
                }
                override fun stop() { latch.countDown() }
                override fun release() {}
            }
        }
        service.recorder = WavRecorder(
            openAudioSource = { WavRecorder.RecorderConfig(blockUntilStopped(CountDownLatch(1)), 1, 4) }
        )

        service.startRecording(1L, 30)
        assertEquals(RecordingSplitDuration.MINUTES_30, service.sessionSplitDuration)
        stopAndAwait(service)

        service.startRecording(2L, 45)
        assertEquals(RecordingSplitDuration.MINUTES_45, service.sessionSplitDuration)
        stopAndAwait(service)
    }
}
