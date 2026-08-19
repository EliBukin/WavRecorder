package com.example.wavrecorder

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * Covers [WavRecoveryManager]'s process-restart recovery pass: repairing every WAV segment (if
 * any) abrupt previous-process deaths left with a stale header, using only the durable
 * RECOVERY_PENDING queue in [ActiveSegmentJournal] -- never by scanning or guessing at arbitrary
 * files on disk, and never the live ACTIVE record -- and reporting truthfully how far a failed
 * attempt actually got (untouched / maybe-partially-written / written-but-unverified /
 * verified-but-not-cleared / fully recovered). Also covers the real SAF opening path (a real
 * Robolectric-registered ContentProvider, not just the injected fake seam) since a prior version
 * of this code only ever exercised local files or a fake in tests, which is exactly how a
 * write-only-channel bug in the real SAF path went unnoticed.
 */
@RunWith(RobolectricTestRunner::class)
class WavRecoveryManagerTest {

    @get:Rule
    val tempFolder = org.junit.rules.TemporaryFolder()

    private fun context() = ApplicationProvider.getApplicationContext<Context>()
    private val foreignOwner = "simulated-foreign-process"

    /** Writes a segment file exactly the way WavRecorder leaves one mid-write: a header declaring
     * [declaredDataSize] bytes of audio (0, if it crashed before ever being flushed), followed by
     * [actualAudioBytes] bytes of real PCM already on disk. */
    private fun writeInterruptedSegment(name: String, actualAudioBytes: Int, declaredDataSize: Int = 0): File {
        val file = tempFolder.newFile(name)
        RandomAccessFile(file, "rw").use { raf ->
            val header = WavHeaderWriter.build(
                sampleRate = 48000, channels = 1, bitsPerSample = 16, audioDataLen = declaredDataSize.toLong()
            )
            val headerBytes = ByteArray(header.remaining())
            header.get(headerBytes)
            raf.write(headerBytes)
            raf.write(ByteArray(actualAudioBytes) { (it % 128).toByte() })
        }
        return file
    }

    /** Sets up a recovery-pending queue entry exactly the way production does: persist an ACTIVE
     * record owned by a genuinely different (foreign) process, then claim it -- rather than
     * writing the queue directly, which nothing in production ever does. */
    private fun seedRecoveryCandidate(
        journal: ActiveSegmentJournal, token: String, target: OutputTarget,
        sampleRate: Int = 48000, channels: Int = 1, bitsPerSample: Int = 16
    ) {
        journal.persistActiveWithOwnerForTest(token, foreignOwner, target, sampleRate, channels, bitsPerSample)
        journal.claimActiveAsRecoveryCandidate()
    }

    private fun singleOutcome(manager: WavRecoveryManager): RecoveryOutcome =
        manager.recoverIfNeeded(context()).single()

    /** Minimal [RecoverableChannel] fake with every method throwing "unreached" by default;
     * individual tests override just the operation(s) they want to exercise. */
    private open class StubRecoverableChannel(private val length: Long) : RecoverableChannel {
        var closeCalls = 0
        override fun size(): Long = length
        override fun position(newPosition: Long) {}
        override fun write(bytes: ByteArray) {}
        override fun force() {}
        override fun close() { closeCalls++ }
    }

    @Test
    fun `NothingToRecover when the recovery queue is empty`() {
        val journal = ActiveSegmentJournal(context())
        val manager = WavRecoveryManager(journalFor = { journal })

        val outcome = manager.recoverIfNeeded(context())

        assertEquals(listOf(RecoveryOutcome.NothingToRecover), outcome)
    }

    @Test
    fun `a live active segment persisted after claiming is never opened, patched, or cleared by recovery`() {
        val liveFile = writeInterruptedSegment("live.wav", actualAudioBytes = 4, declaredDataSize = 0)
        val liveBytesBefore = liveFile.readBytes()
        val journal = ActiveSegmentJournal(context())
        journal.claimActiveAsRecoveryCandidate() // nothing to claim -- clean previous exit
        journal.persistActive("live-token", null, OutputTarget.FileTarget(liveFile), sampleRate = 48000, channels = 1, bitsPerSample = 16)
        val manager = WavRecoveryManager(journalFor = { journal })

        val outcome = singleOutcome(manager)

        assertEquals(RecoveryOutcome.NothingToRecover, outcome)
        assertArrayEquals("the live segment's file must be byte-for-byte untouched",
            liveBytesBefore, liveFile.readBytes())
        assertEquals("the live segment's own ACTIVE record must be untouched", "live-token",
            journal.peekActive()?.token)
    }

