package com.example.wavrecorder

import android.content.Context
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** What actually happened trying to recover one recovery-pending candidate. See [RecoveryOutcome.Mutation]
 * for how far a failed attempt actually got before failing. */
internal sealed class RecoveryOutcome {
    /** No recovery-pending record was found -- the previous session (if any) finalized cleanly,
     * or there was never one to begin with. Nothing was touched. */
    object NothingToRecover : RecoveryOutcome()
    /** The header was patched to declare exactly the complete, frame-aligned audio actually
     * present, the patch was forced to storage and verified (by reopening read-only and reading
     * it back), and the recovery-queue entry was durably removed. This is the only outcome that
     * means "fully done, nothing left to retry here." */
    data class Recovered(val target: OutputTarget, val recoveredAudioBytes: Long) : RecoveryOutcome()
    /** The header patch was written, forced to storage, and verified byte-for-byte correct on
     * disk -- but the durable recovery-queue entry could not be removed (a commit failure, or the
     * removal call itself threw). The *file* is genuinely fine and playable; only the queue's own
     * bookkeeping is stale, so a later startup will attempt to "recover" this file again --
     * redundantly (the same aligned byte count is recomputed and rewritten identically) but
     * harmlessly. Kept distinct from [Recovered] so this is never conflated with "nothing left to
     * do here at all." */
    data class VerifiedButNotCleared(val target: OutputTarget, val recoveredAudioBytes: Long) : RecoveryOutcome()
    /** Recovery could not be completed or verified; the recovery-queue entry is deliberately left
     * in place so a later attempt can retry. [mutation] distinguishes how far the write actually
     * got before this failed -- see [Mutation]. [reason] is a short, non-localized diagnostic for
     * logs. */
    data class Failed(val target: OutputTarget, val reason: String, val mutation: Mutation) : RecoveryOutcome()

    /** How far a failed recovery attempt actually got before failing -- callers must never
     * describe a [MAYBE_PARTIAL] or [WRITTEN_UNVERIFIED] failure as though the file were left
     * completely untouched. */
    enum class Mutation {
        /** The file (if it exists at all) was never opened for writing, or was opened but no
         * `write()` call was ever actually issued to it -- provably byte-for-byte as the crash
         * left it. */
        UNTOUCHED,
        /** A header write was attempted and may have partially landed: the write call itself
         * threw, or `force()`/close() failed in a way that leaves no confirmation the bytes
         * actually reached storage. Without a successful reopen+readback there is no way to know
         * the real bytes on disk -- must not be assumed either fully patched or fully untouched. */
        MAYBE_PARTIAL,
        /** The complete header write call returned successfully and was forced to
         * storage without error -- unlike [MAYBE_PARTIAL], the write itself is confirmed durable.
         * But the read-side reopen+verify step never *confirmed* it: verification either failed
         * outright (a parse/field mismatch), threw, or was never reached at all. Does not cover a
         * verification that itself succeeded but whose channel merely failed to close afterward --
         * that case is [WRITTEN_VERIFIED], not this one. */
        WRITTEN_UNVERIFIED,
        /** The header write was forced to storage *and* independently confirmed correct by
         * reopening and reading it back -- the file's on-disk contents are genuinely known-good.
         * Only a required cleanup step *after* that successful verification (closing the read-only
         * verification channel) failed. Distinct from [WRITTEN_UNVERIFIED] (verification itself
         * never succeeded) and from [RecoveryOutcome.VerifiedButNotCleared] (verification *and* its
         * own close both succeeded, but the later, separate recovery-queue removal failed
         * instead) -- this value covers only the close-failure-during-verification case, where the
         * queue entry is deliberately left in place because the close failure means recovery could
         * not even attempt the queue removal this time. */
        WRITTEN_VERIFIED
    }
}

/** Minimal seek+write surface recovery needs to patch a header -- deliberately not the full
 * [java.nio.channels.FileChannel] API, so a test can fake a provider that can't seek (e.g. a
 * cloud-backed SAF document proxied through a pipe) without needing a real non-seekable file
 * descriptor for every scenario. */
