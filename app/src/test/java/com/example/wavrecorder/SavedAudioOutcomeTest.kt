package com.example.wavrecorder

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
 * What a session reports as saved is what actually holds audio. A file that received no audio is
 * deleted when it is closed, so a result never points at it: a partial save names the last file
 * that holds audio, and a session that saved none says nothing was saved -- never "saved up to
 * that point" -- at the recorder, the service, the persisted outcome and its message alike.
 * A finalization failure keeps its own may-need-recovery meaning. Silent audio is audio.
 */
@RunWith(RobolectricTestRunner::class)
class SavedAudioOutcomeTest {

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

    /** Delivers [chunks] chunks of [chunkBytes] filled with [fill], then only empty reads --
     * paced like a microphone throughout. */
    private class Source(private val chunks: Int, private val chunkBytes: Int, private val fill: Byte = 7) : AudioSource {
        val reads = AtomicInteger()
        override fun startRecording() {}
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            Thread.sleep(1)
            if (reads.incrementAndGet() <= chunks) {
                buffer.fill(fill, offset, offset + chunkBytes)
                return chunkBytes
            }
            Thread.sleep(1)
            return 0
        }
        override fun stop() {}
        override fun release() {}
    }

    /**
     * Each file's writer: the first file's first audio write (anything after the 44-byte header)
     * waits for [release]; audio write [failAtWrite] of file [failPart] throws. Header patches and
     * close are left alone.
     */
    private class PartWriters(
        private val release: CountDownLatch,
        private val failPart: Int,
        private val failAtWrite: Int
    ) {
        val firstWriteEntered = CountDownLatch(1)
        private val parts = AtomicInteger()

        fun wrap(channel: FileChannel): SegmentWriter {
            val part = parts.incrementAndGet()
            return object : SegmentWriter {
                private var audioWrites = 0
                override fun write(buffer: ByteBuffer): Int {
                    if (channel.position() >= 44) {
                        audioWrites++
                        if (part == 1 && audioWrites == 1) {
                            firstWriteEntered.countDown()
                            release.await(5, TimeUnit.SECONDS)
                        }
                        if (part == failPart && audioWrites == failAtWrite) throw IOException("write $failAtWrite of part $part failed")
                    }
                    return channel.write(buffer)
                }
                override fun position() = channel.position()
                override fun position(newPosition: Long) { channel.position(newPosition) }
                override fun close() = channel.close()
            }
        }
    }

    private fun dataSizeOf(file: File): Long = WavRiffParser.parse(file.inputStream())!!.dataSize

    private fun partFile(n: Int) = File(tempFolder.root, "part$n.wav")

    private fun partsTarget() = object : WavRecorder.NextTarget {
        private var n = 0
        override fun create(): OutputTarget = OutputTarget.FileTarget(partFile(++n))
    }

    private fun OutputTarget?.path() = (this as? OutputTarget.FileTarget)?.file?.absolutePath

    /**
     * Records [chunks] chunks of [chunkBytes] (48 kHz 16-bit mono, 1-second files) with the writer
     * held until every chunk was accepted and Stop requested -- so everything is written while
     * draining -- then returns the finalization result.
     */
    private fun recordAndStop(
        chunks: Int,
        chunkBytes: Int,
        failPart: Int = 0,
        failAtWrite: Int = 0,
        fill: Byte = 7
    ): Pair<WavRecorder, WavRecorder.FinalizeResult> {
        val release = latch()
        val writers = PartWriters(release, failPart, failAtWrite)
        val source = Source(chunks, chunkBytes, fill)
        val recorder = WavRecorder(startup = ImmediateStartup, segmentMaxSeconds = 1,
            openAudioSource = { WavRecorder.RecorderConfig(source, 48000, chunkBytes) },
            wrapChannel = writers::wrap)
        recorder.start(app(), partsTarget(), onSegmentStarted = {}, onAmplitude = {}, onError = {})
        assertTrue(writers.firstWriteEntered.await(3, TimeUnit.SECONDS))
        waitFor("every chunk accepted") { source.reads.get() > chunks }
        val pending = recorder.requestStop()
        release.countDown()
        return recorder to recorder.awaitFinalization(pending)
    }

    // ---- Recorder -----------------------------------------------------------------------------

    @Test
    fun `a write failure before any audio was saved reports that nothing was saved, and leaves no file`() {
        val (recorder, result) = recordAndStop(chunks = 3, chunkBytes = 64, failPart = 1, failAtWrite = 1)

        assertTrue("got $result", result is WavRecorder.FinalizeResult.NothingSaved)
        result as WavRecorder.FinalizeResult.NothingSaved
        assertTrue(result.cause is RecordingNotSavedException)
        assertEquals("write 1 of part 1 failed", result.cause.cause?.message)
        assertFalse("the empty file was deleted", partFile(1).exists())
        assertNull(recorder.lastSavedTarget)
        assertEquals(0L, recorder.lastRecordedMillis)
    }

    @Test
    fun `a write failure before any audio reached the next file names the earlier file, which holds audio`() {
        // 15 chunks of 9,600 bytes: 1.5 s. The first second fills part 1; part 2's first write fails.
        val (recorder, result) = recordAndStop(chunks = 15, chunkBytes = 9600, failPart = 2, failAtWrite = 1)

        assertTrue("got $result", result is WavRecorder.FinalizeResult.Incomplete)
        result as WavRecorder.FinalizeResult.Incomplete
        assertTrue(result.cause is IncompleteRecordingException)
        assertEquals(partFile(1).absolutePath, result.target.path())
        assertEquals("part 1 is whole", 96_000L, dataSizeOf(partFile(1)))
        assertFalse("the empty part 2 was deleted", partFile(2).exists())
        assertEquals(partFile(1).absolutePath, recorder.lastSavedTarget.path())
        assertEquals(1000L, recorder.lastRecordedMillis)
    }

    @Test
    fun `a write failure partway into the next file names that file, which holds the audio up to it`() {
        val (recorder, result) = recordAndStop(chunks = 15, chunkBytes = 9600, failPart = 2, failAtWrite = 3)

        assertTrue("got $result", result is WavRecorder.FinalizeResult.Incomplete)
        assertEquals(partFile(2).absolutePath, (result as WavRecorder.FinalizeResult.Incomplete).target.path())
        assertEquals(96_000L, dataSizeOf(partFile(1)))
        assertEquals("two chunks reached part 2", 19_200L, dataSizeOf(partFile(2)))
        assertEquals(partFile(2).absolutePath, recorder.lastSavedTarget.path())
    }

    @Test
    fun `silent audio that reached the file is saved audio, not absent audio`() {
        val (recorder, result) = recordAndStop(chunks = 3, chunkBytes = 64, failPart = 1, failAtWrite = 2, fill = 0)

        assertTrue("got $result", result is WavRecorder.FinalizeResult.Incomplete)
        assertEquals(partFile(1).absolutePath, (result as WavRecorder.FinalizeResult.Incomplete).target.path())
        assertEquals(64L, dataSizeOf(partFile(1)))
        assertEquals(partFile(1).absolutePath, recorder.lastSavedTarget.path())
    }

    @Test
    fun `a clean stop just after a split names the last file with audio, not the empty one deleted`() {
        // Exactly 1 s: part 1 fills, part 2 is opened, and nothing more arrives.
        val (recorder, result) = recordAndStop(chunks = 10, chunkBytes = 9600)

        assertEquals(WavRecorder.FinalizeResult.Ok, result)
        assertEquals(96_000L, dataSizeOf(partFile(1)))
        assertFalse(partFile(2).exists())
        assertEquals(partFile(1).absolutePath, recorder.lastSavedTarget.path())
    }

    @Test
    fun `a finalization failure at a split is a finalization failure, not a partial save`() {
        val release = latch()
        release.countDown()
        val source = Source(chunks = 15, chunkBytes = 9600)
        val parts = AtomicInteger()
        val recorder = WavRecorder(startup = ImmediateStartup, segmentMaxSeconds = 1,
            openAudioSource = { WavRecorder.RecorderConfig(source, 48000, 9600) },
            wrapChannel = { channel ->
                val part = parts.incrementAndGet()
                object : SegmentWriter {
                    override fun write(buffer: ByteBuffer): Int = channel.write(buffer)
                    override fun position() = channel.position()
                    override fun position(newPosition: Long) { channel.position(newPosition) }
                    override fun close() {
                        channel.close()
                        if (part == 1) throw IOException("part 1 didn't close")
                    }
                }
            })
        val errors = Collections.synchronizedList(mutableListOf<Exception>())
        recorder.start(app(), partsTarget(), onSegmentStarted = {}, onAmplitude = {}, onError = { errors += it })
        waitFor("the failed split is reported") { errors.isNotEmpty() }

        val result = recorder.stop()
        assertTrue("got $result", result is WavRecorder.FinalizeResult.Failed)
        assertEquals(partFile(1).absolutePath, (result as WavRecorder.FinalizeResult.Failed).target.path())
        assertEquals("the failure finalizing part 1 itself, not a later symptom", "part 1 didn't close", result.cause.cause?.message)
    }

    // ---- Service, persisted outcome and message ----------------------------------------------

    private class Log : RecordingService.Listener {
        val events = Collections.synchronizedList(mutableListOf<String>())
        @Volatile var error: Exception? = null
        @Volatile var stoppedWith: OutputTarget? = null
        override fun onAmplitude(amplitude: Float) {}
        override fun onSegmentStarted(target: OutputTarget, partNumber: Int) {}
        override fun onError(e: Exception) { events += "error"; error = e }
        override fun onStopped(lastTarget: OutputTarget?) { events += "stopped"; stoppedWith = lastTarget }
        override fun onFinalizationFailed(target: OutputTarget?, cause: Exception) { events += "finalization failed" }
        override fun onFinalizationUnknown(target: OutputTarget?) { events += "unknown" }
    }

    private fun service(recorder: WavRecorder): RecordingService =
        Robolectric.buildService(RecordingService::class.java).create().get().apply {
            destinationManager = object : DestinationManager(app()) {
                override fun createOutputFile(fileName: String): OutputTarget = OutputTarget.FileTarget(File(tempFolder.root, fileName))
            }
            this.recorder = recorder
        }

    /** A service whose first audio write -- written while draining after Stop -- fails. */
    private fun stopWithFirstWriteFailing(listener: RecordingService.Listener?): RecordingService {
        val release = latch()
        val writers = PartWriters(release, failPart = 1, failAtWrite = 1)
        val source = Source(chunks = 3, chunkBytes = 64)
        val service = service(WavRecorder(startup = ImmediateStartup,
            openAudioSource = { WavRecorder.RecorderConfig(source, 48000, 64) }, wrapChannel = writers::wrap))
        service.listener = listener
        service.startRecording(1L)
        assertTrue(writers.firstWriteEntered.await(3, TimeUnit.SECONDS))
        waitFor("every chunk accepted") { source.reads.get() > 3 }
        service.stopRecording()
        release.countDown()
        waitFor("finalized") { !service.isFinalizing }
        return service
    }

    @Test
    fun `the service reports a write failure before any audio was saved once, live, as nothing saved`() {
        val log = Log()
        stopWithFirstWriteFailing(log)
        assertEquals(listOf("error"), log.events)
        assertTrue("got ${log.error}", log.error is RecordingNotSavedException)
    }

    @Test
    fun `unattended, it is persisted as a failure with no file, and its message claims nothing was saved`() {
        val outcome = stopWithFirstWriteFailing(null).consumePendingOutcome()
        assertTrue("got $outcome", outcome is RecordingOutcome.Failed)
        assertEquals(app().getString(R.string.recording_not_saved_error), (outcome as RecordingOutcome.Failed).cause.message)
    }

    /**
     * 100 Hz 16-bit mono -- 200 bytes a second, so a 30-minute file is 360,000 bytes: 18 chunks of
     * 100 s each. Part 1 fills; part 2's [failAtWrite]-th audio write fails, while recording.
     */
    private fun recordIntoSecondPart(failAtWrite: Int, listener: RecordingService.Listener?): RecordingService {
        val release = latch()
        release.countDown()
        val writers = PartWriters(release, failPart = 2, failAtWrite = failAtWrite)
        val source = Source(chunks = 1_000, chunkBytes = 20_000)
        val service = service(WavRecorder(startup = ImmediateStartup,
            openAudioSource = { WavRecorder.RecorderConfig(source, 100, 20_000) }, wrapChannel = writers::wrap))
        service.listener = listener
        service.startRecording(1L, splitMinutes = 30)
        waitFor("part 2's write fails and the outcome is delivered", timeoutMs = 10_000) { service.hasOutcome(listener) }
        return service
    }

    /** Whether the session's one outcome has been delivered: live to [listener], or persisted. */
    private fun RecordingService.hasOutcome(listener: RecordingService.Listener?): Boolean =
        !isFinalizing && ((listener as? Log)?.events?.isNotEmpty() ?: (pendingOutcomeStore.peekSessionId() != null))

    private fun File.isPart(n: Int) = name.endsWith("_part0$n.wav")

    @Test
    fun `when part 2 receives no audio, the partial save is reported against part 1, which holds it`() {
        val outcome = recordIntoSecondPart(failAtWrite = 1, listener = null).consumePendingOutcome()

        assertTrue("got $outcome", outcome is RecordingOutcome.FailedButSaved)
        val saved = ((outcome as RecordingOutcome.FailedButSaved).target as OutputTarget.FileTarget).file
        assertTrue("reported ${saved.name}", saved.isPart(1))
        assertTrue(saved.exists())
        assertEquals(360_000L, dataSizeOf(saved))
        assertTrue("no part 2 left", tempFolder.root.listFiles()!!.none { it.isPart(2) })
        assertEquals(app().getString(R.string.recording_incomplete_error), outcome.cause.message)
    }

    @Test
    fun `live, the same partial save is reported once, as saved up to the failure`() {
        val log = Log()
        recordIntoSecondPart(failAtWrite = 1, listener = log)
        assertEquals(listOf("error"), log.events)
        assertTrue("got ${log.error}", log.error is IncompleteRecordingException)
    }

    @Test
    fun `a microphone that delivers nothing ends the session as nothing saved, not saved up to that point`() {
        val source = Source(chunks = 0, chunkBytes = 64)
        val service = service(WavRecorder(startup = ImmediateStartup, noProgressTimeoutMs = 300,
            openAudioSource = { WavRecorder.RecorderConfig(source, 48000, 64) }))
        service.startRecording(1L)
        waitFor("the stall ends the session") { service.hasOutcome(null) }

        val outcome = service.consumePendingOutcome()
        assertTrue("got $outcome", outcome is RecordingOutcome.Failed)
        assertEquals(app().getString(R.string.mic_stalled_nothing_saved), (outcome as RecordingOutcome.Failed).cause.message)
        assertTrue("no file left", tempFolder.root.listFiles()!!.isEmpty())
    }

    @Test
    fun `live, a stall before any audio is reported as nothing saved, for the microphone's reason`() {
        val log = Log()
        val source = Source(chunks = 0, chunkBytes = 64)
        val service = service(WavRecorder(startup = ImmediateStartup, noProgressTimeoutMs = 300,
            openAudioSource = { WavRecorder.RecorderConfig(source, 48000, 64) }))
        service.listener = log
        service.startRecording(1L)
        waitFor("the stall ends the session") { service.hasOutcome(log) }

        assertEquals(listOf("error"), log.events)
        val error = log.error
        assertTrue("got $error", error is NothingSavedException)
        assertTrue((error as NothingSavedException).reason is MicrophoneStalledException)
    }

    @Test
    fun `a stop before any audio was recorded reports no file, not the deleted empty one`() {
        val log = Log()
        val source = Source(chunks = 0, chunkBytes = 64)
        val service = service(WavRecorder(startup = ImmediateStartup,
            openAudioSource = { WavRecorder.RecorderConfig(source, 48000, 64) }))
        service.listener = log
        service.startRecording(1L)
        waitFor("recording") { service.lastTarget != null }
        service.stopRecording()
        waitFor("finalized") { !service.isFinalizing && log.events.isNotEmpty() }

        assertEquals(listOf("stopped"), log.events)
        assertNull(log.stoppedWith)
        assertTrue(tempFolder.root.listFiles()!!.isEmpty())
    }

    @Test
    fun `every nothing-saved message says nothing was saved, and none claims audio was`() {
        val reasons = listOf(
            MicrophoneDisconnectedException("x"), MicrophoneRouteChangedException("x"),
            MicrophoneStalledException("x"), StorageTooSlowException("x"), IOException("x")
        )
        val messages = reasons.map { nothingSavedMessage(app(), NothingSavedException(it)) } +
            app().getString(R.string.recording_not_saved_error)
        messages.forEach { message ->
            assertTrue(message, message.contains("nothing", ignoreCase = true))
            assertFalse(message, message.contains("up to that point"))
        }
        assertEquals(app().getString(R.string.recording_error_nothing_saved, "x"), messages[4])
    }
}