    @Test
    fun `recovery repairs only the claimed old file while a newer active record is preserved throughout`() {
        val oldFile = writeInterruptedSegment("old.wav", actualAudioBytes = 100, declaredDataSize = 0)
        val newFile = writeInterruptedSegment("new-live.wav", actualAudioBytes = 4, declaredDataSize = 0)
        val journal = ActiveSegmentJournal(context())
        seedRecoveryCandidate(journal, "old-token", OutputTarget.FileTarget(oldFile))
        journal.persistActive("new-token", null, OutputTarget.FileTarget(newFile), sampleRate = 48000, channels = 1, bitsPerSample = 16)
        val manager = WavRecoveryManager(journalFor = { journal })

        val outcome = singleOutcome(manager)

        assertTrue("expected the old file to be successfully recovered, got: $outcome",
            outcome is RecoveryOutcome.Recovered)
        val oldFormat = WavRiffParser.parse(oldFile.inputStream())
        assertEquals(100L, oldFormat?.dataSize)
        assertTrue("the recovery-pending queue must be empty once recovered", journal.peekRecoveryCandidates().isEmpty())

        // The new, still-live active record must have been completely unaffected the entire time.
        assertEquals("new-token", journal.peekActive()?.token)
        val newFormat = WavRiffParser.parse(newFile.inputStream())
        assertEquals("the live file's own header must be untouched by recovering a different file",
            0L, newFormat?.dataSize)
    }

    @Test
    fun `recovers an interrupted app-owned WAV whose PCM extends beyond the last declared data size`() {
        val file = writeInterruptedSegment("interrupted.wav", actualAudioBytes = 100, declaredDataSize = 0)
        val journal = ActiveSegmentJournal(context())
        seedRecoveryCandidate(journal, "token", OutputTarget.FileTarget(file))
        val manager = WavRecoveryManager(journalFor = { journal })

        val outcome = singleOutcome(manager)

        assertTrue("expected a successful recovery, got: $outcome", outcome is RecoveryOutcome.Recovered)
        assertEquals(100L, (outcome as RecoveryOutcome.Recovered).recoveredAudioBytes)

        val format = WavRiffParser.parse(file.inputStream())
        assertEquals(100L, format?.dataSize)
        assertEquals(48000, format?.sampleRate)
        assertTrue("a successful, verified recovery must clear the recovery-pending queue entry",
            journal.peekRecoveryCandidates().isEmpty())
    }

    @Test
    fun `aligns an odd trailing PCM frame down to a complete frame without truncating or overwriting the file`() {
        val file = writeInterruptedSegment("odd-trailing.wav", actualAudioBytes = 101, declaredDataSize = 0)
        val originalFileLength = file.length()
        val journal = ActiveSegmentJournal(context())
        seedRecoveryCandidate(journal, "token", OutputTarget.FileTarget(file))
        val manager = WavRecoveryManager(journalFor = { journal })

        val outcome = singleOutcome(manager)

        assertTrue(outcome is RecoveryOutcome.Recovered)
        assertEquals(100L, (outcome as RecoveryOutcome.Recovered).recoveredAudioBytes)
        val format = WavRiffParser.parse(file.inputStream())
        assertEquals("the declared data size must be aligned down to a whole number of frames",
            100L, format?.dataSize)
        assertEquals("recovery must never truncate the file itself, only the header's declared size",
            originalFileLength, file.length())
    }

    @Test
    fun `a segment with no audio beyond its already-valid zero-length header recovers as zero bytes`() {
        val file = writeInterruptedSegment("empty.wav", actualAudioBytes = 0, declaredDataSize = 0)
        val journal = ActiveSegmentJournal(context())
        seedRecoveryCandidate(journal, "token", OutputTarget.FileTarget(file))
        val manager = WavRecoveryManager(journalFor = { journal })

        val outcome = singleOutcome(manager)

        assertTrue(outcome is RecoveryOutcome.Recovered)
        assertEquals(0L, (outcome as RecoveryOutcome.Recovered).recoveredAudioBytes)
        assertTrue(journal.peekRecoveryCandidates().isEmpty())
    }

