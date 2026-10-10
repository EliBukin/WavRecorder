package com.example.wavrecorder

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.After
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
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * A recording whose file may need recovery hands that file over to recovery -- durably, by its own
 * writer, once it has finished with the file for good -- so it never blocks the next recording in
 * the same process; and a write that may have stored more audio than it confirmed keeps the file,
 * and its recovery record, until recovery settles it from what is physically there.
 */
@RunWith(RobolectricTestRunner::class)
class RecoveryHandoffTest {

    @get:Rule
    val isolation = TestIsolation()

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    private val recorders = mutableListOf<WavRecorder>()
    private val gates = mutableListOf<CountDownLatch>()

    @After
    fun tearDown() {
        gates.forEach { it.countDown() }
        recorders.forEach { it.stop() }
        RecordingOwnership.resetForTest()
    }

    private fun gate() = CountDownLatch(1).also { gates += it }

    private fun waitFor(what: String, timeoutMs: Long = 5000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(2)
        }
        shadowOf(Looper.getMainLooper()).idle()
        check(condition()) { "timed out waiting: $what" }
    }

    /** Delivers [chunks] chunks of [chunkBytes] -- chunk n filled with byte n -- then only empty reads, paced like a microphone. */
    private class Source(private val chunks: Int, private val chunkBytes: Int) : AudioSource {
        val reads = AtomicInteger()
        override fun startRecording() {}
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            Thread.sleep(1)
            val n = reads.incrementAndGet()
            if (n > chunks) {
                Thread.sleep(1)
                return 0
            }
            buffer.fill(n.toByte(), offset, offset + chunkBytes)
            return chunkBytes
        }
        override fun stop() {}
        override fun release() {}
    }

    /** How a file's writer treats its audio writes (anything past [headerSize]), and its end. */
    private class Writer(
        private val channel: FileChannel,
        private val headerSize: Int,
        private val onAudioWrite: (n: Int, channel: FileChannel, buffer: ByteBuffer) -> Int? = { _, _, _ -> null },
        private val failPatch: Boolean = false,
        private val closeGate: CountDownLatch? = null,
        private val failClose: Boolean = false
    ) : SegmentWriter {
        private var audioWrites = 0
        override fun write(buffer: ByteBuffer): Int {
            if (channel.position() >= headerSize) {
                onAudioWrite(++audioWrites, channel, buffer)?.let { return it }
            }
            return channel.write(buffer)
        }
        override fun position() = channel.position()
        override fun position(newPosition: Long) {
            if (failPatch && newPosition == 0L && channel.position() > headerSize) throw IOException("the header patch failed")
            channel.position(newPosition)
        }
        override fun close() {
            closeGate?.let(::awaitLikeAStuckNativeCall)
            channel.close()
            if (failClose) throw IOException("close failed")
        }

        /** Waits for [gate] the way a stuck native call does: an interrupt (stop() sends one when
         * it gives up waiting) doesn't end it; the thread is left interrupted afterwards. */
        private fun awaitLikeAStuckNativeCall(gate: CountDownLatch) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            var interrupted = false
            while (true) {
                try {
                    gate.await(deadline - System.nanoTime(), TimeUnit.NANOSECONDS)
                    break
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    /** The write stores the whole buffer, then reports failure without saying so. */
    private fun storesThenFails(at: Int): (Int, FileChannel, ByteBuffer) -> Int? = { n, channel, buffer ->
        if (n == at) {
            channel.write(buffer.duplicate())
            throw IOException("the write stored its bytes, then failed")
        }
        null
    }

    /** The write fails, storing nothing. */
    private fun failsStoringNothing(at: Int): (Int, FileChannel, ByteBuffer) -> Int? = { n, _, _ ->
        if (n == at) throw IOException("the write failed, storing nothing")
        null
    }

    /** A journal whose durable writes fail while [failing] is set -- the edit still applies in
     * memory first, exactly as a real failed commit does (see ActiveSegmentJournal). */
    private fun journal(failing: AtomicBoolean = AtomicBoolean(false)) =
        ActiveSegmentJournal(context(), commitEditor = { editor -> editor.commit() && !failing.get() })

    private fun recorder(
        source: Source,
        format: PcmFormat,
        chunkBytes: Int,
        writerFor: (FileChannel) -> SegmentWriter,
        journal: ActiveSegmentJournal = journal(),
        threadJoinTimeoutMs: Long = 2000,
        targetSize: ((Context, OutputTarget) -> Long?)? = null
    ): WavRecorder {
        val recorder = if (targetSize == null) {
            WavRecorder(startup = ImmediateStartup, threadJoinTimeoutMs = threadJoinTimeoutMs,
                openAudioSource = { WavRecorder.RecorderConfig(source, format, chunkBytes) },
                wrapChannel = writerFor, journalFor = { journal })
        } else {
            WavRecorder(startup = ImmediateStartup, threadJoinTimeoutMs = threadJoinTimeoutMs,
                openAudioSource = { WavRecorder.RecorderConfig(source, format, chunkBytes) },
                wrapChannel = writerFor, journalFor = { journal }, targetSize = targetSize)
        }
        return recorder.also { recorders += it }
    }

    private val mono16 = PcmFormat.pcm16Mono(48000)

    private fun WavRecorder.begin(file: File, errors: MutableList<Exception> = Collections.synchronizedList(mutableListOf())): MutableList<Exception> {
        start(context(), WavRecorder.NextTarget { OutputTarget.FileTarget(file) }, onSegmentStarted = {}, onAmplitude = {}, onError = { errors += it })
        return errors
    }

    /** Records [chunks] chunks of 64 bytes into [file], and stops once all were accepted (or writing failed). */
    private fun record(file: File, chunks: Int = 3, writer: (FileChannel) -> SegmentWriter = { Writer(it, 44) },
                       journal: ActiveSegmentJournal = journal()): Pair<WavRecorder, WavRecorder.FinalizeResult> {
        val source = Source(chunks, 64)
        val recorder = recorder(source, mono16, 64, writer, journal)
        val errors = recorder.begin(file)
        waitFor("every chunk accepted, or writing failed") { source.reads.get() > chunks || errors.isNotEmpty() }
        return recorder to recorder.stop()
    }

    private fun dataSize(file: File): Long = WavRiffParser.parse(file.inputStream())!!.dataSize

    private fun payload(file: File, format: PcmFormat): ByteArray = file.readBytes().copyOfRange(WavHeaderWriter.headerSize(format), file.length().toInt())

    private fun sha(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    private fun OutputTarget.path(): String = (this as OutputTarget.FileTarget).file.absolutePath

    private fun ActiveSegmentJournal.queuedPaths(): List<String> = peekRecoveryCandidates().map { it.target.path() }

    private fun recoverAll(): List<RecoveryOutcome> = WavRecoveryManager().recoverIfNeeded(context())

    // ---- A failed finalization hands its file to recovery, and doesn't block the next recording ----------

    @Test
    fun `after a failed finalization, the next recording in the same process succeeds, and the failed file is recovered`() {
        val dir = tempFolder.newFolder()
        val first = File(dir, "first.wav")
        val second = File(dir, "second.wav")
        val journal = journal()

        val (_, failed) = record(first, writer = { Writer(it, 44, failPatch = true) }, journal = journal)
        assertTrue("got $failed", failed is WavRecorder.FinalizeResult.Failed)
        assertNull("no longer active: it doesn't block anything", journal.peekActive())
        val queued = journal.peekRecoveryCandidates().single()
        assertEquals(first.absolutePath, queued.target.path())
        assertEquals("its format is kept for recovery", mono16, queued.format)

        val (recorder2, ok) = record(second, journal = journal)
        assertEquals(WavRecorder.FinalizeResult.Ok, ok)
        assertEquals(second.absolutePath, recorder2.lastSavedTarget?.path())
        assertEquals(192L, dataSize(second))
        assertEquals("still queued, untouched by the new recording", listOf(first.absolutePath), journal.queuedPaths())

        val secondSha = sha(second)
        val recovered = recoverAll().single()
        assertTrue("got $recovered", recovered is RecoveryOutcome.Recovered)
        assertEquals(first.absolutePath, (recovered as RecoveryOutcome.Recovered).target.path())
        assertEquals(192L, dataSize(first))
        assertEquals("recovering the old file never touches the new one", secondSha, sha(second))
        assertTrue(journal.peekRecoveryCandidates().isEmpty())
    }

    @Test
    fun `repeated failures queue each file once, and settling again changes nothing`() {
        val dir = tempFolder.newFolder()
        val journal = journal()
        val files = (1..3).map { File(dir, "failed$it.wav") }
        files.forEach { file ->
            val (_, result) = record(file, writer = { Writer(it, 44, failPatch = true) }, journal = journal)
            assertTrue(result is WavRecorder.FinalizeResult.Failed)
        }
        val queue = journal.peekRecoveryCandidates()
        assertEquals(files.map { it.absolutePath }, queue.map { it.target.path() })
        assertEquals("each its own token", 3, queue.map { it.token }.toSet().size)

        // A late or repeated settle of any of them -- either way -- adds or removes nothing.
        queue.forEach {
            assertTrue(journal.settleFinishedSegment(it.token, ActiveSegmentJournal.Settle.RECOVER))
            assertTrue(journal.settleFinishedSegment(it.token, ActiveSegmentJournal.Settle.CLEAR))
        }
        assertEquals(queue.map { it.token to it.target.path() }, journal.peekRecoveryCandidates().map { it.token to it.target.path() })

        assertTrue(recoverAll().all { it is RecoveryOutcome.Recovered })
        files.forEach { assertEquals(192L, dataSize(it)) }
    }

    @Test
    fun `a handoff that can't be committed keeps the record active and recoverable, and is retried before the next recording`() {
        val dir = tempFolder.newFolder()
        val first = File(dir, "first.wav")
        val failing = AtomicBoolean(false)
        val journal = journal(failing)

        // Storage for the journal fails from the moment the file is finalized.
        val source = Source(3, 64)
        val recorder1 = recorder(source, mono16, 64, { Writer(it, 44, failPatch = true) }, journal)
        recorder1.begin(first)
        waitFor("every chunk accepted") { source.reads.get() > 3 }
        failing.set(true)
        assertTrue(recorder1.stop() is WavRecorder.FinalizeResult.Failed)
        val record = journal.peekActive()
        assertNotNull("rolled back: still active", record)
        assertEquals(first.absolutePath, record!!.target.path())
        assertTrue(journal.peekRecoveryCandidates().isEmpty())
        assertTrue(journal.isSettlementPendingForTest(record.token))

        // While the journal still can't be written, a new recording fails safely -- the record stays.
        val blocked = File(dir, "blocked.wav")
        val errors = recorder(Source(3, 64), mono16, 64, { Writer(it, 44) }, journal).begin(blocked)
        waitFor("the refused start is reported") { errors.isNotEmpty() }
        assertEquals(record.token, journal.peekActive()?.token)
        assertFalse(blocked.exists())

        // Once it can be written again, the next recording settles the old record first, then records.
        failing.set(false)
        val second = File(dir, "second.wav")
        val (recorder3, ok) = record(second, journal = journal)
        assertEquals(WavRecorder.FinalizeResult.Ok, ok)
        assertEquals(second.absolutePath, recorder3.lastSavedTarget?.path())
        assertEquals(listOf(first.absolutePath), journal.queuedPaths())
        assertFalse(journal.isSettlementPendingForTest(record.token))
        assertTrue(recoverAll().single() is RecoveryOutcome.Recovered)
        assertEquals(192L, dataSize(first))
    }

    @Test
    fun `a handoff that failed is also retried when the service starts again`() {
        val first = File(tempFolder.newFolder(), "first.wav")
        val failing = AtomicBoolean(false)
        val journal = journal(failing)
        val source = Source(3, 64)
        val recorder = recorder(source, mono16, 64, { Writer(it, 44, failPatch = true) }, journal)
        recorder.begin(first)
        waitFor("every chunk accepted") { source.reads.get() > 3 }
        failing.set(true)
        recorder.stop()
        val record = journal.peekActive()!!
        failing.set(false)

        // What RecordingService.onCreate does: its own-process record is settled now, not left.
        val claim = WavRecoveryManager(journalFor = { journal }).claimPreviousSession(context())
        assertTrue("got $claim", claim is ActiveSegmentJournal.ClaimResult.Claimed)
        assertEquals(record.token, (claim as ActiveSegmentJournal.ClaimResult.Claimed).record.token)
        assertNull(journal.peekActive())
        assertEquals(listOf(first.absolutePath), journal.queuedPaths())
    }

    @Test
    fun `a writer still finishing its file keeps its record until it really has - even after stop gave up waiting`() {
        val dir = tempFolder.newFolder()
        val first = File(dir, "first.wav")
        val journal = journal()
        val closing = gate()
        val source = Source(3, 64)
        val recorder1 = recorder(source, mono16, 64, { Writer(it, 44, closeGate = closing, failClose = true) }, journal,
            threadJoinTimeoutMs = 200)
        recorder1.begin(first)
        waitFor("every chunk accepted") { source.reads.get() > 3 }
        assertEquals("stop gives up; the writer is still closing the file",
            WavRecorder.FinalizeResult.Unknown, recorder1.stop())

        val record = journal.peekActive()!!
        assertEquals(first.absolutePath, record.target.path())
        assertFalse("not finished: nothing to settle", journal.isSettlementPendingForTest(record.token))
        // Neither a service start nor a new recording takes it over meanwhile.
        assertEquals(ActiveSegmentJournal.ClaimResult.OwnedByThisProcess, journal.claimActiveAsRecoveryCandidate())
        val errors = recorder(Source(3, 64), mono16, 64, { Writer(it, 44) }, journal).begin(File(dir, "too-early.wav"))
        waitFor("the refused start is reported") { errors.isNotEmpty() }
        assertEquals(record.token, journal.peekActive()?.token)

        // The writer finishes -- its close fails -- and only now hands the file to recovery.
        closing.countDown()
        waitFor("the late finalizer handed its file over") { journal.peekRecoveryCandidates().isNotEmpty() }
        assertNull(journal.peekActive())
        assertEquals(listOf(first.absolutePath), journal.queuedPaths())
        val (_, ok) = record(File(dir, "second.wav"), journal = journal)
        assertEquals(WavRecorder.FinalizeResult.Ok, ok)
    }

    @Test
    fun `a late settle for an older segment never touches a newer segment's record`() {
        val journal = journal()
        val old = OutputTarget.FileTarget(File(tempFolder.root, "old.wav"))
        val new = OutputTarget.FileTarget(File(tempFolder.root, "new.wav"))
        assertTrue(journal.persistActive("old", null, old, mono16))
        assertTrue(journal.settleFinishedSegment("old", ActiveSegmentJournal.Settle.RECOVER))
        assertTrue(journal.persistActive("new", null, new, mono16))

        assertTrue(journal.settleFinishedSegment("old", ActiveSegmentJournal.Settle.RECOVER))
        assertTrue(journal.settleFinishedSegment("old", ActiveSegmentJournal.Settle.CLEAR))

        assertEquals("new", journal.peekActive()?.token)
        assertEquals(listOf("old"), journal.peekRecoveryCandidates().map { it.token })
    }

    @Test
    fun `a clean file whose record can't be cleared doesn't block the next recording, and isn't queued for recovery`() {
        val dir = tempFolder.newFolder()
        val first = File(dir, "first.wav")
        val failing = AtomicBoolean(false)
        val journal = journal(failing)
        val source = Source(3, 64)
        val recorder1 = recorder(source, mono16, 64, { Writer(it, 44) }, journal)
        recorder1.begin(first)
        waitFor("every chunk accepted") { source.reads.get() > 3 }
        failing.set(true)
        assertEquals("the file itself is fine", WavRecorder.FinalizeResult.Ok, recorder1.stop())
        assertEquals(first.absolutePath, recorder1.lastSavedTarget?.path())
        val record = journal.peekActive()!!
        assertTrue(journal.isSettlementPendingForTest(record.token))

        failing.set(false)
        val (_, ok) = record(File(dir, "second.wav"), journal = journal)
        assertEquals(WavRecorder.FinalizeResult.Ok, ok)
        assertNull(journal.peekActive())
        assertTrue("a clean file has nothing to recover", journal.peekRecoveryCandidates().isEmpty())
        assertEquals(192L, dataSize(first))
    }

    // ---- A write that may have stored more than it confirmed ---------------------------------------------

    /** Records 3 chunks of 64 bytes whose audio writes [onAudioWrite] shapes, and stops once writing failed. */
    private fun recordFailing(
        file: File,
        onAudioWrite: (Int, FileChannel, ByteBuffer) -> Int?,
        journal: ActiveSegmentJournal = journal(),
        targetSize: ((Context, OutputTarget) -> Long?)? = null
    ): WavRecorder.FinalizeResult {
        val recorder = recorder(Source(3, 64), mono16, 64, { Writer(it, 44, onAudioWrite) }, journal, targetSize = targetSize)
        val errors = recorder.begin(file)
        waitFor("the write failure is reported") { errors.isNotEmpty() }
        return recorder.stop()
    }

    private fun assertKeptForRecovery(result: WavRecorder.FinalizeResult, file: File, journal: ActiveSegmentJournal, writeFailure: String) {
        assertTrue("got $result", result is WavRecorder.FinalizeResult.Failed)
        val cause = (result as WavRecorder.FinalizeResult.Failed).cause
        assertTrue("a PCM-write uncertainty, not a header or close failure: $cause", cause is UnconfirmedAudioException)
        assertEquals("the original write failure", writeFailure, cause.cause?.message)
        assertNull(journal.peekActive())
        assertEquals(listOf(file.absolutePath), journal.queuedPaths())
    }

    @Test
    fun `a write that stores its bytes after a saved chunk keeps the file for recovery - 128 stored, 64 confirmed`() {
        val file = File(tempFolder.newFolder(), "confirmed-then-uncertain.wav")
        val journal = journal()
        val result = recordFailing(file, storesThenFails(at = 2), journal)

        assertKeptForRecovery(result, file, journal, "the write stored its bytes, then failed")
        assertEquals("the header describes what was confirmed", 64L, dataSize(file))
        assertArrayEquals("nothing truncated or overwritten: both chunks, as captured",
            ByteArray(64) { 1 } + ByteArray(64) { 2 }, payload(file, mono16))

        assertEquals(RecoveryOutcome.Recovered::class, recoverAll().single()::class)
        assertEquals("recovery settles it from what is there", 128L, dataSize(file))
        assertTrue(journal.peekRecoveryCandidates().isEmpty())
    }

    @Test
    fun `a write that stores its bytes before any was confirmed keeps the file for recovery too`() {
        val file = File(tempFolder.newFolder(), "uncertain-from-the-start.wav")
        val journal = journal()
        val result = recordFailing(file, storesThenFails(at = 1), journal)

        assertKeptForRecovery(result, file, journal, "the write stored its bytes, then failed")
        assertEquals(0L, dataSize(file))
        assertArrayEquals(ByteArray(64) { 1 }, payload(file, mono16))
        recoverAll()
        assertEquals(64L, dataSize(file))
    }

    @Test
    fun `a short confirmed write followed by an unconfirmed one keeps the file for recovery`() {
        val file = File(tempFolder.newFolder(), "short-then-uncertain.wav")
        val journal = journal()
        // The first chunk's first call stores and confirms 32 bytes; the call for the rest stores
        // them too, but fails.
        val result = recordFailing(file, { n, channel, buffer ->
            when (n) {
                1 -> {
                    val part = buffer.duplicate().apply { limit(position() + 32) }
                    val written = channel.write(part)
                    buffer.position(buffer.position() + written)
                    written
                }
                2 -> {
                    channel.write(buffer.duplicate())
                    throw IOException("the rest was stored, then the write failed")
                }
                else -> null
            }
        }, journal)

        assertKeptForRecovery(result, file, journal, "the rest was stored, then the write failed")
        assertEquals(32L, dataSize(file))
        assertArrayEquals(ByteArray(64) { 1 }, payload(file, mono16))
        recoverAll()
        assertEquals(64L, dataSize(file))
    }

    @Test
    fun `a write failure that stored nothing more is a partial save, finalized, with nothing left to recover`() {
        val file = File(tempFolder.newFolder(), "failed-storing-nothing.wav")
        val journal = journal()
        val result = recordFailing(file, failsStoringNothing(at = 2), journal)

        assertTrue("got $result", result is WavRecorder.FinalizeResult.Incomplete)
        assertEquals(64L, dataSize(file))
        assertArrayEquals(ByteArray(64) { 1 }, payload(file, mono16))
        assertNull(journal.peekActive())
        assertTrue(journal.peekRecoveryCandidates().isEmpty())
    }

    @Test
    fun `when the file's size can't be read, a failed write is treated as possibly having stored more`() {
        for (unknown in listOf<(Context, OutputTarget) -> Long?>({ _, _ -> null }, { _, _ -> throw IOException("the provider failed") })) {
            val file = File(tempFolder.newFolder(), "unknown-size.wav")
            val journal = journal()
            val result = recordFailing(file, failsStoringNothing(at = 2), journal, targetSize = unknown)

            assertKeptForRecovery(result, file, journal, "the write failed, storing nothing")
            assertEquals(64L, dataSize(file))
            recoverAll()
            assertEquals("recovery finds nothing more, and loses nothing", 64L, dataSize(file))
            assertArrayEquals(ByteArray(64) { 1 }, payload(file, mono16))
        }
    }

    // ---- Frame widths, header sizes and RIFF padding --------------------------------------------------------

    /** Records into [file] in [format] with chunks of [chunkBytes]; the second audio write does [second]. */
    private fun recordFormat(file: File, format: PcmFormat, chunkBytes: Int, journal: ActiveSegmentJournal,
                             second: (FileChannel, ByteBuffer) -> Int): WavRecorder.FinalizeResult {
        val headerSize = WavHeaderWriter.headerSize(format)
        val recorder = recorder(Source(3, chunkBytes), format, chunkBytes, {
            Writer(it, headerSize, { n, channel, buffer -> if (n == 2) second(channel, buffer) else null })
        }, journal)
        val errors = recorder.begin(file)
        waitFor("the write failure is reported") { errors.isNotEmpty() }
        return recorder.stop()
    }

    @Test
    fun `24-bit mono - an odd confirmed length followed by unconfirmed audio gets no pad byte over that audio`() {
        val format = PcmFormat.of(48000, PcmEncoding.PCM_24_PACKED, 1) // 3-byte frames
        val file = File(tempFolder.newFolder(), "odd-24bit.wav")
        val journal = journal()
        val result = recordFormat(file, format, 9, journal) { channel, buffer ->
            channel.write(buffer.duplicate())
            throw IOException("stored, then failed")
        }

        assertKeptForRecovery(result, file, journal, "stored, then failed")
        assertEquals(9L, WavRiffParser.parse(file.inputStream())!!.dataSize)
        assertArrayEquals("the byte after the odd-length audio is still audio, not a pad byte",
            ByteArray(9) { 1 } + ByteArray(9) { 2 }, payload(file, format))
        recoverAll()
        assertEquals(18L, WavRiffParser.parse(file.inputStream())!!.dataSize)
        assertArrayEquals(ByteArray(9) { 1 } + ByteArray(9) { 2 }, payload(file, format))
    }

    @Test
    fun `24-bit mono - less than a frame stored past an odd confirmed length is not audio - padded and finalized`() {
        val format = PcmFormat.of(48000, PcmEncoding.PCM_24_PACKED, 1)
        val file = File(tempFolder.newFolder(), "odd-24bit-partial-frame.wav")
        val journal = journal()
        val result = recordFormat(file, format, 9, journal) { channel, buffer ->
            val stray = buffer.duplicate().apply { limit(position() + 2) } // two bytes of a 3-byte frame
            channel.write(stray)
            throw IOException("two bytes stored, then failed")
        }

        assertTrue("got $result", result is WavRecorder.FinalizeResult.Incomplete)
        val parsed = WavRiffParser.parse(file.inputStream())!!
        assertEquals(9L, parsed.dataSize)
        val bytes = file.readBytes()
        val headerSize = WavHeaderWriter.headerSize(format)
        assertEquals("the RIFF pad byte, over an incomplete frame", 0.toByte(), bytes[headerSize + 9])
        assertNull(journal.peekActive())
        assertTrue(journal.peekRecoveryCandidates().isEmpty())
    }

    @Test
    fun `24-bit stereo, with its larger header - a whole unconfirmed frame is kept, less than one is not`() {
        val format = PcmFormat.of(48000, PcmEncoding.PCM_24_PACKED, 2) // 6-byte frames, extensible header
        assertNotEquals44(WavHeaderWriter.headerSize(format))

        val kept = File(tempFolder.newFolder(), "stereo-kept.wav")
        val keptJournal = journal()
        val keptResult = recordFormat(kept, format, 12, keptJournal) { channel, buffer ->
            channel.write(buffer.duplicate().apply { limit(position() + 6) }) // exactly one frame
            throw IOException("a frame stored, then failed")
        }
        assertKeptForRecovery(keptResult, kept, keptJournal, "a frame stored, then failed")
        recoverAll()
        assertEquals(18L, WavRiffParser.parse(kept.inputStream())!!.dataSize)

        val finalized = File(tempFolder.newFolder(), "stereo-finalized.wav")
        val finalizedJournal = journal()
        val finalizedResult = recordFormat(finalized, format, 12, finalizedJournal) { channel, buffer ->
            channel.write(buffer.duplicate().apply { limit(position() + 5) }) // one byte short of a frame
            throw IOException("part of a frame stored, then failed")
        }
        assertTrue("got $finalizedResult", finalizedResult is WavRecorder.FinalizeResult.Incomplete)
        assertEquals(12L, WavRiffParser.parse(finalized.inputStream())!!.dataSize)
        assertNull(finalizedJournal.peekActive())
        assertTrue(finalizedJournal.peekRecoveryCandidates().isEmpty())
    }

    private fun assertNotEquals44(headerSize: Int) = assertTrue("expected a header larger than 44 bytes, got $headerSize", headerSize > 44)

    // ---- Both together ---------------------------------------------------------------------------------------

    @Test
    fun `an uncertain write, then a healthy recording in the same process, then recovery - each file keeps to itself`() {
        val dir = tempFolder.newFolder()
        val first = File(dir, "uncertain.wav")
        val second = File(dir, "healthy.wav")
        val journal = journal()

        val result = recordFailing(first, storesThenFails(at = 2), journal)
        assertKeptForRecovery(result, first, journal, "the write stored its bytes, then failed")
        val firstToken = journal.peekRecoveryCandidates().single().token

        // The next recording starts at once, in the same process; recovery runs while it records.
        val source = Source(3, 64)
        val recorder2 = recorder(source, mono16, 64, { Writer(it, 44) }, journal)
        recorder2.begin(second)
        waitFor("every chunk accepted") { source.reads.get() > 3 }
        val secondToken = journal.peekActive()!!.token
        assertTrue(firstToken != secondToken)
        assertEquals(second.absolutePath, journal.peekActive()!!.target.path())

        val recovered = recoverAll().single()
        assertTrue("got $recovered", recovered is RecoveryOutcome.Recovered)
        assertEquals(first.absolutePath, (recovered as RecoveryOutcome.Recovered).target.path())
        assertEquals(128L, dataSize(first))
        assertEquals("recovery never touched the recording in progress", secondToken, journal.peekActive()?.token)

        assertEquals(WavRecorder.FinalizeResult.Ok, recorder2.stop())
        assertEquals(192L, dataSize(second))
        assertArrayEquals(ByteArray(64) { 1 } + ByteArray(64) { 2 } + ByteArray(64) { 3 }, payload(second, mono16))
        val secondSha = sha(second)
        assertEquals("nothing left for recovery to do", listOf(RecoveryOutcome.NothingToRecover), recoverAll())
        assertEquals(secondSha, sha(second))
        assertNull(journal.peekActive())
    }
}
