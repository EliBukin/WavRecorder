package com.example.wavrecorder

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
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
internal class PendingOutcomeStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val lock = Any()

    companion object {
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
        editor.commit()
    }

    /** Returns and clears the persisted record, if any -- consumed exactly once: a second call
     * with nothing new persisted in between returns null. Atomic with respect to [persist]/[clear]
     * (see class doc): a concurrent call can never observe this record half-cleared. */
    fun consume(): RecordingOutcome? = synchronized(lock) {
        val category = prefs.getString(KEY_CATEGORY, null) ?: return@synchronized null
        val target = readTarget()
        val message = prefs.getString(KEY_MESSAGE, null)
        val startedAt = if (prefs.contains(KEY_STARTED_AT)) prefs.getLong(KEY_STARTED_AT, 0L) else null
        clearLocked()
        when (category) {
            CATEGORY_SAVED -> RecordingOutcome.Saved(target, startedAt)
            CATEGORY_FAILED_BUT_SAVED ->
                RecordingOutcome.FailedButSaved(target, PersistedOutcomeException(message.orEmpty()))
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

    private fun clearLocked(): Boolean = prefs.edit().clear().commit()

    private fun categoryOf(outcome: RecordingOutcome): String = when (outcome) {
        is RecordingOutcome.Saved -> CATEGORY_SAVED
        is RecordingOutcome.FailedButSaved -> CATEGORY_FAILED_BUT_SAVED
        is RecordingOutcome.FinalizationFailed -> CATEGORY_FINALIZATION_FAILED
        is RecordingOutcome.FinalizationUnknown -> CATEGORY_FINALIZATION_UNKNOWN
    }

    private fun targetOf(outcome: RecordingOutcome): OutputTarget? = when (outcome) {
        is RecordingOutcome.Saved -> outcome.target
        is RecordingOutcome.FailedButSaved -> outcome.target
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