    @Test
    fun `recovery failure preserves the original file untouched and keeps the journal record for a later retry`() {
        val file = writeInterruptedSegment("unseekable.wav", actualAudioBytes = 50, declaredDataSize = 0)
        val originalBytes = file.readBytes()
        val journal = ActiveSegmentJournal(context())
        seedRecoveryCandidate(journal, "token", OutputTarget.FileTarget(file))
        // Standing in for a non-seekable content-provider stream (e.g. a cloud-backed SAF
        // document proxied through a pipe): opens "successfully" but every seek fails, before any
        // write is ever attempted.
        val unseekableChannel = object : StubRecoverableChannel(length = file.length()) {
            override fun position(newPosition: Long) { throw IOException("simulated: stream is not seekable") }
            override fun write(bytes: ByteArray) { throw AssertionError("must never be reached -- seek already failed") }
        }
        val manager = WavRecoveryManager(
            journalFor = { journal },
            openWritable = { _, _ -> unseekableChannel },
            openReadable = { _, _ -> throw AssertionError("must never attempt to verify when the write itself failed") }
        )

        val outcome = singleOutcome(manager)

        assertTrue("expected an honest failure outcome, got: $outcome", outcome is RecoveryOutcome.Failed)
        val failed = outcome as RecoveryOutcome.Failed
        assertEquals("no write() call was ever issued, so this must report UNTOUCHED",
            RecoveryOutcome.Mutation.UNTOUCHED, failed.mutation)
        assertArrayEquals("a failed recovery must never modify the original file at all",
            originalBytes, file.readBytes())
        assertEquals("a failed/unverified recovery must keep the queue entry so a later attempt " +
            "can retry -- it must never be silently dropped", "token", journal.peekRecoveryCandidates().single().token)
        assertEquals("the writable channel must still be closed even though every operation on " +
            "it failed", 1, unseekableChannel.closeCalls)
    }

    @Test
    fun `a header write that throws after starting produces a MAYBE_PARTIAL failure, never claiming the file is untouched`() {
        val file = writeInterruptedSegment("write-throws.wav", actualAudioBytes = 50, declaredDataSize = 0)
        val journal = ActiveSegmentJournal(context())
        seedRecoveryCandidate(journal, "token", OutputTarget.FileTarget(file))
        val throwingChannel = object : StubRecoverableChannel(length = file.length()) {
            override fun write(bytes: ByteArray) { throw IOException("simulated: write failed partway") }
        }
        val manager = WavRecoveryManager(
            journalFor = { journal },
            openWritable = { _, _ -> throwingChannel },
            openReadable = { _, _ -> throw AssertionError("must never attempt to verify when the write itself failed") }
        )

        val outcome = singleOutcome(manager)

        assertTrue(outcome is RecoveryOutcome.Failed)
        assertEquals("a write that was attempted and threw may have partially landed -- never " +
            "reported as untouched", RecoveryOutcome.Mutation.MAYBE_PARTIAL, (outcome as RecoveryOutcome.Failed).mutation)
        assertEquals(1, throwingChannel.closeCalls)
    }

    @Test
    fun `a force() failure after a successful write produces a MAYBE_PARTIAL failure and never attempts verification`() {
        val file = writeInterruptedSegment("force-fails.wav", actualAudioBytes = 50, declaredDataSize = 0)
        val journal = ActiveSegmentJournal(context())
        seedRecoveryCandidate(journal, "token", OutputTarget.FileTarget(file))
        val forceFailingChannel = object : StubRecoverableChannel(length = file.length()) {
            override fun force() { throw IOException("simulated: force() failed") }
        }
        val manager = WavRecoveryManager(
            journalFor = { journal },
            openWritable = { _, _ -> forceFailingChannel },
            openReadable = { _, _ -> throw AssertionError("must never attempt to verify when force() already failed") }
        )

        val outcome = singleOutcome(manager)

        assertTrue(outcome is RecoveryOutcome.Failed)
        assertEquals("force() failing means there's no confirmation the write reached storage -- " +
            "must not be treated as a completed, merely-unverified write",
            RecoveryOutcome.Mutation.MAYBE_PARTIAL, (outcome as RecoveryOutcome.Failed).mutation)
        assertEquals(1, forceFailingChannel.closeCalls)
        assertEquals("token", journal.peekRecoveryCandidates().single().token)
    }

