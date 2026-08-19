package com.example.wavrecorder

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import java.io.File

/** Reconstructed from durable storage in place of the original cause -- see [PendingOutcomeStore].
 * [message] is always an already-resolved, safe, user-facing description (never a raw
 * Throwable/stack trace), so this must never be pattern-matched for a *specific* failure type
 * (e.g. [MicrophoneDisconnectedException]) the way a live-delivered cause can be. */
class PersistedOutcomeException(message: String) : Exception(message)

/**
 * Durable, minimal-schema persistence for the single most recent terminal [RecordingOutcome] that
 * no listener was attached to see live. Backed by [SharedPreferences] (survives process death,
 * not just [RecordingService] object recreation), so a session that ends while the app is fully
 * backgrounded -- or the process is killed outright -- is never lost the way a purely in-memory
 * field would be, and (if notifications are also denied) would otherwise be silently dropped.
 *
 * Deliberately stores plain, structured fields rather than serializing the outcome's [Exception]
 * cause: only [outputTargetType]/path/uri/name, the outcome category, an already-resolved safe
 * message string, and the originating session id are kept -- see [persist]/[consume].
 *
 * [persist]/[clear] use [SharedPreferences.Editor.commit] (synchronous) rather than `apply()`
 * (asynchronous, write-behind): this is a single small write that only ever happens at a terminal
 * recording event, so the synchronous cost is negligible, and it's the only way to have any real
 * confidence the record actually reached disk before the call returns -- `apply()` leaves a real
 * window (however short) where a process death right after a terminal event could lose the record
 * even though this method already returned "success". [persist] surfaces `commit()`'s own result
 * so a caller can at least log/observe the (rare) case where even the synchronous write itself
 * failed (e.g. disk I/O error) -- see [RecordingService.reportOutcome]. Residual limitation: this
 * still can't survive the OS killing the process in the middle of `commit()`'s own write syscall
 * (no software fsync can make a write instantaneous); that narrow window is accepted as an
 * unavoidable limit of on-device persistence, not something this class can close further.
 *
 * All entry points are synchronized on [lock] so overlapping calls (e.g. two near-simultaneous
 * `consumePendingOutcome()` calls) can't interleave a read with a concurrent clear/write and
 * either double-deliver the same outcome or observe a half-written record.
 */