internal interface RecoverableChannel {
    fun size(): Long
    fun position(newPosition: Long)
    fun write(bytes: ByteArray)
    /** Best-effort-but-checked: pushes the just-written header past the OS page cache to physical
     * storage *before* verification/queue-removal are attempted, so those steps confirm real
     * durability rather than merely what the page cache currently holds. Unlike
     * [WavRecorder]'s periodic [SegmentWriter.force] (a nicety that's allowed to fail silently
     * during normal recording), a failure here is treated as a genuine recovery failure -- see
     * [RecoveryOutcome.Mutation.MAYBE_PARTIAL]. */
    fun force()
    fun close()
}

/** Minimal seek+read surface recovery needs to verify a header, opened *separately* from
 * [RecoverableChannel] -- see [openReadableRecoveryChannel]'s doc for why a single channel can't
 * safely serve both roles for a real SAF destination. */
internal interface ReadableRecoveryChannel {
    fun position(newPosition: Long)
    fun readFully(length: Int): ByteArray
    fun close()
}

private class FileChannelRecoverableChannel(
    private val channel: FileChannel,
    private val pfd: ParcelFileDescriptor?
) : RecoverableChannel {
    override fun size(): Long = channel.size()
    override fun position(newPosition: Long) {
        channel.position(newPosition)
    }
    override fun write(bytes: ByteArray) {
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) {
            val written = channel.write(buffer)
            if (written <= 0) throw IOException("Recovery write made no progress (returned $written)")
        }
    }
    // false: sync data only, not metadata (timestamps/permissions) -- matches WavRecorder's own
    // regular periodic SegmentWriter.force() (see FileChannelSegmentWriter), and is all the
    // durability guarantee a WAV header patch actually needs; metadata sync additionally risks
    // platform-specific failures on some fd origins (e.g. certain SAF-backed descriptors) for no
    // correctness benefit here.
    override fun force() = channel.force(false)
    override fun close() {
        try { channel.close() } finally { pfd?.close() }
    }
}

private class FileChannelReadableChannel(
    private val channel: FileChannel,
    private val pfd: ParcelFileDescriptor?
) : ReadableRecoveryChannel {
    override fun position(newPosition: Long) {
        channel.position(newPosition)
    }
    override fun readFully(length: Int): ByteArray = readFullyOrFail(length) { buffer -> channel.read(buffer) }
    override fun close() {
        try { channel.close() } finally { pfd?.close() }
    }
}

/** Reads exactly [length] bytes via repeated calls to [readChunk] (each call attempting to fill as
 * much of the remaining buffer as possible, exactly like [FileChannel.read]), stopping at EOF (a
 * negative result). Pulled out of [FileChannelReadableChannel.readFully] as its own standalone,
 * directly-unit-testable function so the no-progress guard below doesn't need a real FileChannel
 * to exercise: a provider can genuinely return exactly 0 (no progress, but not EOF) repeatedly,
 * which would otherwise spin this loop forever -- unlike a plain short read, zero progress can
 * never resolve itself by simply retrying indefinitely, so it's treated as a hard failure instead. */
internal fun readFullyOrFail(length: Int, readChunk: (ByteBuffer) -> Int): ByteArray {
    val buffer = ByteBuffer.allocate(length)
    while (buffer.hasRemaining()) {
        val n = readChunk(buffer)
        if (n < 0) break
        if (n == 0) throw IOException("Recovery verification read made no progress")
    }
    buffer.flip()
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    return bytes
}

/**
 * Opens [target] for the header-patch step, read/write but *without* truncating it -- unlike
 * `WavRecorder.openSegment`, which always opens a brand new, empty segment. Returns null (rather
 * than throwing) if the target can't be opened at all, e.g. the file/document no longer exists or
 * a SAF permission was revoked.
 *
 * Deliberately opened and closed as its own step, separate from [openReadableRecoveryChannel]:
 * for a local file, `RandomAccessFile("rw").channel` genuinely supports both read and write, but
 * for a SAF target the only public way to get a writable [FileChannel] from a [ParcelFileDescriptor]
 * is via `FileOutputStream(fd).channel`, which -- regardless of the fd's actual underlying open
 * mode -- Java's own stream/channel implementation permanently marks as write-only: any `read()`
 * call on it throws `NonReadableChannelException`. Trying to reuse that same channel for the
 * verification readback (the original bug this fixes) meant every real SAF recovery attempt threw
 * during verification, even though the patch itself had actually succeeded.
 */