    @Test
    fun `a writable close failure after a successful patch is reported honestly, distinct from an unattempted write`() {
        val file = writeInterruptedSegment("close-fails.wav", actualAudioBytes = 50, declaredDataSize = 0)
        val journal = ActiveSegmentJournal(context())
        seedRecoveryCandidate(journal, "token", OutputTarget.FileTarget(file))
        val closeFailingChannel = object : StubRecoverableChannel(length = file.length()) {
            override fun close() { throw IOException("simulated: close() failed") }
        }
        val manager = WavRecoveryManager(
            journalFor = { journal },
            openWritable = { _, _ -> closeFailingChannel },
            openReadable = { _, _ -> throw AssertionError("must never attempt to verify when the writable close already failed") }
        )

        val outcome = singleOutcome(manager)

        assertTrue(outcome is RecoveryOutcome.Failed)
        val failed = outcome as RecoveryOutcome.Failed
        assertTrue("expected the failure reason to mention the close failure", failed.reason.contains("close", ignoreCase = true))
        assertEquals("force() already succeeded before close() failed, so the write itself is " +
            "confirmed durable -- this is a bookkeeping failure, not doubt about the bytes",
            RecoveryOutcome.Mutation.WRITTEN_UNVERIFIED, failed.mutation)
        assertEquals("token", journal.peekRecoveryCandidates().single().token)
    }

    @Test
    fun `verification failure after a successful header write reports WRITTEN_UNVERIFIED, never untouched, and keeps the journal record`() {
        val file = writeInterruptedSegment("verify-fails.wav", actualAudioBytes = 100, declaredDataSize = 0)
        val journal = ActiveSegmentJournal(context())
        seedRecoveryCandidate(journal, "token", OutputTarget.FileTarget(file))
        var writableClosed = false
        var readableClosed = false
        val manager = WavRecoveryManager(
            journalFor = { journal },
            // The real local-file writable channel, so the header patch genuinely happens on disk.
            openWritable = { _, target ->
                val raf = RandomAccessFile((target as OutputTarget.FileTarget).file, "rw")
                object : RecoverableChannel {
                    override fun size(): Long = raf.length()
                    override fun position(newPosition: Long) { raf.seek(newPosition) }
                    override fun write(bytes: ByteArray) { raf.write(bytes) }
                    override fun force() {}
                    override fun close() { writableClosed = true; raf.close() }
                }
            },
            // Simulates a permission revoked (or a provider that simply can't be trusted) between
            // the write and the verification reopen.
            openReadable = { _, _ ->
                object : ReadableRecoveryChannel {
                    override fun position(newPosition: Long) {}
                    override fun readFully(length: Int): ByteArray = throw IOException("simulated: revoked mid-recovery")
                    override fun close() { readableClosed = true }
                }
            }
        )

        val outcome = singleOutcome(manager)

        assertTrue("expected a failure outcome, got: $outcome", outcome is RecoveryOutcome.Failed)
        val failed = outcome as RecoveryOutcome.Failed
        assertEquals(
            "a header write genuinely happened and was forced to storage before verification " +
                "failed -- this must never be reported as if the file were left untouched",
            RecoveryOutcome.Mutation.WRITTEN_UNVERIFIED, failed.mutation
        )
        // The header really was patched on disk, even though verification (via the separately
        // opened, and separately failing, read channel) could not confirm it.
        val actualHeaderBytes = ByteArray(WavHeaderWriter.HEADER_SIZE)
        file.inputStream().use { it.read(actualHeaderBytes) }
        val parsed = WavRiffParser.parse(actualHeaderBytes.inputStream())
        assertEquals(100L, parsed?.dataSize)
        assertEquals("an unverified recovery must keep the journal record for a later retry",
            "token", journal.peekRecoveryCandidates().single().token)
        assertTrue(writableClosed)
        assertTrue(readableClosed)
    }

