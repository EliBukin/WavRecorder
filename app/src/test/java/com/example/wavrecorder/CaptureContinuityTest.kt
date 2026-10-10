package com.example.wavrecorder

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * The recorder's capture continuity and safeguards (audit findings F3, F4, F6, F7): the
 * microphone keeps being drained while storage stalls, every accepted frame reaches the file, an
 * overflow ends the recording explicitly, a source that delivers nothing is detected, silent audio
 * is not mistaken for that, the recorded duration comes from written frames, and intervals follow
 * the monotonic clock.
 */
@RunWith(RobolectricTestRunner::class)
class CaptureContinuityTest {

    @get:Rule
    val isolation = TestIsolation()

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val releaseAtEnd = Collections.synchronizedList(mutableListOf<CountDownLatch>())

    @After
    fun tearDown() {
        releaseAtEnd.forEach { it.countDown() }
        RecordingOwnership.resetForTest()
    }

    private fun latch(): CountDownLatch = CountDownLatch(1).also { releaseAtEnd += it }

    private fun waitFor(what: String, timeoutMs: Long = 3000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(2)
        check(condition()) { "timed out waiting: $what" }
    }

    private fun idleMain() = shadowOf(Looper.getMainLooper()).idle()

    /**
     * A microphone delivering [chunks] numbered chunks of [chunkBytes] (a known byte sequence, so
     * any lost or reordered audio shows), then either nothing (reads return 0) or silence. Records
     * everything it handed out.
     */
    private class CountingSource(
        private val chunks: Int,
        private val chunkBytes: Int,
        private val afterwards: After = After.NOTHING,
        private val connected: () -> Boolean = { true }
    ) : AudioSource {
        enum class After { NOTHING, SILENCE }

        val delivered = ByteArrayOutputStream()
        val reads = AtomicInteger()
        private var next = 0

        override fun startRecording() {}
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            reads.incrementAndGet()
            if (next < chunks) {
                val n = minOf(chunkBytes, length)
                for (i in 0 until n) buffer[offset + i] = ((next * 7 + i) % 251).toByte()
                synchronized(delivered) { delivered.write(buffer, offset, n) }
                next++
                return n
            }
            if (afterwards == After.SILENCE) {
                // Paced like a real microphone (no faster than real time): 64 bytes of 48 kHz
                // 16-bit mono are 0.67 ms of audio.
                Thread.sleep(1)
                buffer.fill(0, offset, offset + minOf(chunkBytes, length))
                return minOf(chunkBytes, length)
            }
            Thread.sleep(1)
            return 0
        }
        override fun stop() {}
        override fun release() {}
        override fun isDeviceConnected(): Boolean = connected()
        val deliveredBytes: ByteArray get() = synchronized(delivered) { delivered.toByteArray() }
    }

    /** A writer whose first audio write (anything after the header) blocks until [release]. */
    private fun stallingWriter(entered: CountDownLatch, release: CountDownLatch): (java.nio.channels.FileChannel) -> SegmentWriter = { channel ->
        object : SegmentWriter {
            override fun write(buffer: ByteBuffer): Int {
                if (channel.position() >= 44 && entered.count > 0) {
                    entered.countDown()
                    release.await(10, TimeUnit.SECONDS)
                }
                return channel.write(buffer)
            }
            override fun position() = channel.position()
            override fun position(newPosition: Long) { channel.position(newPosition) }
            override fun close() = channel.close()
        }
    }

    private fun dataOf(file: File): ByteArray {
        val parsed = WavRiffParser.parse(file.inputStream())!!
        return file.readBytes().copyOfRange(parsed.dataOffset.toInt(), (parsed.dataOffset + parsed.dataSize).toInt())
    }

    private fun WavRecorder.begin(
        file: () -> File,
        errors: MutableList<Exception> = Collections.synchronizedList(mutableListOf())
    ): MutableList<Exception> {
        start(
            ApplicationProvider.getApplicationContext(),
            WavRecorder.NextTarget { OutputTarget.FileTarget(file()) },
            onSegmentStarted = {}, onAmplitude = {}, onError = { errors += it }
        )
        return errors
    }

    // ---- F3: storage latency no longer stops the microphone being drained ----------------------

    @Test
    fun `a stalled write doesn't stop the microphone being read, and every accepted frame reaches the file`() {
        val source = CountingSource(chunks = 50, chunkBytes = 64)
        val entered = latch()
        val release = latch()
        val file = tempFolder.newFile("stalled.wav")
        val recorder = WavRecorder(startup = ImmediateStartup, wrapChannel = stallingWriter(entered, release),
            openAudioSource = { WavRecorder.RecorderConfig(source, 48000, 64) })
        val errors = recorder.begin({ file })

        assertTrue(entered.await(3, TimeUnit.SECONDS))
        // The writer is stuck in its first write; the microphone is still drained meanwhile.
        waitFor("all 50 chunks read while the write stalls") { source.reads.get() >= 50 }
        assertEquals("still stalled", 1L, release.count)

        release.countDown()
        assertEquals(WavRecorder.FinalizeResult.Ok, recorder.stop())
        assertArrayEquals("no accepted frame lost or reordered", source.deliveredBytes, dataOf(file))
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `a slow new file at rollover doesn't stop capture, and the files together hold every frame`() {
        // 48 kHz 16-bit mono: 9,600-byte chunks are 0.1 s, so the 1 s segments roll over every 10.
        val source = CountingSource(chunks = 25, chunkBytes = 9600)
        val files = mutableListOf<File>()
        val secondFileEntered = latch()
        val createSecondFile = latch()
        val recorder = WavRecorder(startup = ImmediateStartup, segmentMaxSeconds = 1,
            openAudioSource = { WavRecorder.RecorderConfig(source, 48000, 9600) })
        recorder.begin({
            if (files.size == 1) {
                secondFileEntered.countDown()
                createSecondFile.await(10, TimeUnit.SECONDS) // a slow provider creating the next file
            }
            tempFolder.newFile("part${files.size + 1}.wav").also { files += it }
        })

        assertTrue(secondFileEntered.await(3, TimeUnit.SECONDS))
        waitFor("capture carries on while the next file is created") { source.reads.get() >= 25 }
        createSecondFile.countDown()

        assertEquals(WavRecorder.FinalizeResult.Ok, recorder.stop())
        val written = files.map(::dataOf).fold(ByteArray(0)) { all, part -> all + part }
        assertArrayEquals(source.deliveredBytes, written)
        assertEquals(3, files.size)
        // F6: the recorded duration counts every file's frames: 25 x 0.1 s.
        assertEquals(2_500L, recorder.lastRecordedMillis)
    }

    @Test
    fun `when the capture queue overflows, recording stops explicitly and everything accepted is saved`() {
        val source = CountingSource(chunks = 1_000, chunkBytes = 64)
        val entered = latch()
        val release = latch()
        val file = tempFolder.newFile("overflow.wav")
        val recorder = WavRecorder(startup = ImmediateStartup, maxQueuedBuffers = 3, wrapChannel = stallingWriter(entered, release),
            openAudioSource = { WavRecorder.RecorderConfig(source, 48000, 64) })
        val errors = recorder.begin({ file })

        assertTrue(entered.await(3, TimeUnit.SECONDS))
        waitFor("the overflow ends the recording") { !recorder.isActive }
        release.countDown()
        idleMain()
        waitFor("the overflow is reported") { idleMain(); errors.isNotEmpty() }

        assertTrue(errors.single() is StorageTooSlowException)
        assertEquals(WavRecorder.FinalizeResult.Ok, recorder.stop())
        // Exactly the three buffers the queue holds were accepted -- all written, none dropped
        // silently, nothing read beyond them kept.
        assertArrayEquals(source.deliveredBytes.copyOf(3 * 64), dataOf(file))
    }

    @Test
    fun `however far the audio runs ahead of the system clock, a full queue is an overflow - capture never waits`() {
        // A drift simulation at its limit: the monotonic clock never moves, so by the time the
        // queue fills the audio is infinitely "ahead" of it. An audio clock can drift from the
        // system clock, so the source's apparent pace must not change overflow semantics: capture
        // must not wait on the writer (and stop draining the microphone) because of it.
        val source = CountingSource(chunks = 1_000, chunkBytes = 64)
        val entered = latch()
        val release = latch()
        val file = tempFolder.newFile("drift.wav")
        val recorder = WavRecorder(startup = ImmediateStartup, maxQueuedBuffers = 3, clock = { 0L },
            noProgressTimeoutMs = Long.MAX_VALUE, wrapChannel = stallingWriter(entered, release),
            openAudioSource = { WavRecorder.RecorderConfig(source, 48000, 64) })
        val errors = recorder.begin({ file })

        assertTrue(entered.await(3, TimeUnit.SECONDS))
        waitFor("the overflow is reported while the writer is still stalled") { idleMain(); errors.isNotEmpty() }
        assertTrue(errors.single() is StorageTooSlowException)
        assertEquals("the writer was still stalled throughout", 1L, release.count)
        release.countDown()
        assertEquals(WavRecorder.FinalizeResult.Ok, recorder.stop())
        assertArrayEquals(source.deliveredBytes.copyOf(3 * 64), dataOf(file))
    }

    @Test
    fun `a read that never returns after stop no longer keeps the file from being finalized`() {
        val stuck = latch()
        var reads = 0
        val source = object : AudioSource {
            override fun startRecording() {}
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (++reads == 1) {
                    buffer.fill(3, offset, offset + 64)
                    return 64
                }
                stuck.await(10, TimeUnit.SECONDS) // a driver that ignores stop()
                return -1
            }
            override fun stop() {}
            override fun release() {}
        }
        val file = tempFolder.newFile("stuck.wav")
        val recorder = WavRecorder(startup = ImmediateStartup, threadJoinTimeoutMs = 600,
            openAudioSource = { WavRecorder.RecorderConfig(source, 48000, 64) })
        recorder.begin({ file })
        waitFor("the first chunk") { reads >= 2 }

        assertEquals("finalized even though the read never returned", WavRecorder.FinalizeResult.Ok, recorder.stop())
        assertEquals(64L, WavRiffParser.parse(file.inputStream())!!.dataSize)
    }

    // ---- F4: a source that delivers nothing is detected; silence is not ------------------------

    @Test
    fun `a disconnected source that only ever returns zero bytes is still detected`() {
        val source = CountingSource(chunks = 0, chunkBytes = 4, connected = { false })
        val recorder = WavRecorder(startup = ImmediateStartup, headerFlushIntervalMs = 0,
            openAudioSource = { WavRecorder.RecorderConfig(source, 48000, 4) })
        val errors = recorder.begin({ tempFolder.newFile("zero.wav") })

        waitFor("the disconnect is reported") { idleMain(); errors.isNotEmpty() }
        assertTrue(errors.single() is MicrophoneDisconnectedException)
        assertFalse(recorder.isActive)
        recorder.stop()
    }

    @Test
    fun `no audio at all for longer than the limit ends the recording as stalled, and what was captured is saved`() {
        val source = CountingSource(chunks = 3, chunkBytes = 64) // then only empty reads
        val file = tempFolder.newFile("stalled-source.wav")
        val recorder = WavRecorder(startup = ImmediateStartup, noProgressTimeoutMs = 300,
            openAudioSource = { WavRecorder.RecorderConfig(source, 48000, 64) })
        val errors = recorder.begin({ file })

        waitFor("the stall is reported") { idleMain(); errors.isNotEmpty() }
        assertTrue(errors.single() is MicrophoneStalledException)
        assertEquals(WavRecorder.FinalizeResult.Ok, recorder.stop())
        assertArrayEquals(source.deliveredBytes, dataOf(file))
    }

    @Test
    fun `a read that blocks without returning is detected as stalled too`() {
        val blocked = latch()
        var reads = 0
        val source = object : AudioSource {
            override fun startRecording() {}
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (++reads == 1) {
                    buffer.fill(1, offset, offset + 64)
                    return 64
                }
                blocked.await(10, TimeUnit.SECONDS)
                return -1
            }
            override fun stop() { blocked.countDown() }
            override fun release() {}
        }
        val recorder = WavRecorder(startup = ImmediateStartup, noProgressTimeoutMs = 300,
            openAudioSource = { WavRecorder.RecorderConfig(source, 48000, 64) })
        val errors = recorder.begin({ tempFolder.newFile("blocked.wav") })

        waitFor("the stall is reported") { idleMain(); errors.isNotEmpty() }
        assertTrue(errors.single() is MicrophoneStalledException)
        assertEquals(WavRecorder.FinalizeResult.Ok, recorder.stop())
    }

    @Test
    fun `short runs of empty reads recover without an error`() {
        var reads = 0
        val source = object : AudioSource {
            override fun startRecording() {}
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                reads++
                // Data, then about 50 empty reads (well under the limit), then data again.
                return if (reads in 2..50) 0 else { buffer.fill(2, offset, offset + 64); 64 }
            }
            override fun stop() {}
            override fun release() {}
        }
        val recorder = WavRecorder(startup = ImmediateStartup, noProgressTimeoutMs = 2_000,
            openAudioSource = { WavRecorder.RecorderConfig(source, 48000, 64) })
        val errors = recorder.begin({ tempFolder.newFile("gap.wav") })

        waitFor("audio resumes after the empty reads") { reads > 60 }
        idleMain()
        assertTrue(errors.isEmpty())
        assertTrue(recorder.isActive)
        assertEquals(WavRecorder.FinalizeResult.Ok, recorder.stop())
    }

    @Test
    fun `silent audio is audio - a silenced microphone keeps recording past the no-progress limit`() {
        val source = CountingSource(chunks = 2, chunkBytes = 64, afterwards = CountingSource.After.SILENCE)
        val recorder = WavRecorder(startup = ImmediateStartup, noProgressTimeoutMs = 150,
            openAudioSource = { WavRecorder.RecorderConfig(source, 48000, 64) })
        val errors = recorder.begin({ tempFolder.newFile("silent.wav") })

        Thread.sleep(600) // four times the limit, all of it zero-valued samples
        idleMain()
        assertTrue(errors.isEmpty())
        assertTrue(recorder.isActive)
        assertEquals(WavRecorder.FinalizeResult.Ok, recorder.stop())
    }

    // ---- F7: intervals follow the monotonic clock ------------------------------------------------

    @Test
    fun `health checks and the no-progress limit follow the injected monotonic clock, not elapsed wall time`() {
        val now = AtomicLong(1_000_000)
        val connected = java.util.concurrent.atomic.AtomicBoolean(true)
        val source = CountingSource(chunks = 1, chunkBytes = 64, connected = { connected.get() })
        val recorder = WavRecorder(startup = ImmediateStartup, headerFlushIntervalMs = 1_000, noProgressTimeoutMs = 60_000,
            clock = { now.get() }, openAudioSource = { WavRecorder.RecorderConfig(source, 48000, 64) })
        val errors = recorder.begin({ tempFolder.newFile("clock.wav") })
        waitFor("the first chunk") { source.reads.get() > 1 }

        // The device goes away. Real time passes, but the monotonic clock doesn't: nothing is due.
        connected.set(false)
        Thread.sleep(300)
        idleMain()
        assertTrue("no check before the monotonic interval elapses", errors.isEmpty())

        now.addAndGet(1_000)
        waitFor("the check runs once the interval has elapsed on the monotonic clock") { idleMain(); errors.isNotEmpty() }
        assertTrue(errors.single() is MicrophoneDisconnectedException)
        recorder.stop()
    }

    @Test
    fun `the no-progress limit is measured on the monotonic clock`() {
        val now = AtomicLong(5_000)
        val source = CountingSource(chunks = 1, chunkBytes = 64) // then empty reads
        val recorder = WavRecorder(startup = ImmediateStartup, noProgressTimeoutMs = 10_000,
            clock = { now.get() }, openAudioSource = { WavRecorder.RecorderConfig(source, 48000, 64) })
        val errors = recorder.begin({ tempFolder.newFile("clock-stall.wav") })
        waitFor("reads after the first chunk") { source.reads.get() > 5 }

        Thread.sleep(200)
        idleMain()
        assertTrue(errors.isEmpty())
        now.addAndGet(10_000)
        waitFor("stalled once 10 s have passed on the monotonic clock") { idleMain(); errors.isNotEmpty() }
        assertTrue(errors.single() is MicrophoneStalledException)
        recorder.stop()
    }
}
