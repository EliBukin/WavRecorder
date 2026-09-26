package com.example.wavrecorder

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import java.io.File
import java.util.UUID

/**
 * A JVM-process-lifetime identifier: computed once, the first time this object is loaded in a
 * given process, and never again -- so it stays identical across every [RecordingService]
 * instance created/destroyed within that one process (Android can recreate a Service in the same
 * still-running process, e.g. a fresh `onCreate()` shortly after a previous instance's
 * `onDestroy()` while its recording thread's own non-blocking teardown is still in flight -- see
 * [RecordingService.onDestroy]'s doc), but is guaranteed different after a genuine process
 * restart (a new process means a new JVM, hence a fresh class-load and a fresh UUID).
 * [ActiveSegmentJournal] uses this to tell "this ACTIVE record belongs to a segment still owned
 * by *this same, still-running* process" apart from "this ACTIVE record is a genuine leftover
 * from an actually-previous, now-dead process" -- only the latter is safe to claim as a recovery
 * candidate; see [ActiveSegmentJournal.claimActiveAsRecoveryCandidate].
 */
internal object ProcessInstanceId {
    val value: String = UUID.randomUUID().toString()
}

/**
 * Durable journal used to recover WAV segments an abrupt process death interrupted, without ever
 * mistaking a *currently live* segment (in this same, still-running process) for a crashed one,
 * and without ever losing track of one crashed segment because a later crash's own leftover
 * record was queued in behind it.
 *
 * There are two independently-keyed records:
 * - **ACTIVE**: the single segment [WavRecorder] is (or was most recently, in *this* process)
 *   actively writing to. Written by [persistActive] when a segment opens, cleared by
 *   [clearActiveIfMatches] once it finalizes successfully. Tagged with [ProcessInstanceId] so a
 *   same-process Service recreation can recognize -- and refuse to claim -- its own still-owned
 *   record; see [claimActiveAsRecoveryCandidate].
 * - **RECOVERY_PENDING**: an ordered, durable *queue* of records proven to belong to an earlier,
 *   now-gone process -- populated only by [claimActiveAsRecoveryCandidate] appending to the end,
 *   never written directly. [WavRecoveryManager] reads/removes entries from this queue, never
 *   ACTIVE. A queue (not a single slot) so a segment that fails to recover on one attempt is
 *   never silently evicted by a *later* crash's own leftover record claimed at a subsequent
 *   startup -- each candidate accumulates independently until it's individually removed by a
 *   successful, verified recovery.
 *
 * [claimActiveAsRecoveryCandidate] is the mechanism that keeps ACTIVE and RECOVERY_PENDING from
 * ever colliding: it atomically appends whatever is in ACTIVE onto the end of the RECOVERY_PENDING
 * queue and clears ACTIVE, in one lock-held operation -- but only when that ACTIVE record is
 * provably *not* owned by this same process (see [ClaimResult.OwnedByThisProcess]). Called exactly
 * once, synchronously, at service startup -- before any new segment in this process can possibly
 * call [persistActive] -- so a live segment's own record can never be read as a previous process's
 * crashed one, and recovery work can safely run fully asynchronously afterward while a brand new
 * live segment is recorded, since the two records live in entirely separate keys.
 *
 * Backed by [SharedPreferences] (survives process death, not just object recreation), the same
 * durability approach [PendingOutcomeStore] already uses for terminal outcomes. Every mutating
 * entry point uses [SharedPreferences.Editor.commit] (synchronous) rather than `apply()`, for the
 * same reason [PendingOutcomeStore] does: this is the only way to have any real confidence a write
 * actually reached disk before the call returns, which matters here specifically because the whole
 * point is surviving an *abrupt* process death moments later. Every entry point returns (or, for
 * [claimActiveAsRecoveryCandidate], reports via [ClaimResult]) whether its underlying write
 * actually succeeded -- callers must check it (see [WavRecorder.openSegment]'s fail-before-capture
 * policy and [WavRecoveryManager.claimPreviousSession]).
 *
 * **`commit()` failure does not mean "nothing changed."** `SharedPreferencesImpl.commit()` (and
 * `apply()`) apply an edit to the in-memory preferences map *synchronously and unconditionally*,
 * then attempt the disk write and report only *that* outcome -- a `false` result means the disk
 * write failed, not that the edit never happened. Left uncorrected, every mutating method below
 * would leave a same-process reader (`peekActive()`/`peekRecoveryCandidates()`, or this same
 * method called again) observing the *attempted* new state even after reporting failure: a failed
 * [persistActive] would leave a phantom ACTIVE record pointing at a file [WavRecorder] is about to
 * delete, and permanently block every later retry in the same process (the phantom's token would
 * never match a future `expectedPreviousToken`); a failed [claimActiveAsRecoveryCandidate] would
 * make the previous ACTIVE record disappear from `peekActive()` even though it was never durably
 * queued, letting a brand new segment silently claim the slot; a failed
 * [removeRecoveryCandidateIfMatches] would make a still-unrecovered candidate invisible to a
 * same-process retry. Every mutating method below corrects for this: if [commitEditor] reports
 * failure, it immediately issues a *separate* corrective edit restoring the exact pre-mutation
 * snapshot (also going through [commitEditor], so its own in-memory application is real and
 * immediate regardless of whether *that* write reaches disk either) -- so a same-process reader
 * never observes a failed mutation as though it happened. This does not manufacture disk-level
 * atomicity across two separate commits (a process death between the failed edit and the
 * corrective one is not closeable from user code), only truthful in-memory state for as long as
 * this process keeps running, which is what every same-process invariant above actually needs.
 *
 * All entry points are synchronized on the **companion-level** [LOCK] -- not an instance field --
 * because production code ([WavRecorder]/[WavRecoveryManager]) constructs a fresh
 * `ActiveSegmentJournal` on every call via their own `journalFor(context)` seam. An instance-level
 * lock would therefore never actually serialize anything in production (each call would lock a
 * different, unshared object); a lock shared by the whole class does, combined with the
 * [SharedPreferences] object itself already being a process-wide singleton per (context, name) via
 * Android's own internal caching.
 *
 * [token] identity uses [java.util.UUID] strings (not a resettable in-process counter): a counter
 * that restarts at 0 every process can mint the same value a still-outstanding crashed record from
 * an *earlier* process used, which would let an unrelated new segment be mistaken for a match. A
 * random UUID cannot repeat across process restarts in any practical sense.
 */