    @Test
    fun `a readable-channel close failure after successful verification is never reported as Recovered, and the candidate stays queued`() {
        // header writing + force() succeed; verification itself reads and parses the *correct*
        // header (a real RandomAccessFile-backed channel, so this genuinely succeeds); only the
        // readable channel's own close() throws.
        val file = writeInterruptedSegment("readable-close-fails.wav", actualAudioBytes = 100, declaredDataSize = 0)
        val journal = ActiveSegmentJournal(context())
        seedRecoveryCandidate(journal, "token", OutputTarget.FileTarget(file))
        var readableCloseCalls = 0
        val manager = WavRecoveryManager(
            journalFor = { journal },
            openWritable = { _, target ->
                val raf = RandomAccessFile((target as OutputTarget.FileTarget).file, "rw")
                object : RecoverableChannel {
                    override fun size(): Long = raf.length()
                    override fun position(newPosition: Long) { raf.seek(newPosition) }
                    override fun write(bytes: ByteArray) { raf.write(bytes) }
                    override fun force() {}
                    override fun close() = raf.close()
                }
            },
            openReadable = { _, target ->
                val raf = RandomAccessFile((target as OutputTarget.FileTarget).file, "r")
                object : ReadableRecoveryChannel {
                    override fun position(newPosition: Long) { raf.seek(newPosition) }
                    override fun readFully(length: Int): ByteArray {
                        val bytes = ByteArray(length)
                        raf.readFully(bytes)
                        return bytes
                    }
                    override fun close() {
                        readableCloseCalls++
                        raf.close()
                        throw IOException("simulated: verification channel close failed")
                    }
                }
            }
        )

        val outcome = singleOutcome(manager)

        assertTrue("a readable-channel close failure must never be silently converted into " +
            "Recovered, got: $outcome", outcome is RecoveryOutcome.Failed)
        val failed = outcome as RecoveryOutcome.Failed
        assertTrue("the failure reason must mention the close failure",
            failed.reason.contains("close", ignoreCase = true))
        assertEquals("verification itself genuinely succeeded (the header was read back and " +
            "confirmed correct) before only the close step failed -- WRITTEN_UNVERIFIED would " +
            "falsely claim verification never confirmed the file; this must be WRITTEN_VERIFIED",
            RecoveryOutcome.Mutation.WRITTEN_VERIFIED, failed.mutation)
        assertEquals("the recovery queue entry must not be removed when the required close step " +
            "failed, even though verification itself succeeded",
            "token", journal.peekRecoveryCandidates().single().token)
        assertEquals("the readable channel must be closed exactly once", 1, readableCloseCalls)
    }

    @Test
    fun `a verification failure that also fails to close the channel stays WRITTEN_UNVERIFIED, never WRITTEN_VERIFIED`() {
        // Distinguishes the two failure shapes: here the readback itself never confirms the
        // header (throws), so even though close() also fails afterward, this must not be
        // upgraded to WRITTEN_VERIFIED -- that value is reserved for a verification that actually
        // succeeded before only cleanup failed.
        val file = writeInterruptedSegment("verify-and-close-fail.wav", actualAudioBytes = 100, declaredDataSize = 0)
        val journal = ActiveSegmentJournal(context())
        seedRecoveryCandidate(journal, "token", OutputTarget.FileTarget(file))
        var readableCloseCalls = 0
        val manager = WavRecoveryManager(
            journalFor = { journal },
            openWritable = { _, target ->
                val raf = RandomAccessFile((target as OutputTarget.FileTarget).file, "rw")
                object : RecoverableChannel {
                    override fun size(): Long = raf.length()
                    override fun position(newPosition: Long) { raf.seek(newPosition) }
                    override fun write(bytes: ByteArray) { raf.write(bytes) }
                    override fun force() {}
                    override fun close() = raf.close()
                }
            },
            openReadable = { _, _ ->
                object : ReadableRecoveryChannel {
                    override fun position(newPosition: Long) {}
                    override fun readFully(length: Int): ByteArray = throw IOException("simulated: revoked mid-recovery")
                    override fun close() {
                        readableCloseCalls++
                        throw IOException("simulated: verification channel close also failed")
                    }
                }
            }
        )

        val outcome = singleOutcome(manager)

        assertTrue(outcome is RecoveryOutcome.Failed)
        val failed = outcome as RecoveryOutcome.Failed
        assertEquals("verification itself never succeeded here, so this must stay " +
            "WRITTEN_UNVERIFIED regardless of the close failure on top",
            RecoveryOutcome.Mutation.WRITTEN_UNVERIFIED, failed.mutation)
        assertEquals("token", journal.peekRecoveryCandidates().single().token)
        assertEquals(1, readableCloseCalls)
    }