internal class PendingOutcomeStore(
    context: Context,
    // Seam over the final commit step -- defaults to the real, synchronous
    // SharedPreferences.Editor.commit(). A test overrides this to force a "disk write failed"
    // result while still calling the real editor.commit() first, so the in-memory
    // SharedPreferences map is genuinely mutated exactly the way a real commit() failure would
    // leave it (SharedPreferencesImpl applies an edit to memory before attempting the disk write,
    // regardless of that write's outcome -- see ActiveSegmentJournal's class doc for the same
    // note in more detail). Production code must never have a reason to override this.
    private val commitEditor: (SharedPreferences.Editor) -> Boolean = { it.commit() }
) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val lock = Any()

    companion object {
        private const val TAG = "PendingOutcomeStore"
        private const val PREFS_NAME = "wav_recorder_pending_outcome"
        private const val KEY_SESSION_ID = "session_id"
        private const val KEY_CATEGORY = "category"
        private const val KEY_TARGET_TYPE = "target_type"
        private const val KEY_TARGET_PATH = "target_path"
        private const val KEY_TARGET_URI = "target_uri"
        private const val KEY_TARGET_NAME = "target_name"
        private const val KEY_MESSAGE = "message"
        private const val KEY_STARTED_AT = "started_at"

        private const val CATEGORY_SAVED = "SAVED"
        private const val CATEGORY_FAILED_BUT_SAVED = "FAILED_BUT_SAVED"
        private const val CATEGORY_FAILED = "FAILED"
        private const val CATEGORY_FINALIZATION_FAILED = "FINALIZATION_FAILED"
        private const val CATEGORY_FINALIZATION_UNKNOWN = "FINALIZATION_UNKNOWN"

        private const val TARGET_FILE = "FILE"
        private const val TARGET_SAF = "SAF"
    }

    /** Persists [outcome] as the single current pending record, tagged with [sessionId] (the
     * originating session's start time -- unique per session) so a later read can always be
     * traced back to exactly the session that produced it. This is a fixed-key record, not an
     * appended log: writing always fully replaces whatever was there before, so a still-unread
     * record from an older session can never linger once a newer session's own outcome is
     * written -- it's either consumed first, or overwritten. [safeMessage] must already be a
     * resolved, user-facing description; the original cause is never persisted. Returns whether
     * the underlying synchronous write actually succeeded (see class doc). */
    fun persist(sessionId: Long, outcome: RecordingOutcome, safeMessage: String?): Boolean = synchronized(lock) {
        val editor = prefs.edit()
            .putLong(KEY_SESSION_ID, sessionId)
            .putString(KEY_CATEGORY, categoryOf(outcome))
        writeTarget(editor, targetOf(outcome))
        if (safeMessage != null) editor.putString(KEY_MESSAGE, safeMessage) else editor.remove(KEY_MESSAGE)
        val startedAt = (outcome as? RecordingOutcome.Saved)?.startedAtMillis
        if (startedAt != null) editor.putLong(KEY_STARTED_AT, startedAt) else editor.remove(KEY_STARTED_AT)
        commitEditor(editor)
    }

    /** Returns and clears the persisted record, if any. The returned outcome is always the real,
     * correctly-decoded record -- a failure clearing it durably afterward doesn't change what's
     * handed back, since the value being returned right now is genuinely correct regardless. What
     * it *can't* promise is that this was the last time this exact record is ever returned: if the
     * durable clear fails, the same bytes are still on disk, and a later process restart (a fresh
     * [PendingOutcomeStore] reading the SharedPreferences file back from disk, not carrying over
     * this process's in-memory state) would read and redeliver it again. That failure is never
     * silent -- it's logged, including the originating session id, so it's traceable -- but this
     * method cannot itself close the gap: retrying the clear here is the only real mitigation
     * available, which is exactly what happens (see [clearLocked]). */
    fun consume(): RecordingOutcome? = synchronized(lock) {
        val category = prefs.getString(KEY_CATEGORY, null) ?: return@synchronized null
        val sessionId = peekSessionId()
        val target = readTarget()
        val message = prefs.getString(KEY_MESSAGE, null)
        val startedAt = if (prefs.contains(KEY_STARTED_AT)) prefs.getLong(KEY_STARTED_AT, 0L) else null
        if (!clearLocked()) {
            Log.w(TAG, "Failed to durably clear a consumed pending outcome (session id " +
                "$sessionId, category $category); if this process dies before a later write " +
                "clears it, the same outcome may be read and delivered again after a restart")
        }
        when (category) {
            CATEGORY_SAVED -> RecordingOutcome.Saved(target, startedAt)
            CATEGORY_FAILED_BUT_SAVED -> target?.let {
                RecordingOutcome.FailedButSaved(it, PersistedOutcomeException(message.orEmpty()))
            } ?: RecordingOutcome.Failed(PersistedOutcomeException(message.orEmpty()))
            CATEGORY_FAILED -> RecordingOutcome.Failed(PersistedOutcomeException(message.orEmpty()))
            CATEGORY_FINALIZATION_FAILED ->
                RecordingOutcome.FinalizationFailed(target, PersistedOutcomeException(message.orEmpty()))
            CATEGORY_FINALIZATION_UNKNOWN -> RecordingOutcome.FinalizationUnknown(target)
            else -> null
        }
    }

    /** The originating session id of the currently-persisted record, without consuming it --
     * purely for tests/diagnostics to confirm which session a record actually came from. */
    fun peekSessionId(): Long? = if (prefs.contains(KEY_SESSION_ID)) prefs.getLong(KEY_SESSION_ID, 0L) else null

    fun clear(): Boolean = synchronized(lock) { clearLocked() }

    /** A single immediate retry on top of the plain [SharedPreferences.Editor.commit]: clearing is
     * idempotent (retrying after a real success is a harmless no-op), and a synchronous commit()
     * failure is most often a transient disk hiccup that's already resolved a moment later -- worth
     * the small, bounded extra attempt given what's at stake (see [consume]'s doc). This does not
     * make the clear durably guaranteed; a caller must still check the final result. */
    private fun clearLocked(): Boolean = commitEditor(prefs.edit().clear()) || commitEditor(prefs.edit().clear())

    private fun categoryOf(outcome: RecordingOutcome): String = when (outcome) {
        is RecordingOutcome.Saved -> CATEGORY_SAVED
        is RecordingOutcome.FailedButSaved -> CATEGORY_FAILED_BUT_SAVED
        is RecordingOutcome.Failed -> CATEGORY_FAILED
        is RecordingOutcome.FinalizationFailed -> CATEGORY_FINALIZATION_FAILED
        is RecordingOutcome.FinalizationUnknown -> CATEGORY_FINALIZATION_UNKNOWN
    }

    private fun targetOf(outcome: RecordingOutcome): OutputTarget? = when (outcome) {
        is RecordingOutcome.Saved -> outcome.target
        is RecordingOutcome.FailedButSaved -> outcome.target
        is RecordingOutcome.Failed -> null
        is RecordingOutcome.FinalizationFailed -> outcome.target
        is RecordingOutcome.FinalizationUnknown -> outcome.target
    }

    private fun writeTarget(editor: SharedPreferences.Editor, target: OutputTarget?) {
        when (target) {
            is OutputTarget.FileTarget -> {
                editor.putString(KEY_TARGET_TYPE, TARGET_FILE)
                editor.putString(KEY_TARGET_PATH, target.file.absolutePath)
                editor.remove(KEY_TARGET_URI)
                editor.remove(KEY_TARGET_NAME)
            }
            is OutputTarget.SafTarget -> {
                editor.putString(KEY_TARGET_TYPE, TARGET_SAF)
                editor.putString(KEY_TARGET_URI, target.uri.toString())
                editor.putString(KEY_TARGET_NAME, target.name)
                editor.remove(KEY_TARGET_PATH)
            }
            null -> {
                editor.remove(KEY_TARGET_TYPE)
                editor.remove(KEY_TARGET_PATH)
                editor.remove(KEY_TARGET_URI)
                editor.remove(KEY_TARGET_NAME)
            }
        }
    }

    private fun readTarget(): OutputTarget? = when (prefs.getString(KEY_TARGET_TYPE, null)) {
        TARGET_FILE -> prefs.getString(KEY_TARGET_PATH, null)?.let { OutputTarget.FileTarget(File(it)) }
        TARGET_SAF -> {
            val uriString = prefs.getString(KEY_TARGET_URI, null)
            val name = prefs.getString(KEY_TARGET_NAME, null)
            if (uriString != null && name != null) OutputTarget.SafTarget(Uri.parse(uriString), name) else null
        }
        else -> null
    }
}
