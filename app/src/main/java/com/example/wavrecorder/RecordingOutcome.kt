package com.example.wavrecorder

import android.content.Context

/**
 * The terminal result of a recording session, computed once [RecordingService] knows how it
 * ended -- the single model both the live [RecordingService.Listener] callbacks and the
 * no-listener-attached terminal notification are built from, so a session backgrounded mid-error
 * is reported exactly the same way a foregrounded one would be, never silently.
 */
sealed class RecordingOutcome {
    /** The recording stopped normally (or was never really recording) and its file, if any,
     * finalized cleanly. [target] is the last file that holds audio -- null when no audio was
     * saved: the session ended before its first segment ever opened, or before any audio reached
     * it (a file that received none is deleted). [startedAtMillis] is captured at
     * outcome-creation time (not re-read later) so a Fragment that only catches up to this
     * outcome well after the fact still shows the true session start time -- for the date only. [recordedMillis] is how much audio the session
     * actually wrote, across all of its files (counted from written frames, not wall-clock time),
     * so a summary shown long after the stop still states the real duration; null when unknown
     * (e.g. an outcome persisted by an earlier app version), in which case no duration is shown. */
    data class Saved(
        val target: OutputTarget?,
        val startedAtMillis: Long?,
        val recordedMillis: Long? = null
    ) : RecordingOutcome()

    /** Recording failed (mic disconnected, route changed, an I/O error, ...), but the audio up to
     * the point of the failure was saved, and every file finalized cleanly. [target] is the last
     * file that holds audio, and always non-null here -- a failure before any audio was saved is
     * [Failed], never this, so this is never mistaken for "a file exists but no indication it's
     * actually usable." */
    data class FailedButSaved(val target: OutputTarget, val cause: Exception) : RecordingOutcome()

    /** Recording failed before any audio was saved -- before any segment was ever opened, or
     * before any audio reached the one that was (a [NothingSavedException] or
     * [RecordingNotSavedException] cause) -- so there is no file, saved or otherwise, to point to
     * at all. Distinct from [FailedButSaved] specifically so such a failure (e.g. the microphone
     * was busy) is never classified as if some file had been saved. */
    data class Failed(val cause: Exception) : RecordingOutcome()

    /** The last segment's header patch or writer close failed; [target] (if known) may be
     * truncated or carry a stale header and should be treated as needing recovery. */
    data class FinalizationFailed(val target: OutputTarget?, val cause: Exception) : RecordingOutcome()

    /** Whether the last segment actually finalized cleanly is genuinely unknown -- the recording
     * thread never joined in time. [target] (if known) must be treated as unverified. */
    data class FinalizationUnknown(val target: OutputTarget?) : RecordingOutcome()
}

/** The session ended, for [reason], after its first file was started but before any audio
 * reached it -- so nothing was saved (the empty file is deleted). Wraps a reason whose own message
 * would otherwise say the recording was saved up to that point; see [nothingSavedMessage]. */
class NothingSavedException(val reason: Exception) :
    Exception("Recording ended before any audio was saved: ${reason.message}", reason)

/** What to tell the user about [e]: why the recording ended, and that nothing was saved. */
internal fun nothingSavedMessage(context: Context, e: NothingSavedException): String = when (val reason = e.reason) {
    is MicrophoneDisconnectedException -> context.getString(R.string.mic_disconnected_nothing_saved)
    is MicrophoneRouteChangedException -> context.getString(R.string.mic_route_changed_nothing_saved)
    is MicrophoneStalledException -> context.getString(R.string.mic_stalled_nothing_saved)
    is StorageTooSlowException -> context.getString(R.string.storage_too_slow_nothing_saved)
    else -> context.getString(R.string.recording_error_nothing_saved, reason.message)
}