    @Test
    fun `readback succeeds but queue removal returning false reports VerifiedButNotCleared, not Recovered`() {
        val file = writeInterruptedSegment("removal-fails.wav", actualAudioBytes = 100, declaredDataSize = 0)
        val realJournal = ActiveSegmentJournal(context())
        seedRecoveryCandidate(realJournal, "token", OutputTarget.FileTarget(file))
        val journalWithFailingRemoval = object : ActiveSegmentJournal(context()) {
            override fun peekRecoveryCandidates(): List<Record> = realJournal.peekRecoveryCandidates()
            override fun removeRecoveryCandidateIfMatches(token: String): Boolean = false
        }
        val manager = WavRecoveryManager(journalFor = { journalWithFailingRemoval })

        val outcome = singleOutcome(manager)

        assertTrue("expected VerifiedButNotCleared, got: $outcome", outcome is RecoveryOutcome.VerifiedButNotCleared)
        assertEquals(100L, (outcome as RecoveryOutcome.VerifiedButNotCleared).recoveredAudioBytes)
        val format = WavRiffParser.parse(file.inputStream())
        assertEquals("the file itself must still be genuinely, correctly patched even though the " +
            "queue bookkeeping failed", 100L, format?.dataSize)
    }

    @Test
    fun `queue removal throwing also reports VerifiedButNotCleared, never crashes the recovery pass`() {
        val file = writeInterruptedSegment("removal-throws.wav", actualAudioBytes = 100, declaredDataSize = 0)
        val realJournal = ActiveSegmentJournal(context())
        seedRecoveryCandidate(realJournal, "token", OutputTarget.FileTarget(file))
        val journalWithThrowingRemoval = object : ActiveSegmentJournal(context()) {
            override fun peekRecoveryCandidates(): List<Record> = realJournal.peekRecoveryCandidates()
            override fun removeRecoveryCandidateIfMatches(token: String): Boolean =
                throw RuntimeException("simulated: durable removal blew up")
        }
        val manager = WavRecoveryManager(journalFor = { journalWithThrowingRemoval })

        val outcome = singleOutcome(manager)

        assertTrue("expected VerifiedButNotCleared, got: $outcome", outcome is RecoveryOutcome.VerifiedButNotCleared)
    }

    @Test
    fun `only a complete patch, force, verification, and successful durable removal produces Recovered`() {
        val file = writeInterruptedSegment("fully-clean.wav", actualAudioBytes = 100, declaredDataSize = 0)
        val journal = ActiveSegmentJournal(context())
        seedRecoveryCandidate(journal, "token", OutputTarget.FileTarget(file))
        val manager = WavRecoveryManager(journalFor = { journal }) // real production channels throughout

        val outcome = singleOutcome(manager)

        assertTrue(outcome is RecoveryOutcome.Recovered)
        assertTrue(journal.peekRecoveryCandidates().isEmpty())
    }

    @Test
    fun `a target that can no longer be opened is reported as a failure, never silently dropped`() {
        val missingFile = File(tempFolder.root, "never-actually-created.wav")
        val journal = ActiveSegmentJournal(context())
        seedRecoveryCandidate(journal, "token", OutputTarget.FileTarget(missingFile))
        val manager = WavRecoveryManager(journalFor = { journal })

        val outcome = singleOutcome(manager)

        assertTrue(outcome is RecoveryOutcome.Failed)
        assertEquals("token", journal.peekRecoveryCandidates().single().token)
    }

    @Test
    fun `an unrelated imported WAV file with trailing bytes is never modified when there is no journal record for it`() {
        val unrelatedFile = writeInterruptedSegment("imported.wav", actualAudioBytes = 37, declaredDataSize = 0)
        val originalBytes = unrelatedFile.readBytes()
        val journal = ActiveSegmentJournal(context()) // deliberately never persisted/claimed
        val manager = WavRecoveryManager(journalFor = { journal })

        val outcome = manager.recoverIfNeeded(context())

        assertEquals(listOf(RecoveryOutcome.NothingToRecover), outcome)
        assertArrayEquals("an unrelated file must never be touched just because none was named " +
            "by the journal", originalBytes, unrelatedFile.readBytes())
    }

    // ---- Area 2: independent multi-candidate recovery ----

