package com.example.wavrecorder

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * After Stop, the audio already captured is still written -- and if writing it fails, the result
 * says the file is incomplete rather than reporting a complete save (follow-up review R1). A
 * cleanly closed WAV holding only a prefix of what was captured is a valid partial file, not a
 * save of everything; and no header repair can supply audio that was never written.
 */
@RunWith(RobolectricTestRunner::class)
class DrainIntegrityTest {

    @get:Rule
    val isolation = TestIsolation()

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun app(): Application = ApplicationProvider.getApplicationContext()

    private val releaseAtEnd = Collections.synchronizedList(mutableListOf<CountDownLatch>())

    @After
    fun tearDown() {
        releaseAtEnd.forEach { it.countDown() }
        RecordingOwnership.resetForTest()
    }

    private fun latch() = CountDownLatch(1).also { releaseAtEnd += it }

    private fun waitFor(what: String, timeoutMs: Long = 3000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(2)
        }
        shadowOf(Looper.getMainLooper()).idle()
        check(condition()) { "timed out waiting: $what" }
    }

    /** Delivers [chunks] chunks of [chunkBytes], then only empty reads (paced like a microphone). */
    private class Source(private val chunks: Int, private val chunkBytes: Int) : AudioSource {
        val reads = AtomicInteger()
        override fun startRecording() {}
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (reads.incrementAndGet() <= chunks) {
                buffer.fill(7, offset, offset + chunkBytes)
                return chunkBytes
            }
            Thread.sleep(2)
            return 0
        }
        override fun stop() {}
        override fun release() {}
    }

    /**
     * A writer whose first audio write (anything after the 44-byte header) waits for [release],
     * and whose [failAtWrite]-th audio write throws; header patches and close are left alone.
     */
    private class ScriptedWriter(
        private val channel: FileChannel,
        private val firstWriteEntered: CountDownLatch,
        private val release: CountDownLatch,
        private val failAtWrite: Int
    ) : SegmentWriter {
        private var audioWrites = 0
        override fun write(buffer: ByteBuffer): Int {
            if (channel.position() >= 44) {
                audioWrites++
                if (audioWrites == 1) {
                    firstWriteEntered.countDown()
                    release.await(5, TimeUnit.SECONDS)
                }
                if (audioWrites == failAtWrite) throw IOException("injected write failure")
            }
            return channel.write(buffer)
        }
        override fun position() = channel.position()
        override fun position(newPosition: Long) { channel.position(newPosition) }
        override fun close() = channel.close()
    }

    private fun dataSizeOf(file: File): Long = WavRiffParser.parse(file.inputStream())!!.dataSize

    private fun WavRecorder.begin(nextTarget: WavRecorder.NextTarget, errors: MutableList<Exception>) =
        start(app(), nextTarget, onSegmentStarted = {}, onAmplitude = {}, onError = { errors += it })

    @Test
    fun `a write that fails while draining after Stop makes the result incomplete, never a complete save`() {
        val entered = latch()
        val release = latch()
        val source = Source(chunks = 3, chunkBytes = 64)
        val file = tempFolder.newFile("drain-failure.wav")
        val errors = Collections.synchronizedList(mutableListOf<Exception>())
        val recorder = WavRecorder(startup = ImmediateStartup,
            openAudioSource = { WavRecorder.RecorderConfig(source, 48000, 64) },
            wrapChannel = { ScriptedWriter(it, entered, release, failAtWrite = 2) })
        recorder.begin(WavRecorder.NextTarget { OutputTarget.FileTarget(file) }, errors)
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        waitFor("all three chunks accepted") { source.reads.get() > 3 }

        val pending = recorder.requestStop()
        release.countDown()
        val result = recorder.awaitFinalization(pending)

        assertTrue("got $result", result is WavRecorder.FinalizeResult.Incomplete)
        result as WavRecorder.FinalizeResult.Incomplete
        assertTrue(result.cause is IncompleteRecordingException)
        assertEquals("injected write failure", result.cause.cause?.message)
        assertEquals(file.absolutePath, (result.target as OutputTarget.FileTarget).file.absolutePath)
        assertEquals("the valid prefix is kept: 64 of the 192 accepted bytes", 64L, dataSizeOf(file))
    }

    @Test
    fun `a failure opening the next file while draining makes the result incomplete, and the finished file stays whole`() {
        // 48 kHz 16-bit mono: 1-second files of 96,000 bytes; 15 chunks of 9,600 bytes are 1.5 s.
        val entered = latch()
        val release = latch()
        val source = Source(chunks = 15, chunkBytes = 9600)
        val first = tempFolder.newFile("part1.wav")
        var created = 0
        val errors = Collections.synchronizedList(mutableListOf<Exception>())
        val recorder = WavRecorder(startup = ImmediateStartup, segmentMaxSeconds = 1,
            openAudioSource = { WavRecorder.RecorderConfig(source, 48000, 9600) },
            wrapChannel = { ScriptedWriter(it, entered, release, failAtWrite = Int.MAX_VALUE) })
        recorder.begin(WavRecorder.NextTarget {
            if (++created == 1) OutputTarget.FileTarget(first) else throw IOException("the next file can't be created")
        }, errors)
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        waitFor("all chunks accepted") { source.reads.get() > 15 }

        val pending = recorder.requestStop()
        release.countDown()
        val result = recorder.awaitFinalization(pending)

        assertTrue("got $result", result is WavRecorder.FinalizeResult.Incomplete)
        assertEquals("the first file is complete", 96_000L, dataSizeOf(first))
    }

    @Test
    fun `a header patch that fails while finalizing after Stop is a finalization failure`() {
        val source = Source(chunks = 3, chunkBytes = 64)
        val file = tempFolder.newFile("header-failure.wav")
        val recorder = WavRecorder(startup = ImmediateStartup,
            openAudioSource = { WavRecorder.RecorderConfig(source, 48000, 64) },
            wrapChannel = { channel ->
                object : SegmentWriter {
                    override fun write(buffer: ByteBuffer): Int = channel.write(buffer)
                    override fun position() = channel.position()
                    override fun position(newPosition: Long) {
                        if (newPosition == 0L) throw IOException("header patch failed")
                        channel.position(newPosition)
                    }
                    override fun close() = channel.close()
                }
            })
        recorder.begin(WavRecorder.NextTarget { OutputTarget.FileTarget(file) }, mutableListOf())
        waitFor("all chunks accepted") { source.reads.get() > 3 }

        val result = recorder.stop()
        assertTrue("got $result", result is WavRecorder.FinalizeResult.Failed)
    }

    @Test
    fun `an overflow followed by a failed drain write is incomplete, not saved`() {
        val entered = latch()
        val release = latch()
        val source = Source(chunks = 1_000, chunkBytes = 64)
        val file = tempFolder.newFile("overflow-then-failure.wav")
        val errors = Collections.synchronizedList(mutableListOf<Exception>())
        val recorder = WavRecorder(startup = ImmediateStartup, maxQueuedBuffers = 3,
            openAudioSource = { WavRecorder.RecorderConfig(source, 48000, 64) },
            wrapChannel = { ScriptedWriter(it, entered, release, failAtWrite = 2) })
        recorder.begin(WavRecorder.NextTarget { OutputTarget.FileTarget(file) }, errors)
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        waitFor("the overflow is reported") { errors.isNotEmpty() }
        assertTrue(errors.single() is StorageTooSlowException)

        release.countDown()
        val result = recorder.stop()

        assertTrue("got $result", result is WavRecorder.FinalizeResult.Incomplete)
        assertEquals(64L, dataSizeOf(file))
    }

    @Test
    fun `a clean drain after Stop writes every accepted byte and is a complete save`() {
        val entered = latch()
        val release = latch()
        val source = Source(chunks = 3, chunkBytes = 64)
        val file = tempFolder.newFile("clean-drain.wav")
        val recorder = WavRecorder(startup = ImmediateStartup,
            openAudioSource = { WavRecorder.RecorderConfig(source, 48000, 64) },
            wrapChannel = { ScriptedWriter(it, entered, release, failAtWrite = Int.MAX_VALUE) })
        recorder.begin(WavRecorder.NextTarget { OutputTarget.FileTarget(file) }, mutableListOf())
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        waitFor("all three chunks accepted") { source.reads.get() > 3 }

        val pending = recorder.requestStop()
        release.countDown()
        assertEquals(WavRecorder.FinalizeResult.Ok, recorder.awaitFinalization(pending))
        assertEquals(192L, dataSizeOf(file))
    }

    // ---- The service reports it once, as saved only up to the failure -------------------------

    private fun serviceWithFailingDrain(file: File, entered: CountDownLatch, release: CountDownLatch): Pair<RecordingService, Source> {
        val source = Source(chunks = 3, chunkBytes = 64)
        val service = Robolectric.buildService(RecordingService::class.java).create().get()
        service.destinationManager = object : DestinationManager(app()) {
            override fun createOutputFile(fileName: String): OutputTarget = OutputTarget.FileTarget(file)
        }
        service.recorder = WavRecorder(startup = ImmediateStartup,
            openAudioSource = { WavRecorder.RecorderConfig(source, 48000, 64) },
            wrapChannel = { ScriptedWriter(it, entered, release, failAtWrite = 2) })
        return service to source
    }

    @Test
    fun `a normal Stop whose drain fails is reported once, live, as saved only up to the failure`() {
        val entered = latch()
        val release = latch()
        val (service, source) = serviceWithFailingDrain(tempFolder.newFile("live.wav"), entered, release)
        val log = Collections.synchronizedList(mutableListOf<String>())
        var error: Exception? = null
        service.listener = object : RecordingService.Listener {
            override fun onAmplitude(amplitude: Float) {}
            override fun onSegmentStarted(target: OutputTarget, partNumber: Int) {}
            override fun onError(e: Exception) { log += "error"; error = e }
            override fun onStopped(lastTarget: OutputTarget?) { log += "stopped" }
            override fun onFinalizationFailed(target: OutputTarget?, cause: Exception) { log += "finalization failed" }
            override fun onFinalizationUnknown(target: OutputTarget?) { log += "unknown" }
        }
        service.startRecording(1L)
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        waitFor("all chunks accepted") { source.reads.get() > 3 }

        service.stopRecording()
        release.countDown()
        waitFor("the outcome is delivered") { !service.isFinalizing }

        assertEquals("exactly one outcome, and not 'saved'", listOf("error"), log)
        assertTrue(error is IncompleteRecordingException)
    }

    @Test
    fun `with no screen attached, the incomplete outcome is persisted with its message`() {
        val entered = latch()
        val release = latch()
        val (service, source) = serviceWithFailingDrain(tempFolder.newFile("unattended.wav"), entered, release)
        service.startRecording(1L)
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        waitFor("all chunks accepted") { source.reads.get() > 3 }

        service.stopRecording()
        release.countDown()
        waitFor("finalized") { !service.isFinalizing }

        val outcome = service.consumePendingOutcome()
        assertTrue("got $outcome", outcome is RecordingOutcome.FailedButSaved)
        assertNotNull(outcome as RecordingOutcome.FailedButSaved)
        assertEquals(app().getString(R.string.recording_incomplete_error), outcome.cause.message)
    }
}
