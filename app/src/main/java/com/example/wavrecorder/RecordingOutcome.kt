package com.example.wavrecorder

/**
 * The terminal result of a recording session, computed once [RecordingService] knows how it
 * ended -- the single model both the live [RecordingService.Listener] callbacks and the
 * no-listener-attached terminal notification are built from, so a session backgrounded mid-error
 * is reported exactly the same way a foregrounded one would be, never silently.
 */
sealed class RecordingOutcome {
    /** The recording stopped normally (or was never really recording) and its file, if any,
     * finalized cleanly. [target] is null only when the session ended before its first segment
     * ever opened. [startedAtMillis] is captured at outcome-creation time (not re-read later) so a
     * Fragment that only catches up to this outcome well after the fact still shows the true
     * session start time. */
    data class Saved(val target: OutputTarget?, val startedAtMillis: Long?) : RecordingOutcome()

    /** Recording failed (mic disconnected, route changed, an I/O error, ...), but the last
     * segment's header/writer still finalized cleanly -- the file is safe to use up to the point
     * of the failure. */
    data class FailedButSaved(val target: OutputTarget?, val cause: Exception) : RecordingOutcome()

    /** The last segment's header patch or writer close failed; [target] (if known) may be
     * truncated or carry a stale header and should be treated as needing recovery. */
    data class FinalizationFailed(val target: OutputTarget?, val cause: Exception) : RecordingOutcome()

    /** Whether the last segment actually finalized cleanly is genuinely unknown -- the recording
     * thread never joined in time. [target] (if known) must be treated as unverified. */
    data class FinalizationUnknown(val target: OutputTarget?) : RecordingOutcome()
}