    @Test
    fun `candidate 1 recovery fails while candidate 2 recovers independently -- only candidate 2 is removed from the queue`() {
        val failingFile = writeInterruptedSegment("fails.wav", actualAudioBytes = 20, declaredDataSize = 0)
        val succeedingFile = writeInterruptedSegment("succeeds.wav", actualAudioBytes = 20, declaredDataSize = 0)
        val journal = ActiveSegmentJournal(context())
        seedRecoveryCandidate(journal, "fails-token", OutputTarget.FileTarget(failingFile))
        seedRecoveryCandidate(journal, "succeeds-token", OutputTarget.FileTarget(succeedingFile))
        val manager = WavRecoveryManager(
            journalFor = { journal },
            openWritable = { _, target ->
                val file = (target as OutputTarget.FileTarget).file
                if (file.name == "fails.wav") null // simulates "could not be opened"
                else {
                    val raf = RandomAccessFile(file, "rw")
                    object : RecoverableChannel {
                        override fun size(): Long = raf.length()
                        override fun position(newPosition: Long) { raf.seek(newPosition) }
                        override fun write(bytes: ByteArray) { raf.write(bytes) }
                        override fun force() {}
                        override fun close() { raf.close() }
                    }
                }
            },
            openReadable = { _, target ->
                val raf = RandomAccessFile((target as OutputTarget.FileTarget).file, "r")
                object : ReadableRecoveryChannel {
                    override fun position(newPosition: Long) { raf.seek(newPosition) }
                    override fun readFully(length: Int): ByteArray {
                        val bytes = ByteArray(length); raf.readFully(bytes); return bytes
                    }
                    override fun close() { raf.close() }
                }
            }
        )

        val outcomes = manager.recoverIfNeeded(context())

        assertEquals(2, outcomes.size)
        assertTrue("expected candidate 1 (fails.wav) to fail", outcomes[0] is RecoveryOutcome.Failed)
        assertTrue("expected candidate 2 (succeeds.wav) to recover", outcomes[1] is RecoveryOutcome.Recovered)
        assertEquals("only the successfully-recovered candidate must be removed from the queue",
            listOf("fails-token"), journal.peekRecoveryCandidates().map { it.token })
    }

    @Test
    fun `three independently-claimed candidates each recover without overwriting each other's files or queue entries`() {
        val files = (1..3).map { writeInterruptedSegment("multi-$it.wav", actualAudioBytes = it * 20, declaredDataSize = 0) }
        val journal = ActiveSegmentJournal(context())
        files.forEachIndexed { i, file -> seedRecoveryCandidate(journal, "token-$i", OutputTarget.FileTarget(file)) }
        val manager = WavRecoveryManager(journalFor = { journal })

        val outcomes = manager.recoverIfNeeded(context())

        assertEquals(3, outcomes.size)
        assertTrue(outcomes.all { it is RecoveryOutcome.Recovered })
        files.forEachIndexed { i, file ->
            val format = WavRiffParser.parse(file.inputStream())
            assertEquals("candidate $i's own file must declare exactly its own audio bytes, " +
                "never another candidate's", ((i + 1) * 20).toLong(), format?.dataSize)
        }
        assertTrue(journal.peekRecoveryCandidates().isEmpty())
    }

    // ---- readFullyOrFail: no-progress guard, tested directly (no real FileChannel needed) ----

    @Test
    fun `readFullyOrFail terminates with an honest failure instead of looping forever when reads repeatedly make no progress`() {
        var callCount = 0
        try {
            readFullyOrFail(44) { _: ByteBuffer ->
                callCount++
                0 // no progress, but not EOF either -- a genuinely misbehaving provider can do this
            }
            org.junit.Assert.fail("expected an IOException rather than looping forever")
        } catch (e: IOException) {
            assertTrue("expected a small, bounded number of calls before giving up, not an " +
                "infinite loop; got $callCount", callCount in 1..5)
        }
    }

    @Test
    fun `readFullyOrFail succeeds normally when reads make steady progress`() {
        val source = byteArrayOf(1, 2, 3, 4)
        var offset = 0
        val result = readFullyOrFail(source.size) { buffer ->
            val n = minOf(1, source.size - offset) // one byte at a time -- still progress, never zero
            buffer.put(source, offset, n)
            offset += n
            n
        }
        assertArrayEquals(source, result)
    }

    @Test
    fun `readFullyOrFail stops cleanly at EOF without requiring the full length`() {
        val result = readFullyOrFail(44) { _ -> -1 }
        assertEquals(0, result.size)
    }

    // ---- Real SAF (ContentProvider) opening path -- not just the injected RecoverableChannel fake ----

