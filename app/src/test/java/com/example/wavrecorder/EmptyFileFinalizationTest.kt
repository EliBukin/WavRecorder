package com.example.wavrecorder

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.After
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
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * Finalizing a file no audio was confirmed written to. It is deleted -- and never then reported
 * as a file needing recovery -- but only once it is certain there is nothing in it to keep and
 * that it is really gone; otherwise it is kept, with its journal record, for recovery. Whole
 * frames a failing write did store are audio, never discarded. The outcome, the journal and the
 * message agree.
 */
@RunWith(RobolectricTestRunner::class)
class EmptyFileFinalizationTest {

    @get:Rule
    val isolation = TestIsolation()

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun app(): Application = ApplicationProvider.getApplicationContext()

    private val readOnlyDirs = mutableListOf<File>()
    private val recorders = mutableListOf<WavRecorder>()

    @After
    fun tearDown() {
        readOnlyDirs.forEach { it.setWritable(true) }
        recorders.forEach { it.stop() }
        RecordingOwnership.resetForTest()
    }

    private fun waitFor(what: String, timeoutMs: Long = 5000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(2)
        }
        shadowOf(Looper.getMainLooper()).idle()
        check(condition()) { "timed out waiting: $what" }
    }

    /** Delivers [chunks] chunks of [chunkBytes], then only empty reads -- paced like a microphone. */
    private class Source(private val chunks: Int, private val chunkBytes: Int) : AudioSource {
        val reads = AtomicInteger()
        override fun startRecording() {}
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            Thread.sleep(1)
            if (reads.incrementAndGet() <= chunks) {
                buffer.fill(7, offset, offset + chunkBytes)
                return chunkBytes
            }
            Thread.sleep(1)
            return 0
        }
        override fun stop() {}
        override fun release() {}
    }

    /** How one file's writer misbehaves. */
    private enum class Fault {
        NONE,
        /** close() closes the channel, then reports failure. */
        CLOSE_FAILS,
        /** The header patch at finalization fails. */
        PATCH_FAILS,
        /** The first audio write stores the whole buffer, then reports failure without saying so. */
        WRITE_STORES_THEN_FAILS,
        /** The first audio write stores only [SHORT_WRITE_BYTES] bytes; the next call fails. */
        SHORT_WRITE_THEN_FAILS
    }

    private class FaultyWriter(private val channel: FileChannel, private val fault: Fault) : SegmentWriter {
        private var audioWrites = 0
        override fun write(buffer: ByteBuffer): Int {
            if (channel.position() >= 44) {
                audioWrites++
                when (fault) {
                    Fault.WRITE_STORES_THEN_FAILS -> if (audioWrites == 1) {
                        channel.write(buffer.duplicate())
                        throw IOException("the write failed after storing its bytes")
                    }
                    Fault.SHORT_WRITE_THEN_FAILS -> when (audioWrites) {
                        1 -> {
                            val part = buffer.duplicate().apply { limit(position() + SHORT_WRITE_BYTES) }
                            val n = channel.write(part)
                            buffer.position(buffer.position() + n)
                            return n
                        }
                        2 -> throw IOException("the storage filled up")
                    }
                    else -> Unit
                }
            }
            return channel.write(buffer)
        }
        override fun position() = channel.position()
        override fun position(newPosition: Long) {
            if (fault == Fault.PATCH_FAILS && newPosition == 0L && channel.position() > 44) throw IOException("the header patch failed")
            channel.position(newPosition)
        }
        override fun close() {
            channel.close()
            if (fault == Fault.CLOSE_FAILS) throw IOException("close failed")
        }
    }

    companion object {
        const val SHORT_WRITE_BYTES = 33 // 16 whole 16-bit frames, and one byte of the next
    }

    private fun journal() = ActiveSegmentJournal(app())

    private fun dataSizeOf(file: File): Long = WavRiffParser.parse(file.inputStream())!!.dataSize

    /** A recorder writing part N to `[dir]/partN.wav`, with [faultOfPart] deciding each writer. */
    private fun recorder(
        source: Source,
        chunkBytes: Int,
        faultOfPart: (Int) -> Fault,
        segmentSeconds: Long = 3600
    ): WavRecorder {
        val parts = AtomicInteger()
        return WavRecorder(startup = ImmediateStartup, segmentMaxSeconds = segmentSeconds,
            openAudioSource = { WavRecorder.RecorderConfig(source, 48000, chunkBytes) },
            wrapChannel = { FaultyWriter(it, faultOfPart(parts.incrementAndGet())) }
        ).also { recorders += it }
    }

    private fun WavRecorder.begin(dir: File, errors: MutableList<Exception> = Collections.synchronizedList(mutableListOf())) {
        var n = 0
        start(app(), WavRecorder.NextTarget { OutputTarget.FileTarget(File(dir, "part${++n}.wav")) },
            onSegmentStarted = {}, onAmplitude = {}, onError = { errors += it })
    }

    private fun OutputTarget?.file() = (this as? OutputTarget.FileTarget)?.file

    // ---- A file with no audio -------------------------------------------------------------------

    @Test
    fun `an empty file whose close fails is deleted, its record cleared, and nothing saved is reported with that cause`() {
        val dir = tempFolder.newFolder()
        val recorder = recorder(Source(chunks = 0, chunkBytes = 64), 64, { Fault.CLOSE_FAILS })
        recorder.begin(dir)
        waitFor("the file is open") { File(dir, "part1.wav").exists() }

        val result = recorder.stop()

        assertTrue("got $result", result is WavRecorder.FinalizeResult.NothingSaved)
        val cause = (result as WavRecorder.FinalizeResult.NothingSaved).cause
        assertTrue(cause is RecordingNotSavedException)
        assertEquals("the original failure", "close failed", cause.cause?.message)
        assertFalse(File(dir, "part1.wav").exists())
        assertNull("nothing left to recover", journal().peekActive())
        assertNull(recorder.lastSavedTarget)
    }

    @Test
    fun `when the empty last part's close fails, the earlier part is what was saved, and nothing is left to recover`() {
        // 48 kHz 16-bit mono, 1-second parts: 10 chunks of 9,600 bytes fill part 1 exactly.
        val dir = tempFolder.newFolder()
        val recorder = recorder(Source(chunks = 10, chunkBytes = 9600), 9600,
            { part -> if (part == 2) Fault.CLOSE_FAILS else Fault.NONE }, segmentSeconds = 1)
        recorder.begin(dir)
        waitFor("part 2 is open") { File(dir, "part2.wav").exists() }

        val result = recorder.stop()

        assertEquals(WavRecorder.FinalizeResult.Ok, result)
        assertEquals(File(dir, "part1.wav").absolutePath, recorder.lastSavedTarget.file()?.absolutePath)
        assertEquals(96_000L, dataSizeOf(File(dir, "part1.wav")))
        assertFalse(File(dir, "part2.wav").exists())
        assertNull(journal().peekActive())
    }

    @Test
    fun `an empty file that can't be deleted after its close failed is reported for recovery, with its record kept`() {
        val dir = tempFolder.newFolder()
        val recorder = recorder(Source(chunks = 0, chunkBytes = 64), 64, { Fault.CLOSE_FAILS })
        recorder.begin(dir)
        val file = File(dir, "part1.wav")
        waitFor("the file is open") { file.exists() }
        assertTrue(dir.setWritable(false).also { readOnlyDirs += dir })

        val result = recorder.stop()

        assertTrue("got $result", result is WavRecorder.FinalizeResult.Failed)
        result as WavRecorder.FinalizeResult.Failed
        assertEquals(file.absolutePath, result.target.file()?.absolutePath)
        assertEquals("close failed", result.cause.cause?.message)
        assertTrue("it still exists", file.exists())
        assertKeptForRecovery(file)
    }

    @Test
    fun `an empty file that closed cleanly but can't be deleted is a valid WAV with no audio, and nothing to recover`() {
        val dir = tempFolder.newFolder()
        val recorder = recorder(Source(chunks = 0, chunkBytes = 64), 64, { Fault.NONE })
        recorder.begin(dir)
        val file = File(dir, "part1.wav")
        waitFor("the file is open") { file.exists() }
        assertTrue(dir.setWritable(false).also { readOnlyDirs += dir })

        assertEquals(WavRecorder.FinalizeResult.Ok, recorder.stop())
        assertNull("no audio was saved", recorder.lastSavedTarget)
        assertEquals(0L, dataSizeOf(file))
        assertNull(journal().peekActive())
    }

    // ---- A failed write may have stored more than it confirmed -------------------------------------

    @Test
    fun `a write that stored its bytes but reported failure keeps the file for recovery, which restores the audio`() {
        val dir = tempFolder.newFolder()
        val recorder = recorder(Source(chunks = 3, chunkBytes = 64), 64, { Fault.WRITE_STORES_THEN_FAILS })
        val errors = Collections.synchronizedList(mutableListOf<Exception>())
        recorder.begin(dir, errors)
        waitFor("the write failure is reported") { errors.isNotEmpty() }

        val result = recorder.stop()
        val file = File(dir, "part1.wav")
        assertTrue("got $result", result is WavRecorder.FinalizeResult.Failed)
        assertEquals("the original failure", "the write failed after storing its bytes",
            (result as WavRecorder.FinalizeResult.Failed).cause.cause?.message)
        assertTrue("never deleted", file.exists())
        assertEquals(44L + 64, file.length())
        assertKeptForRecovery(file)

        // The next recovery pass settles it from what is physically there.
        val recovered = WavRecoveryManager().recoverIfNeeded(app()).single()
        assertTrue("got $recovered", recovered is RecoveryOutcome.Recovered)
        assertEquals(file.absolutePath, (recovered as RecoveryOutcome.Recovered).target.file()?.absolutePath)
        assertEquals(64L, recovered.recoveredAudioBytes)
        assertEquals(64L, dataSizeOf(file))
    }

    @Test
    fun `whole frames a failing write stored are saved audio, never discarded as an empty file`() {
        val dir = tempFolder.newFolder()
        val recorder = recorder(Source(chunks = 3, chunkBytes = 64), 64, { Fault.SHORT_WRITE_THEN_FAILS })
        val errors = Collections.synchronizedList(mutableListOf<Exception>())
        recorder.begin(dir, errors)
        waitFor("the write failure is reported") { errors.isNotEmpty() }

        val result = recorder.stop()
        val file = File(dir, "part1.wav")
        assertTrue("got $result", result is WavRecorder.FinalizeResult.Incomplete)
        assertEquals(file.absolutePath, (result as WavRecorder.FinalizeResult.Incomplete).target.file()?.absolutePath)
        assertEquals("16 whole frames; the stray byte isn't audio", 32L, dataSizeOf(file))
        assertEquals(file.absolutePath, recorder.lastSavedTarget.file()?.absolutePath)
        assertNull("finalized cleanly: nothing to recover", journal().peekActive())
    }

    // ---- A file holding audio -----------------------------------------------------------------------

    @Test
    fun `a file holding audio whose header patch fails is reported for recovery, with its record kept`() {
        val dir = tempFolder.newFolder()
        val recorder = recorder(Source(chunks = 3, chunkBytes = 64), 64, { Fault.PATCH_FAILS })
        recorder.begin(dir)
        val file = File(dir, "part1.wav")
        waitFor("audio was written") { file.length() >= 44L + 192 }

        val result = recorder.stop()

        assertTrue("got $result", result is WavRecorder.FinalizeResult.Failed)
        assertEquals("the header patch failed", (result as WavRecorder.FinalizeResult.Failed).cause.cause?.message)
        assertTrue(file.exists())
        assertKeptForRecovery(file)
    }

    /** [file]'s record was handed to recovery: queued, and no longer active (so it blocks nothing). */
    private fun assertKeptForRecovery(file: File) {
        assertNull(journal().peekActive())
        assertEquals(listOf(file.absolutePath), journal().peekRecoveryCandidates().map { it.target.file()?.absolutePath })
    }

    // ---- The service, the persisted outcome and the message agree -------------------------------------

    private class Log : RecordingService.Listener {
        val events = Collections.synchronizedList(mutableListOf<String>())
        @Volatile var error: Exception? = null
        @Volatile var target: OutputTarget? = null
        override fun onAmplitude(amplitude: Float) {}
        override fun onSegmentStarted(target: OutputTarget, partNumber: Int) {}
        override fun onError(e: Exception) { events += "error"; error = e }
        override fun onStopped(lastTarget: OutputTarget?) { events += "stopped"; target = lastTarget }
        override fun onFinalizationFailed(target: OutputTarget?, cause: Exception) { events += "finalization failed"; this.target = target }
        override fun onFinalizationUnknown(target: OutputTarget?) { events += "unknown" }
    }

    private fun service(dir: File, recorder: WavRecorder): RecordingService =
        Robolectric.buildService(RecordingService::class.java).create().get().apply {
            destinationManager = object : DestinationManager(app()) {
                override fun createOutputFile(fileName: String): OutputTarget = OutputTarget.FileTarget(File(dir, fileName))
            }
            this.recorder = recorder
        }

    /** Records nothing, stops, and returns the service once the outcome is delivered or persisted. */
    private fun stopBeforeAnyAudio(dir: File, fault: Fault, listener: Log?, beforeStop: () -> Unit = {}): RecordingService {
        val service = service(dir, recorder(Source(chunks = 0, chunkBytes = 64), 64, { fault }))
        service.listener = listener
        service.startRecording(1L)
        waitFor("the file is open") { dir.listFiles()!!.isNotEmpty() }
        beforeStop()
        service.stopAndAwaitFinalization()
        return service
    }

    @Test
    fun `live, an empty file whose close fails is reported once as nothing saved, never as a file to recover`() {
        val log = Log()
        stopBeforeAnyAudio(tempFolder.newFolder(), Fault.CLOSE_FAILS, log)
        assertEquals(listOf("error"), log.events)
        assertTrue("got ${log.error}", log.error is RecordingNotSavedException)
    }

    @Test
    fun `persisted, it is a failure with no file, whose message says nothing was saved`() {
        val outcome = stopBeforeAnyAudio(tempFolder.newFolder(), Fault.CLOSE_FAILS, null).consumePendingOutcome()
        assertTrue("got $outcome", outcome is RecordingOutcome.Failed)
        assertEquals(app().getString(R.string.recording_not_saved_error), (outcome as RecordingOutcome.Failed).cause.message)
    }

    @Test
    fun `live, an empty file that can't be deleted after its close failed is named for recovery`() {
        val dir = tempFolder.newFolder()
        val log = Log()
        stopBeforeAnyAudio(dir, Fault.CLOSE_FAILS, log) { assertTrue(dir.setWritable(false).also { readOnlyDirs += dir }) }
        assertEquals(listOf("finalization failed"), log.events)
        assertTrue(log.target.file()!!.exists())
    }

    @Test
    fun `persisted, an empty file that can't be deleted after its close failed is named for recovery`() {
        val dir = tempFolder.newFolder()
        val outcome = stopBeforeAnyAudio(dir, Fault.CLOSE_FAILS, null) {
            assertTrue(dir.setWritable(false).also { readOnlyDirs += dir })
        }.consumePendingOutcome()
        assertTrue("got $outcome", outcome is RecordingOutcome.FinalizationFailed)
        assertTrue((outcome as RecordingOutcome.FinalizationFailed).target.file()!!.exists())
    }

    @Test
    fun `when the empty last part's close fails, the service reports the earlier part as saved`() {
        // 100 Hz 16-bit mono: a 30-minute part is 360,000 bytes -- 18 chunks, then part 2 opens empty.
        val dir = tempFolder.newFolder()
        val parts = AtomicInteger()
        val source = Source(chunks = 18, chunkBytes = 20_000)
        val service = service(dir, WavRecorder(startup = ImmediateStartup,
            openAudioSource = { WavRecorder.RecorderConfig(source, 100, 20_000) },
            wrapChannel = { FaultyWriter(it, if (parts.incrementAndGet() == 2) Fault.CLOSE_FAILS else Fault.NONE) }
        ).also { recorders += it })
        val log = Log()
        service.listener = log
        service.startRecording(1L, splitMinutes = 30)
        waitFor("part 2 is open") { dir.list()!!.any { it.endsWith("_part02.wav") } }
        service.stopAndAwaitFinalization()

        assertEquals(listOf("stopped"), log.events)
        assertTrue("reported ${log.target}", log.target.file()!!.name.endsWith("_part01.wav"))
        assertEquals(360_000L, dataSizeOf(log.target.file()!!))
        assertTrue(dir.list()!!.none { it.endsWith("_part02.wav") })
        assertNull(journal().peekActive())
    }
}