private fun openWritableRecoveryChannel(context: Context, target: OutputTarget): RecoverableChannel? = when (target) {
    is OutputTarget.FileTarget ->
        if (!target.file.exists()) null
        else FileChannelRecoverableChannel(RandomAccessFile(target.file, "rw").channel, pfd = null)
    is OutputTarget.SafTarget -> {
        val pfd = try {
            context.contentResolver.openFileDescriptor(target.uri, "rw")
        } catch (e: Exception) {
            null
        }
        when {
            pfd == null -> null
            else -> try {
                FileChannelRecoverableChannel(FileOutputStream(pfd.fileDescriptor).channel, pfd)
            } catch (e: Exception) {
                try { pfd.close() } catch (_: Exception) {}
                null
            }
        }
    }
}

/** Opens [target] read-only, for the verification step *after* [openWritableRecoveryChannel]'s
 * channel has already been closed -- see that function's doc for why these must be two genuinely
 * separate opens (and, for SAF, two separate [ParcelFileDescriptor]s) rather than one channel
 * reused for both roles. Returns null if the target can't be reopened at all. */
private fun openReadableRecoveryChannel(context: Context, target: OutputTarget): ReadableRecoveryChannel? = when (target) {
    is OutputTarget.FileTarget ->
        if (!target.file.exists()) null
        else FileChannelReadableChannel(RandomAccessFile(target.file, "r").channel, pfd = null)
    is OutputTarget.SafTarget -> {
        val pfd = try {
            context.contentResolver.openFileDescriptor(target.uri, "r")
        } catch (e: Exception) {
            null
        }
        when {
            pfd == null -> null
            else -> try {
                FileChannelReadableChannel(FileInputStream(pfd.fileDescriptor).channel, pfd)
            } catch (e: Exception) {
                try { pfd.close() } catch (_: Exception) {}
                null
            }
        }
    }
}

/**
 * Repairs every WAV segment left mid-write by abrupt previous-process deaths -- see
 * [ActiveSegmentJournal] for how each such segment is durably identified (via the ordered
 * RECOVERY_PENDING queue specifically, never the live ACTIVE record), and
 * `WavRecorder.openSegment`/`closeSegment` for how the ACTIVE record is written/cleared during
 * normal operation. Deliberately scoped to *only* the files the queue names: this never scans or
 * touches any other WAV file, however it's shaped -- an imported or otherwise unrelated file with
 * trailing bytes is never "repaired" just because it looks similar.
 *
 * Conservative by design: recovery only ever seeks to byte 0 and rewrites the segment's own header
 * in place -- exactly as many bytes as the header the segment was created with (44 for records
 * from earlier app versions; 44-80 depending on format now, per [ActiveSegmentJournal.Record.dataOffset])
 * -- it never touches (or truncates) the PCM payload itself, and only removes a
 * candidate from the queue once its patch has actually been forced to storage, reopened, and
 * verified. A candidate this can't fully verify is reported honestly (see [RecoveryOutcome]) and
 * left in the queue for a later retry; independent candidates in the same queue never affect one
 * another -- one failing to recover never blocks or evicts another. This never deletes a file,
 * and never reports success it can't actually back up.
 */