    /** A minimal real [ContentProvider] backed by plain [File]s, registered with Robolectric so
     * [openWritableRecoveryChannel]/[openReadableRecoveryChannel]'s actual production code (real
     * `ContentResolver.openFileDescriptor` calls, real `FileOutputStream`/`FileInputStream`
     * channels) is what gets exercised -- not a hand-rolled fake standing in for it. */
    class FakeSafProvider : ContentProvider() {
        companion object {
            val backingFiles = mutableMapOf<String, File>()
            var servePipes = false
        }
        override fun onCreate() = true
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            if (servePipes) {
                val pipe = ParcelFileDescriptor.createPipe()
                return if (mode.contains("w")) pipe[1] else pipe[0]
            }
            val file = backingFiles[uri.lastPathSegment] ?: throw FileNotFoundException(uri.toString())
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
        }
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                            selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    }

    private fun registerSafProvider(): String {
        val authority = "com.example.wavrecorder.test.saf"
        Robolectric.buildContentProvider(FakeSafProvider::class.java).create(authority)
        FakeSafProvider.backingFiles.clear()
        FakeSafProvider.servePipes = false
        return authority
    }

    @Test
    fun `real SAF recovery through a genuinely seekable content provider patches and verifies successfully`() {
        val authority = registerSafProvider()
        val backingFile = writeInterruptedSegment("saf-backed.wav", actualAudioBytes = 100, declaredDataSize = 0)
        FakeSafProvider.backingFiles["doc"] = backingFile
        val uri = Uri.parse("content://$authority/doc")
        val journal = ActiveSegmentJournal(context())
        seedRecoveryCandidate(journal, "token", OutputTarget.SafTarget(uri, "saf-backed.wav"))
        val manager = WavRecoveryManager(journalFor = { journal }) // default, production opening path

        val outcome = singleOutcome(manager)

        assertTrue("expected a successful recovery through the real SAF opening path, got: $outcome",
            outcome is RecoveryOutcome.Recovered)
        assertEquals(100L, (outcome as RecoveryOutcome.Recovered).recoveredAudioBytes)
        // Read back the real backing file directly (bypassing the provider) to confirm the patch
        // actually landed on disk, not just that the in-memory outcome claimed success.
        val format = WavRiffParser.parse(backingFile.inputStream())
        assertEquals(100L, format?.dataSize)
        assertTrue(journal.peekRecoveryCandidates().isEmpty())
    }

    @Test
    fun `a non-seekable SAF provider fails recovery honestly instead of throwing NonReadableChannelException`() {
        val authority = registerSafProvider()
        FakeSafProvider.servePipes = true
        val uri = Uri.parse("content://$authority/pipe-doc")
        val journal = ActiveSegmentJournal(context())
        seedRecoveryCandidate(journal, "token", OutputTarget.SafTarget(uri, "pipe-doc.wav"))
        val manager = WavRecoveryManager(journalFor = { journal })

        val outcome = singleOutcome(manager)

        assertTrue("a non-seekable provider must be reported as an honest failure, got: $outcome",
            outcome is RecoveryOutcome.Failed)
        assertEquals("the record must be retained for a later retry", "token", journal.peekRecoveryCandidates().single().token)
    }

    @Test
    fun `local-file recovery closes both the writable and readable descriptors exactly once on success`() {
        val file = writeInterruptedSegment("close-check.wav", actualAudioBytes = 100, declaredDataSize = 0)
        val journal = ActiveSegmentJournal(context())
        seedRecoveryCandidate(journal, "token", OutputTarget.FileTarget(file))
        var writableCloseCalls = 0
        var readableCloseCalls = 0
        val manager = WavRecoveryManager(
            journalFor = { journal },
            openWritable = { _, target ->
                val raf = RandomAccessFile((target as OutputTarget.FileTarget).file, "rw")
                object : RecoverableChannel {
                    override fun size(): Long = raf.length()
                    override fun position(newPosition: Long) { raf.seek(newPosition) }
                    override fun write(bytes: ByteArray) { raf.write(bytes) }
                    override fun force() {}
                    override fun close() { writableCloseCalls++; raf.close() }
                }
            },
            openReadable = { _, target ->
                val raf = RandomAccessFile((target as OutputTarget.FileTarget).file, "r")
                object : ReadableRecoveryChannel {
                    override fun position(newPosition: Long) { raf.seek(newPosition) }
                    override fun readFully(length: Int): ByteArray {
                        val bytes = ByteArray(length)
                        raf.readFully(bytes)
                        return bytes
                    }
                    override fun close() { readableCloseCalls++; raf.close() }
                }
            }
        )

        val outcome = singleOutcome(manager)

        assertTrue(outcome is RecoveryOutcome.Recovered)
        assertEquals(1, writableCloseCalls)
        assertEquals(1, readableCloseCalls)
    }
}