internal open class ActiveSegmentJournal(
    context: Context,
    // Seam over the final commit step of every mutation below -- defaults to the real, synchronous
    // SharedPreferences.Editor.commit(). A test overrides this to force a "disk write failed"
    // result while still calling the real editor.commit() first, so the in-memory
    // SharedPreferences map is genuinely mutated exactly the way production's real commit()
    // failure would leave it -- see the class doc above. Production code must never have a reason
    // to override this; it exists purely so tests can model the failure faithfully without
    // reflection or a hand-rolled fake that doesn't actually touch the in-memory map.
    private val commitEditor: (SharedPreferences.Editor) -> Boolean = { it.commit() }
) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "wav_recorder_active_segment"

        // internal (not private) so a test can prove this lock is genuinely shared across
        // independently-constructed ActiveSegmentJournal instances -- see
        // ActiveSegmentJournalTest's cross-instance-serialization test.
        internal val LOCK = Any()

        private val ACTIVE_KEYS = KeySet(
            token = "active_token", type = "active_target_type", path = "active_target_path",
            uri = "active_target_uri", name = "active_target_name",
            sampleRate = "active_sample_rate", channels = "active_channels", bits = "active_bits_per_sample",
            encoding = "active_encoding", channelMask = "active_channel_mask",
            channelIndexMask = "active_channel_index_mask", dataOffset = "active_data_offset"
        )
        private const val ACTIVE_OWNER_KEY = "active_owner_process_id"
        private const val RECOVERY_COUNT_KEY = "recovery_count"

        private fun recoveryKeysFor(index: Int) = KeySet(
            token = "recovery_${index}_token", type = "recovery_${index}_target_type",
            path = "recovery_${index}_target_path", uri = "recovery_${index}_target_uri",
            name = "recovery_${index}_target_name", sampleRate = "recovery_${index}_sample_rate",
            channels = "recovery_${index}_channels", bits = "recovery_${index}_bits_per_sample",
            encoding = "recovery_${index}_encoding", channelMask = "recovery_${index}_channel_mask",
            channelIndexMask = "recovery_${index}_channel_index_mask", dataOffset = "recovery_${index}_data_offset"
        )

        private const val TARGET_FILE = "FILE"
        private const val TARGET_SAF = "SAF"
    }

    /** The SharedPreferences keys of one record. [encoding], [channelMask], [channelIndexMask] and
     * [dataOffset] were added with format negotiation; a record written by an earlier app version
     * has none of them (see [Record.encoding]). */
    private class KeySet(
        val token: String, val type: String, val path: String, val uri: String, val name: String,
        val sampleRate: String, val channels: String, val bits: String,
        val encoding: String, val channelMask: String, val channelIndexMask: String, val dataOffset: String
    )

    /**
     * One journaled segment. [sampleRate], [channels] and [bitsPerSample] (the container size) are
     * all an earlier app version stored; [encoding] is null for such a legacy record, which always
     * describes the classic 16-bit integer PCM, 44-byte-header layout. Records written now carry
     * the full format and where its audio starts ([dataOffset]).
     */
    data class Record(
        val token: String,
        val target: OutputTarget,
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
        val encoding: PcmEncoding? = null,
        val channelMask: Int = 0,
        val channelIndexMask: Int = 0,
        val dataOffset: Long = WavHeaderWriter.HEADER_SIZE.toLong()
    ) {
        val isLegacy: Boolean get() = encoding == null

        /** The segment's full sample format, or null if the stored values don't describe one. */
        val format: PcmFormat?
            get() = if (encoding == null) {
                PcmFormat.fromLegacy(sampleRate, channels, bitsPerSample)
            } else {
                try {
                    PcmFormat(sampleRate, encoding, channels, channelMask, channelIndexMask)
                } catch (_: IllegalArgumentException) {
                    null
                }
            }

        companion object {
            fun of(token: String, target: OutputTarget, format: PcmFormat) = Record(
                token, target, format.sampleRate, format.channelCount, format.containerBitsPerSample,
                format.encoding, format.channelMask, format.channelIndexMask,
                WavHeaderWriter.headerSize(format).toLong()
            )
        }
    }

    /** Outcome of [claimActiveAsRecoveryCandidate]. */
    sealed class ClaimResult {
        /** Nothing was active -- the previous process (if any) finalized cleanly, or this is the
         * first ever launch. */
        object Empty : ClaimResult()
        /** The current ACTIVE record is owned by *this same, still-running* process (see
         * [ProcessInstanceId]) -- e.g. this [RecordingService] instance was recreated while a
         * previous instance's recording thread was still finishing its own non-blocking teardown.
         * Left completely untouched: it is not a crashed segment, and whichever session owns it
         * will clear it itself once its own finalization actually completes. */
        object OwnedByThisProcess : ClaimResult()
        /** Successfully appended to the RECOVERY_PENDING queue and cleared from ACTIVE. */
        data class Claimed(val record: Record) : ClaimResult()
        /** There was a foreign-owned (or legacy/unowned) ACTIVE record, but the durable
         * append-and-clear commit itself failed -- ACTIVE (record and owner) and the entire
         * recovery queue are both restored to their exact pre-claim state via a single corrective
         * commit (see [restoreActiveAndQueue]), so it stays logically visible/protected in this
         * same process and the queue never gains a phantom entry -- but the record also was not
         * durably moved into the recovery queue this time. */
        data class Failed(val record: Record) : ClaimResult()
    }

    // ---- ACTIVE: the segment WavRecorder is (or was most recently) writing to in this process ----

    /**
     * Persists [target] (opened for [token]) as the current active segment, tagged with this
     * process's [ProcessInstanceId]. [expectedPreviousToken] is the token this call is allowed to
     * legitimately supersede -- the previous segment's own token for a rollover, or null for the
     * very first segment of a session (expecting ACTIVE to currently be empty). If the record
     * actually present doesn't match that expectation (a foreign, unclaimed record -- e.g. a
     * previous process's crashed segment that [claimActiveAsRecoveryCandidate] failed to move into
     * the recovery queue at startup, see [ClaimResult.Failed]), this refuses to overwrite it and
     * returns false: durably recording a *new* segment must never cost the only durable trace of
     * an old, still-unresolved one. Returns whether the underlying synchronous write actually
     * succeeded -- see [WavRecorder.openSegment]'s fail-before-capture policy for why this must
     * never be silently ignored either way.
     */
    open fun persistActive(
        token: String, expectedPreviousToken: String?, target: OutputTarget, format: PcmFormat
    ): Boolean = persistRecord(Record.of(token, target, format), expectedPreviousToken)

    /** Persists a record in the legacy form every earlier app version wrote (integer PCM described
     * only by rate, channels and bit depth, with the classic 44-byte header) -- same contract as
     * the [PcmFormat] overload, which is what recording itself uses. */
    open fun persistActive(
        token: String, expectedPreviousToken: String?, target: OutputTarget,
        sampleRate: Int, channels: Int, bitsPerSample: Int
    ): Boolean {
        if (PcmFormat.fromLegacy(sampleRate, channels, bitsPerSample) == null) return false
        return persistRecord(Record(token, target, sampleRate, channels, bitsPerSample), expectedPreviousToken)
    }

    private fun persistRecord(record: Record, expectedPreviousToken: String?): Boolean = synchronized(LOCK) {
        val before = snapshotActive()
        if (before.record != null && before.record.token != expectedPreviousToken) return@synchronized false
        val editor = prefs.edit()
        writeKeys(editor, ACTIVE_KEYS, record)
        editor.putString(ACTIVE_OWNER_KEY, ProcessInstanceId.value)
        if (commitEditor(editor)) return@synchronized true
        // The edit above already applied to the in-memory SharedPreferences map (see class doc) --
        // restore the pre-mutation snapshot so a same-process retry (with expectedPreviousToken =
        // null, expecting an empty slot) is never permanently blocked by a phantom token that was
        // never actually durable, and so the partial file WavRecorder.openSegment() is about to
        // delete never remains "active" in memory.
        restoreActive(before)
        false
    }

    /** Returns the currently-persisted active record, if any, without clearing it. */
    open fun peekActive(): Record? = synchronized(LOCK) { readRecord(ACTIVE_KEYS) }

    /** Clears the active record only if it's still the one identified by [token] -- a segment
     * finalizing must never clear a *different*, newer segment's still-active record (e.g. a
     * stale thread from an already-superseded session finishing its own cleanup late). Returns
     * true if the record is clear afterward (matched-and-cleared, or was already absent), false
     * if a different token's record was left untouched, and also false if the clearing write
     * itself failed -- callers must check this (see [WavRecorder.closeSegment]). */
    open fun clearActiveIfMatches(token: String): Boolean = synchronized(LOCK) {
        val before = snapshotActive()
        if (before.record == null) return@synchronized true
        if (before.record.token != token) return@synchronized false
        val editor = prefs.edit()
        clearKeys(editor, ACTIVE_KEYS)
        editor.remove(ACTIVE_OWNER_KEY)
        if (commitEditor(editor)) return@synchronized true
        // A cleanly finalized segment whose clear fails must not silently vanish from the
        // in-memory view either: the file itself is fine (this is only ever called after a
        // successful finalization), but claiming the record gone here would let a later recovery
        // pass never re-verify it if this same process crashes before a corrective write lands.
        restoreActive(before)
        false
    }

    /** Test-only seam: persists an ACTIVE record tagged with an explicit [ownerProcessId] rather
     * than this process's own [ProcessInstanceId], and without [persistActive]'s
     * expectedPreviousToken supersede-protection -- lets a test simulate a genuinely different
     * (or, with [ownerProcessId] equal to [ProcessInstanceId.value], this same) process's ACTIVE
     * record without needing to actually spawn a second JVM. Production code must never call this
     * -- [persistActive] always tags with this process's own id. */
    internal fun persistActiveWithOwnerForTest(
        token: String, ownerProcessId: String?, target: OutputTarget, sampleRate: Int, channels: Int, bitsPerSample: Int
    ): Boolean = persistRecordWithOwnerForTest(
        Record(token, target, sampleRate, channels, bitsPerSample), ownerProcessId, legacyKeysOnly = true
    )

    /** Test-only: like the overload above, for a segment of any [format] (written with the full,
     * current key set rather than the legacy one). */
    internal fun persistActiveWithOwnerForTest(
        token: String, ownerProcessId: String?, target: OutputTarget, format: PcmFormat
    ): Boolean = persistRecordWithOwnerForTest(Record.of(token, target, format), ownerProcessId, legacyKeysOnly = false)

    /** [legacyKeysOnly] writes exactly the keys an earlier app version wrote (no format keys), so
     * a test can exercise reading and recovering such a record. */
    private fun persistRecordWithOwnerForTest(record: Record, ownerProcessId: String?, legacyKeysOnly: Boolean): Boolean =
        synchronized(LOCK) {
            val editor = prefs.edit()
            writeKeys(editor, ACTIVE_KEYS, record)
            if (legacyKeysOnly) {
                editor.remove(ACTIVE_KEYS.encoding).remove(ACTIVE_KEYS.channelMask)
                    .remove(ACTIVE_KEYS.channelIndexMask).remove(ACTIVE_KEYS.dataOffset)
            }
            if (ownerProcessId != null) editor.putString(ACTIVE_OWNER_KEY, ownerProcessId) else editor.remove(ACTIVE_OWNER_KEY)
            editor.commit()
        }

    /** Test-only: unconditionally clears the ACTIVE slot regardless of its current token or
     * owner -- lets a test reset journal state between independent scenarios that intentionally
     * share one SharedPreferences-backed Application context within a single test method (e.g.
     * several separately-constructed `RecordingService` instances in one `@Test`, which is not a
     * distinct process the way production's [ProcessInstanceId] boundary assumes). Production
     * code must never call this. */
    internal fun clearActiveForTest(): Unit = synchronized(LOCK) {
        val editor = prefs.edit()
        clearKeys(editor, ACTIVE_KEYS)
        editor.remove(ACTIVE_OWNER_KEY)
        editor.commit()
    }

    /** Test-only: the raw ACTIVE_OWNER_KEY value currently stored, without going through
     * [peekActive] (which returns a [Record] that deliberately has no owner field -- owner is
     * bookkeeping [ActiveSegmentJournal] itself needs, not part of a segment's own identity). Lets
     * a test assert a rollback genuinely restored the *exact* pre-mutation owner string, not just
     * that some record with a matching token reappeared. Production code must never call this. */
    internal fun peekActiveOwnerForTest(): String? = synchronized(LOCK) { prefs.getString(ACTIVE_OWNER_KEY, null) }

    // ---- RECOVERY_PENDING: an ordered queue of records proven to belong to an earlier, now-gone process ----

    /**
     * Atomically appends the current ACTIVE record (if any, and if not owned by this same
     * process) onto the end of the RECOVERY_PENDING queue and clears ACTIVE, in one lock-held
     * operation. Must be called exactly once, synchronously, at service startup -- before any new
     * segment in this process can possibly call [persistActive] -- so a live segment's own record
     * can never be mistaken for a previous process's crashed one. See [ClaimResult] for every
     * possible outcome, including the failure cases a caller must not silently treat as "nothing
     * to recover".
     */
    open fun claimActiveAsRecoveryCandidate(): ClaimResult = synchronized(LOCK) {
        val active = readRecord(ACTIVE_KEYS) ?: return@synchronized ClaimResult.Empty
        val owner = prefs.getString(ACTIVE_OWNER_KEY, null)
        if (owner == ProcessInstanceId.value) return@synchronized ClaimResult.OwnedByThisProcess
        val queueBefore = readRecoveryQueue()
        val editor = prefs.edit()
        clearKeys(editor, ACTIVE_KEYS)
        editor.remove(ACTIVE_OWNER_KEY)
        appendRecoveryEntry(editor, active)
        if (commitEditor(editor)) return@synchronized ClaimResult.Claimed(active)
        // The clear-ACTIVE-and-append edit above already applied in memory -- restore both halves
        // in a single corrective commit (not two separate ones) so there is no process-death
        // window between "ACTIVE restored" and "queue restored" in which the same segment could
        // be observed as both simultaneously ACTIVE and durably queued; see
        // restoreActiveAndQueue's doc.
        restoreActiveAndQueue(ActiveSnapshot(active, owner), queueBefore)
        ClaimResult.Failed(active)
    }

    /** Every currently-queued recovery candidate, oldest (first-claimed) first, without removing
     * any of them. */
    open fun peekRecoveryCandidates(): List<Record> = synchronized(LOCK) { readRecoveryQueue() }

    /** Removes the exact candidate identified by [token] from the queue, if present, reindexing
     * the remaining entries in one commit -- a candidate that failed to recover must stay queued
     * (untouched) while a different, independent candidate is successfully removed. Returns true
     * if the queue no longer contains [token] afterward (removed-and-committed, or it was already
     * absent -- "already clear" semantics, mirroring [clearActiveIfMatches]), false if it's still
     * present because the removal write itself failed -- callers must check this. */
    open fun removeRecoveryCandidateIfMatches(token: String): Boolean = synchronized(LOCK) {
        val entries = readRecoveryQueue()
        val index = entries.indexOfFirst { it.token == token }
        if (index < 0) return@synchronized true
        val remaining = entries.toMutableList().also { it.removeAt(index) }
        val editor = prefs.edit()
        for (i in entries.indices) clearKeys(editor, recoveryKeysFor(i))
        remaining.forEachIndexed { i, record -> writeKeys(editor, recoveryKeysFor(i), record) }
        editor.putInt(RECOVERY_COUNT_KEY, remaining.size)
        if (commitEditor(editor)) return@synchronized true
        // The reindex-without-the-removed-entry edit above already applied in memory -- restore
        // the full pre-removal queue so the candidate remains logically visible and independently
        // retryable in this same process, exactly as if this call had never run.
        restoreRecoveryQueue(entries)
        false
    }

    private fun appendRecoveryEntry(editor: SharedPreferences.Editor, record: Record) {
        val count = prefs.getInt(RECOVERY_COUNT_KEY, 0)
        writeKeys(editor, recoveryKeysFor(count), record)
        editor.putInt(RECOVERY_COUNT_KEY, count + 1)
    }

    private fun readRecoveryQueue(): List<Record> {
        val count = prefs.getInt(RECOVERY_COUNT_KEY, 0)
        return (0 until count).mapNotNull { readRecord(recoveryKeysFor(it)) }
    }

    /** Everything a mutation of ACTIVE can touch, captured before that mutation runs, so a failed
     * [commitEditor] call's already-applied in-memory edit can be corrected back to exactly this
     * -- see the class doc's "commit() failure does not mean nothing changed" note. */
    private class ActiveSnapshot(val record: Record?, val owner: String?)

    private fun snapshotActive(): ActiveSnapshot = ActiveSnapshot(readRecord(ACTIVE_KEYS), prefs.getString(ACTIVE_OWNER_KEY, null))

    /** Stages ACTIVE's restoration to [before] into [editor], without committing -- the shared
     * building block [restoreActive] and [restoreActiveAndQueue] both commit on top of. */
    private fun applyActiveRestore(editor: SharedPreferences.Editor, before: ActiveSnapshot) {
        val record = before.record
        if (record == null) {
            clearKeys(editor, ACTIVE_KEYS)
        } else {
            writeKeys(editor, ACTIVE_KEYS, record)
        }
        if (before.owner == null) editor.remove(ACTIVE_OWNER_KEY) else editor.putString(ACTIVE_OWNER_KEY, before.owner)
    }

    /** Stages the RECOVERY_PENDING queue's restoration to exactly [before] into [editor], without
     * committing -- the shared building block [restoreRecoveryQueue] and [restoreActiveAndQueue]
     * both commit on top of. Clears every slot the *current* (post-failed-mutation) count could
     * occupy before rewriting [before], since a failed removal or claim may have left the count
     * different from what it's being restored to. */
    private fun applyRecoveryQueueRestore(editor: SharedPreferences.Editor, before: List<Record>) {
        val currentCount = prefs.getInt(RECOVERY_COUNT_KEY, 0)
        for (i in 0 until maxOf(currentCount, before.size)) clearKeys(editor, recoveryKeysFor(i))
        before.forEachIndexed { i, record -> writeKeys(editor, recoveryKeysFor(i), record) }
        editor.putInt(RECOVERY_COUNT_KEY, before.size)
    }

    /** Best-effort corrective write restoring ACTIVE to [before] alone, issued only after a
     * mutating commit that touched only ACTIVE already reported failure. This is a *separate*
     * commit from the original failed one -- it cannot make that original, failed disk write
     * retroactively succeed, and this corrective write's own disk result is intentionally not
     * checked or reported further (there is nothing more truthful this method could still promise
     * if it fails too; see the class doc's residual-limitation note). What it does guarantee is
     * that the *in-memory* map -- what every same-process reader actually observes -- is put back
     * to [before] immediately, since committing the correction applies it to memory synchronously
     * the same way the failed mutation did. */
    private fun restoreActive(before: ActiveSnapshot) {
        val editor = prefs.edit()
        applyActiveRestore(editor, before)
        commitEditor(editor)
    }

    /** Best-effort corrective write restoring the RECOVERY_PENDING queue to exactly [before]
     * alone -- same rationale as [restoreActive], for an operation that touched only the queue. */
    private fun restoreRecoveryQueue(before: List<Record>) {
        val editor = prefs.edit()
        applyRecoveryQueueRestore(editor, before)
        commitEditor(editor)
    }

    /** Best-effort corrective write restoring *both* ACTIVE (record and owner) and the entire
     * RECOVERY_PENDING queue to [activeBefore]/[queueBefore], as **one** [SharedPreferences.Editor]
     * committed through a **single** [commitEditor] call -- used only by
     * [claimActiveAsRecoveryCandidate], whose failed commit can leave both areas needing
     * correction together. Staging both restorations into the same editor (rather than calling
     * [restoreActive] and [restoreRecoveryQueue] separately) closes the process-death window that
     * two separate corrective commits would leave open: with two commits, a death between them
     * could land ACTIVE's restoration on disk while the queue's restoration never arrives (or vice
     * versa), leaving the same segment simultaneously readable as ACTIVE *and* durably queued for
     * recovery after a restart. A single commit cannot itself be split by a process death -- it
     * either reaches disk complete or (per the class doc) not at all -- so this closes that
     * specific window. It does not manufacture stronger guarantees than [SharedPreferences]
     * actually provides: a death during *this* corrective commit's own write syscall is still not
     * something application code can close (same residual limitation as every other commit here). */
    private fun restoreActiveAndQueue(activeBefore: ActiveSnapshot, queueBefore: List<Record>) {
        val editor = prefs.edit()
        applyActiveRestore(editor, activeBefore)
        applyRecoveryQueueRestore(editor, queueBefore)
        commitEditor(editor)
    }

    /** Writes [record] under [keys]. A legacy record (no [Record.encoding]) is written exactly as
     * an earlier version would have -- without the format keys -- so it round-trips unchanged. */
    private fun writeKeys(editor: SharedPreferences.Editor, keys: KeySet, record: Record) {
        val target = record.target
        editor.putString(keys.token, record.token)
            .putInt(keys.sampleRate, record.sampleRate)
            .putInt(keys.channels, record.channels)
            .putInt(keys.bits, record.bitsPerSample)
        if (record.encoding != null) {
            editor.putString(keys.encoding, record.encoding.name)
                .putInt(keys.channelMask, record.channelMask)
                .putInt(keys.channelIndexMask, record.channelIndexMask)
                .putLong(keys.dataOffset, record.dataOffset)
        } else {
            editor.remove(keys.encoding).remove(keys.channelMask).remove(keys.channelIndexMask).remove(keys.dataOffset)
        }
        when (target) {
            is OutputTarget.FileTarget -> editor
                .putString(keys.type, TARGET_FILE)
                .putString(keys.path, target.file.absolutePath)
                .remove(keys.uri)
                .remove(keys.name)
            is OutputTarget.SafTarget -> editor
                .putString(keys.type, TARGET_SAF)
                .putString(keys.uri, target.uri.toString())
                .putString(keys.name, target.name)
                .remove(keys.path)
        }
    }

    private fun clearKeys(editor: SharedPreferences.Editor, keys: KeySet) {
        editor.remove(keys.token).remove(keys.type).remove(keys.path)
            .remove(keys.uri).remove(keys.name)
            .remove(keys.sampleRate).remove(keys.channels).remove(keys.bits)
            .remove(keys.encoding).remove(keys.channelMask).remove(keys.channelIndexMask).remove(keys.dataOffset)
    }

    private fun readRecord(keys: KeySet): Record? {
        if (!prefs.contains(keys.token)) return null
        val token = prefs.getString(keys.token, null) ?: return null
        val sampleRate = prefs.getInt(keys.sampleRate, 0)
        val channels = prefs.getInt(keys.channels, 0)
        val bitsPerSample = prefs.getInt(keys.bits, 0)
        val target = readTarget(keys) ?: return null
        if (sampleRate <= 0 || channels <= 0 || bitsPerSample <= 0) return null
        // No encoding key: written by an earlier app version -- a legacy 16-bit/44-byte record.
        val encodingName = prefs.getString(keys.encoding, null)
            ?: return Record(token, target, sampleRate, channels, bitsPerSample)
        val encoding = PcmEncoding.entries.firstOrNull { it.name == encodingName } ?: return null
        val dataOffset = prefs.getLong(keys.dataOffset, 0L)
        if (dataOffset <= 0L) return null
        return Record(
            token, target, sampleRate, channels, bitsPerSample, encoding,
            channelMask = prefs.getInt(keys.channelMask, 0),
            channelIndexMask = prefs.getInt(keys.channelIndexMask, 0),
            dataOffset = dataOffset
        )
    }

    private fun readTarget(keys: KeySet): OutputTarget? = when (prefs.getString(keys.type, null)) {
        TARGET_FILE -> prefs.getString(keys.path, null)?.let { OutputTarget.FileTarget(File(it)) }
        TARGET_SAF -> {
            val uriString = prefs.getString(keys.uri, null)
            val name = prefs.getString(keys.name, null)
            if (uriString != null && name != null) OutputTarget.SafTarget(Uri.parse(uriString), name) else null
        }
        else -> null
    }
}