internal class WavRecoveryManager(
    private val journalFor: (Context) -> ActiveSegmentJournal = { ActiveSegmentJournal(it) },
    // Seams so tests can simulate a target that can't be opened read/write at all, or a provider
    // that opens but isn't actually seekable, without needing a real SAF content provider for
    // every scenario -- the production opening paths themselves are separately covered against a
    // real, Robolectric-registered ContentProvider in WavRecoveryManagerTest.
    private val openWritable: (Context, OutputTarget) -> RecoverableChannel? = ::openWritableRecoveryChannel,
    private val openReadable: (Context, OutputTarget) -> ReadableRecoveryChannel? = ::openReadableRecoveryChannel
) {

    /**
     * Atomically claims whatever segment record is currently ACTIVE (if any, and if not owned by
     * this same still-running process) as a new recovery candidate, appended to the durable
     * RECOVERY_PENDING queue -- see [ActiveSegmentJournal.claimActiveAsRecoveryCandidate]. Must be
     * called exactly once, synchronously, during service startup, strictly *before* any new
     * segment in this process can call `WavRecorder.openSegment()`/persist its own ACTIVE record
     * -- otherwise a live segment's own record could be claimed (and later "recovered" mid-write)
     * as if it belonged to a crashed previous process. Returns the raw [ActiveSegmentJournal.ClaimResult]
     * so a caller can log the failure/owned-by-this-process cases honestly rather than treating
     * every non-[ActiveSegmentJournal.ClaimResult.Claimed] result as "nothing to recover".
     *
     * This is a small, bounded SharedPreferences read+write (a handful of keys, not a WAV file),
     * not the kind of potentially slow/unbounded file I/O [recoverIfNeeded] does -- it's the one
     * piece of this recovery pipeline that's deliberately synchronous specifically because its
     * ordering guarantee is what makes the rest of recovery safe to run fully asynchronously
     * afterward. The actual recovery work (opening each claimed candidate, seeking, patching,
     * reopening to verify) stays entirely on [recoverIfNeeded]'s caller's background thread.
     */
    fun claimPreviousSession(context: Context): ActiveSegmentJournal.ClaimResult =
        journalFor(context).claimActiveAsRecoveryCandidate()

    /** Attempts recovery for every candidate currently queued in RECOVERY_PENDING -- never ACTIVE,
     * which may belong to a live, currently-recording segment in this same process -- each
     * independently: one candidate failing to recover never blocks or removes a different,
     * independent candidate. Does real file I/O (open, seek, read, write) for every candidate --
     * callers must run this off the main thread. Returns one outcome per candidate that was queued
     * at the start of this call, in the same (oldest-first) order; an empty list means the queue
     * was empty (mirrors the single [RecoveryOutcome.NothingToRecover] case for a fully-empty
     * queue). */
    fun recoverIfNeeded(context: Context): List<RecoveryOutcome> {
        val journal = journalFor(context)
        val candidates = journal.peekRecoveryCandidates()
        if (candidates.isEmpty()) return listOf(RecoveryOutcome.NothingToRecover)
        return candidates.map { recoverOne(context, it, journal) }
    }

    private fun recoverOne(context: Context, record: ActiveSegmentJournal.Record, journal: ActiveSegmentJournal): RecoveryOutcome {
        var mutation = RecoveryOutcome.Mutation.UNTOUCHED
        val writable = try {
            openWritable(context, record.target)
        } catch (e: Exception) {
            null
        } ?: return RecoveryOutcome.Failed(record.target, "Could not open the file for recovery", mutation)

        val alignedAudioBytes: Long
        var closeFailureMessage: String? = null
        try {
            val actualLength = try {
                writable.size()
            } catch (e: Exception) {
                return RecoveryOutcome.Failed(record.target, "Could not determine the file's real length: ${e.message}", mutation)
            }
            val format = record.format
                ?: return RecoveryOutcome.Failed(record.target, "Unrecognized recorded format", mutation)
            val headerSize = record.dataOffset
            if (actualLength < headerSize) {
                return RecoveryOutcome.Failed(record.target, "File is smaller than its WAV header ($actualLength bytes)", mutation)
            }
            // Align down to a complete frame: a crash can land mid-sample, and a declared data
            // size that isn't a whole number of frames would leave the trailing partial sample
            // audible as a click/glitch in players that trust it literally. Also never declare
            // more than a WAV header can represent (a segment can't legitimately exceed it).
            val rawAudioBytes = actualLength - headerSize
            alignedAudioBytes = format.alignToFrame(minOf(rawAudioBytes, WavHeaderWriter.maxDataBytes(format)))

            val header = if (record.isLegacy) {
                // Exactly the header an earlier app version wrote for this file.
                WavHeaderWriter.build(record.sampleRate, record.channels, record.bitsPerSample, alignedAudioBytes)
            } else {
                // The RIFF size only counts a pad byte after odd-length audio if one is really there.
                WavHeaderWriter.build(format, alignedAudioBytes, includePadByte = rawAudioBytes > alignedAudioBytes)
            }
            val headerBytes = ByteArray(header.remaining())
            header.get(headerBytes)
            if (headerBytes.size.toLong() != headerSize) {
                // Never write a header of a different size than the one on disk: a longer one would
                // overwrite audio, a shorter one would leave stale bytes before the data.
                return RecoveryOutcome.Failed(
                    record.target, "Recorded header size $headerSize doesn't match its format (${headerBytes.size})", mutation
                )
            }

            try {
                writable.position(0)
            } catch (e: Exception) {
                // No write() call was ever issued -- still provably untouched.
                return RecoveryOutcome.Failed(record.target, "Could not seek to the header: ${e.message}", mutation)
            }
            try {
                writable.write(headerBytes)
            } catch (e: Exception) {
                // A write was attempted and may have partially landed -- no longer safe to call
                // this "untouched".
                return RecoveryOutcome.Failed(
                    record.target, "Could not patch the header: ${e.message}", RecoveryOutcome.Mutation.MAYBE_PARTIAL
                )
            }
            try {
                writable.force()
            } catch (e: Exception) {
                // The write call itself returned, but force() failing means there's no
                // confirmation the bytes actually reached storage -- must not claim a completed
                // write this can't back up.
                return RecoveryOutcome.Failed(
                    record.target, "Could not flush the header patch to storage: ${e.message}",
                    RecoveryOutcome.Mutation.MAYBE_PARTIAL
                )
            }
            // The write call succeeded and was confirmed forced to storage -- from here on, any
            // further failure (close, reopen, verify, queue removal) is a failure of *confirming*
            // an already-durable write, never a sign the write itself might not have landed.
            mutation = RecoveryOutcome.Mutation.WRITTEN_UNVERIFIED
        } finally {
            try {
                writable.close()
            } catch (e: Exception) {
                closeFailureMessage = e.message
            }
        }
        if (closeFailureMessage != null) {
            return RecoveryOutcome.Failed(record.target, "Failed to close the file after patching: $closeFailureMessage", mutation)
        }

        // The header write above is confirmed forced to storage by this point -- every outcome
        // from here on must say so, never imply the file is untouched.
        val readable = try {
            openReadable(context, record.target)
        } catch (e: Exception) {
            null
        } ?: return RecoveryOutcome.Failed(
            record.target, "Header was patched but the file could not be reopened for verification", mutation
        )

        var readableCloseFailureMessage: String? = null
        val verified = try {
            readable.position(0)
            val readBack = readable.readFully(record.dataOffset.toInt())
            val parsed = WavRiffParser.parse(readBack.inputStream())
            parsed != null && parsed.dataSize == alignedAudioBytes && parsed.dataOffset == record.dataOffset &&
                parsed.sampleRate == record.sampleRate && parsed.channels == record.channels &&
                parsed.bitsPerSample == record.bitsPerSample &&
                parsed.isFloat == (record.encoding?.isFloat ?: false)
        } catch (e: Exception) {
            false
        } finally {
            // Checked (never swallowed), like the writable channel's own close above: a close
            // failure here must not be silently converted into Recovered just because the read
            // itself already succeeded -- see the two branches below.
            try {
                readable.close()
            } catch (e: Exception) {
                readableCloseFailureMessage = e.message
            }
        }

        if (!verified) {
            // Primary failure is the verification/read itself; a close failure on top is retained
            // as suppressed diagnostic context rather than replacing (or being dropped alongside)
            // the more useful primary reason.
            val reason = "Header was patched but could not be verified afterward" +
                (readableCloseFailureMessage?.let { " (verification channel also failed to close: $it)" } ?: "")
            return RecoveryOutcome.Failed(record.target, reason, mutation)
        }
        if (readableCloseFailureMessage != null) {
            // The header really was read back and verified correct -- so this is WRITTEN_VERIFIED,
            // never the WRITTEN_UNVERIFIED `mutation` local (that would falsely claim verification
            // itself never confirmed the file). Still not the fully-clean Recovered outcome, and
            // the queue entry below must not be removed; a later retry will simply re-verify (and,
            // this time, hopefully cleanly close) the same, already-correct file.
            return RecoveryOutcome.Failed(
                record.target,
                "Header was patched and verified -- the file's contents are confirmed correct -- " +
                    "but the verification channel failed to close afterward: $readableCloseFailureMessage. " +
                    "The recovery-queue entry remains for a later retry.",
                RecoveryOutcome.Mutation.WRITTEN_VERIFIED
            )
        }

        // Only remove the queue entry once the patch is confirmed on disk -- checking (never
        // ignoring) both the Boolean result and any thrown exception, since either failure means
        // this candidate would otherwise be silently, redundantly re-attempted on the next
        // startup, which is harmless but must still be reported honestly rather than claimed as
        // the fully-clean Recovered outcome.
        val removed = try {
            journal.removeRecoveryCandidateIfMatches(record.token)
        } catch (e: Exception) {
            false
        }
        return if (removed) {
            RecoveryOutcome.Recovered(record.target, alignedAudioBytes)
        } else {
            RecoveryOutcome.VerifiedButNotCleared(record.target, alignedAudioBytes)
        }
    }
}
