package com.example.wavrecorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.core.app.NotificationCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Owns the actual [WavRecorder] and runs as a foreground service so recording survives the
 * screen turning off or the app being backgrounded. Android blocks microphone access from
 * background apps entirely since API 28 — a foreground service (with the "microphone" type
 * declared) is the only way around that, and it requires showing a persistent notification
 * for the duration, which is intentional: the user should always be able to see (and stop)
 * an in-progress recording, even from the lock screen.
 *
 * This is both a *started* service (survives all clients unbinding) and a *bound* service
 * (lets [RecordFragment] talk to it live while visible). It self-stops the moment recording
 * ends, rather than lingering.
 */
class RecordingService : Service() {

    companion object {
        private const val TAG = "RecordingService"
        private const val CHANNEL_ID = "recording_channel"
        // Separate from CHANNEL_ID/IMPORTANCE_LOW: a terminal outcome (especially a failure) is
        // meant to actually get the user's attention once they're no longer looking at the app,
        // which the silent, low-importance ongoing-recording channel is deliberately not.
        private const val RESULT_CHANNEL_ID = "recording_result_channel"
        private const val NOTIFICATION_ID = 1001
        private const val RESULT_NOTIFICATION_ID = 1002
        private const val ACTION_STOP = "com.example.wavrecorder.action.STOP"
        // internal (not private): RecordFragment sends these explicitly rather than a bare,
        // action-less Intent, so this service has its own authoritative record of "a
        // startForegroundService() call is outstanding and owes either a real startForeground()
        // or a safe stopSelf()" -- independent of whatever a bound Fragment's own local state
        // (which can be torn down, or race the connection) currently is. See onStartCommand().
        internal const val ACTION_START = "com.example.wavrecorder.action.START"
        internal const val ACTION_CANCEL_START = "com.example.wavrecorder.action.CANCEL_START"

        // Carries the unique id (see currentRequestId below) that correlates a single logical
        // start attempt across its three independent entry points: the Intent-dispatched
        // ACTION_START, a direct (already-bound) startRecording() call, and a later
        // ACTION_CANCEL_START. Attached by RecordFragment to every ACTION_START/ACTION_CANCEL_START
        // Intent it sends, and passed directly as startRecording()'s own parameter for the bound
        // path -- see RecordFragment.beginRecording().
        internal const val EXTRA_REQUEST_ID = "com.example.wavrecorder.extra.REQUEST_ID"

        // Upper bound on how long the temporary "preparing to record" foreground state entered by
        // ACTION_START (see handleActionStart) may sit unresolved -- neither fulfilled by a real
        // startRecording() call nor explicitly retracted via ACTION_CANCEL_START -- before this
        // service gives up and safely stops itself. This is what closes the gap a bound connection
        // that never arrives at all (e.g. the framework silently drops the bind) would otherwise
        // leave: an idle foreground service running indefinitely. Generous relative to how fast a
        // normal bind actually resolves (milliseconds), specifically so it never fires during any
        // real, if slow, connection. internal so tests can advance Robolectric's paused
        // main-looper clock past it deterministically (see RecordingServiceTest).
        internal const val PENDING_START_TIMEOUT_MS = 15_000L
    }

    interface Listener {
        fun onAmplitude(amplitude: Float)
        fun onSegmentStarted(target: OutputTarget, partNumber: Int)
        fun onError(e: Exception)
        fun onStopped(lastTarget: OutputTarget?)
        /** The input device recording actually started capturing from, known as soon as
         * [startRecording] returns. Default no-op so existing [Listener] implementations don't
         * have to handle this if they don't display it. */
        fun onMicrophoneInfo(info: MicrophoneInfo) {}
        /** The recording was stopped, but the final segment's WAV header/writer failed to close
         * cleanly -- [target] (if known) may be truncated or carry a stale header and should be
         * treated as needing manual recovery, not a normal successful save. Fired *instead of*
         * [onStopped], never both. */
        fun onFinalizationFailed(target: OutputTarget?, cause: Exception) {}
        /** The recording thread didn't finish within [WavRecorder]'s join timeout, so whether the
         * final segment actually finalized cleanly is genuinely unknown -- it may still be running
         * on its own in the background. [target] (if known) must be treated as unverified, not a
         * confirmed save: never report a plain "Saved" here. Fired *instead of* [onStopped],
         * never both. */
        fun onFinalizationUnknown(target: OutputTarget?) {}
    }

    inner class LocalBinder : Binder() {
        fun getService(): RecordingService = this@RecordingService
    }

    private val binder = LocalBinder()
    // Both settable (rather than a plain val) so tests can substitute a WavRecorder wired to a
    // fake AudioSource instead of the real microphone, and/or a DestinationManager that fails on
    // command (e.g. simulating a revoked SAF permission) without needing to reproduce that exact
    // OS-level condition.
    internal var recorder: WavRecorder = WavRecorder()
    internal lateinit var destinationManager: DestinationManager

    private var sessionTimestamp: String? = null
    private var sessionStartedAtMillis: Long? = null
    private val partCounter = AtomicInteger(0)
    private var currentTarget: OutputTarget? = null
    private var currentPartNumber = 1
    private var microphoneInfo: MicrophoneInfo? = null

    // True from an accepted ACTION_START (a startForegroundService() call already made) until
    // it's *fulfilled* -- startRecording() actually running and replacing the temporary
    // "preparing" foreground state with the real recording one -- or explicitly retracted via
    // ACTION_CANCEL_START, or the bounded pending-start timeout below gives up on it.
    // internal (not private) so tests can assert directly that a late/duplicate ACTION_START
    // never marks an already-active recording as pending, without needing an indirect signal.
    internal var startRequestPending = false
        private set

    // The request id (see EXTRA_REQUEST_ID) of the most recent start attempt this service has
    // accepted as authoritative -- set the moment either handleActionStart() or startRecording()
    // first sees it, and never reset back to null afterward, even once that attempt resolves
    // (fulfilled, canceled, timed out, or failed synchronously). It's a high-water mark, not a
    // "currently pending" flag: ids are assigned monotonically increasing by RecordFragment, so
    // "has this exact id (or an older one) already been handled?" is answered by a single
    // <= comparison against this value, regardless of how that handling concluded. This is what
    // lets a delayed ACTION_START for an attempt that already resolved (e.g. failed synchronously
    // before its own Intent was even dispatched -- see startRecording()'s doc) be recognized as
    // stale and ignored, while a genuinely new, higher id is still always accepted as a fresh
    // retry. internal (not private) so tests can assert it directly.
    internal var currentRequestId: Long? = null
        private set

    // Backs PENDING_START_TIMEOUT_MS. Each accepted ACTION_START posts its own closure capturing
    // its own request id (see handleActionStart()/handlePendingStartTimeout()) rather than a
    // single reused Runnable that would need to be proactively removed on every transition to stay
    // correct: instead, every stale delivery -- through any of the three entry points -- is made
    // safe purely by comparing against currentRequestId at the moment it actually runs, which is
    // what's under direct regression-test coverage (see RecordingServiceTest). A real
    // Handler/Looper (not an injected fake) so production behavior is exactly what's tested --
    // Robolectric's paused main-looper clock is what tests advance to exercise this
    // deterministically.
    private val pendingStartHandler = Handler(Looper.getMainLooper())

    // Durable (SharedPreferences-backed), not a plain in-memory field: a session that ends with
    // no listener attached -- app backgrounded, screen off, or the process killed outright before
    // it's ever reopened -- must survive this RecordingService *instance* being destroyed and a
    // later one being created fresh. A purely in-memory field would silently lose the outcome the
    // moment that happens, which is exactly the bug this replaced. internal for testing.
    internal lateinit var pendingOutcomeStore: PendingOutcomeStore

    var listener: Listener? = null

    val isRecording: Boolean get() = recorder.isActive
    val lastTarget: OutputTarget? get() = currentTarget
    val lastPartNumber: Int get() = currentPartNumber
    /** The input device the current (or most recently started) session verified itself to be
     * using; null once a session has been fully reset by a new [startRecording] call. Lets a
     * fragment that reconnects mid-recording (e.g. after rotation) resync its display without
     * waiting for a new session to start. */
    val lastMicrophoneInfo: MicrophoneInfo? get() = microphoneInfo
    /** Wall-clock time the current (or most recently started) session began, purely for showing a
     * friendly "recorded at / for this long" summary once it ends -- deliberately plain
     * [System.currentTimeMillis] bookkeeping, not part of the recording engine itself, kept here
     * (rather than recomputed from the fragment's own lifecycle) specifically so it survives the
     * fragment being recreated mid-recording (rotation, returning to the tab) the same way
     * [lastTarget] and [lastMicrophoneInfo] already do. Null once reset by a new [startRecording]
     * call, same lifecycle as the rest of this session's state. */
    val lastSessionStartedAtMillis: Long? get() = sessionStartedAtMillis

    /** Returns and clears the most recent terminal outcome that no listener was attached to see
     * live, if any -- called once a Fragment (re)binds so it can catch up, exactly once. Reads
     * through to durable storage, so this works identically whether the outcome was reported by
     * this exact service instance or a now-destroyed earlier one. */
    fun consumePendingOutcome(): RecordingOutcome? = pendingOutcomeStore.consume()

    override fun onCreate() {
        super.onCreate()
        destinationManager = DestinationManager(applicationContext)
        pendingOutcomeStore = PendingOutcomeStore(applicationContext)
        createNotificationChannels()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopRecording()
            ACTION_START -> handleActionStart(intent.getLongExtra(EXTRA_REQUEST_ID, -1L))
            ACTION_CANCEL_START -> handleActionCancelStart(intent.getLongExtra(EXTRA_REQUEST_ID, -1L))
        }
        return START_NOT_STICKY
    }

    /** Satisfies the foreground-service obligation directly from this call, rather than waiting on
     * a future [startRecording] call a not-yet-connected (or never-connecting) bound client may or
     * may not ever make: enters a temporary "preparing to record" foreground state immediately and
     * arms [PENDING_START_TIMEOUT_MS] as a backstop. [startRecording] transitions this into the
     * real recording state; [handleActionCancelStart] retracts it explicitly.
     *
     * [requestId] is what makes this safe against the two entry points (this Intent dispatch, and
     * a direct [startRecording] call through an already-live binder) arriving in either order, and
     * against Android redelivering or delaying this call arbitrarily: a delayed ACTION_START for
     * an id that's already been handled -- whether it was fulfilled, canceled, or already failed
     * synchronously via a direct [startRecording] call that raced ahead of this same Intent -- is
     * recognized as stale by comparing against [currentRequestId] and safely ignored, while a
     * genuinely new (strictly greater) id is always accepted as a fresh retry. */
    private fun handleActionStart(requestId: Long) {
        // A late ACTION_START can arrive *after* an already-bound Fragment called startRecording()
        // directly through its live binder reference -- Android dispatches a
        // startForegroundService() Intent to onStartCommand() on a separate path from a direct
        // Binder method call, with no guaranteed ordering between the two. Recording is already
        // the real, fulfilled state by then, so this must be a no-op: it must never downgrade an
        // active recording's notification back to "preparing", and must never arm a timeout that
        // could stop it later.
        if (recorder.isActive) return
        // Stale: either a duplicate delivery of the id that's already pending right now (must not
        // re-arm a second timeout on top of the first), or an id that's <= whatever this service
        // has already moved past -- including one whose direct startRecording() call already ran
        // and failed synchronously before this very Intent was dispatched (see startRecording()'s
        // doc). Only a strictly newer id represents a genuinely new attempt.
        val lastSeen = currentRequestId
        if (lastSeen != null && requestId <= lastSeen) return
        currentRequestId = requestId
        startRequestPending = true
        startForegroundCompat(buildPreparingNotification())
        pendingStartHandler.postDelayed({ handlePendingStartTimeout(requestId) }, PENDING_START_TIMEOUT_MS)
    }

    /** Only acts if [requestId] is still genuinely the current, unresolved request -- a stale
     * delayed firing for an id that's since been superseded by a newer one (or fulfilled, or
     * canceled) must never stop a session it no longer has anything to do with. */
    private fun handlePendingStartTimeout(requestId: Long) {
        if (currentRequestId == requestId && startRequestPending && !recorder.isActive) {
            startRequestPending = false
            finishRecording()
        }
    }

    private fun handleActionCancelStart(requestId: Long) {
        // Only ever retracts a request that both (a) is still merely *pending*, never a recording
        // that's already actively running, and (b) is the exact request this cancellation actually
        // names -- a stale cancellation for an id that's since been superseded by a newer pending
        // request must leave that newer one completely untouched.
        if (currentRequestId == requestId && startRequestPending && !recorder.isActive) {
            startRequestPending = false
            // Already promoted to the foreground (with the temporary "preparing" notification) by
            // handleActionStart() above -- this is the other half of that contract's prompt
            // stopForeground()/stopSelf() obligation.
            finishRecording()
        }
    }

    /** [requestId] correlates this call with whichever of ACTION_START/ACTION_CANCEL_START also
     * refer to the same logical attempt (see [EXTRA_REQUEST_ID]/[handleActionStart]). A direct,
     * already-bound call like this one is always authoritative for its own id regardless of
     * whatever [currentRequestId] currently holds: it's what lets a later, delayed ACTION_START
     * Intent for this exact id -- dispatched *before* this call ran but delivered *after* it
     * already resolved (including a synchronous failure inside [WavRecorder.start] below, which
     * calls back into `onError` and [finishRecording] before this method even returns) -- be
     * recognized by [handleActionStart] as already-handled and ignored, rather than mistakenly
     * re-entering the foreground for an attempt that's already over. */
    fun startRecording(requestId: Long) {
        if (recorder.isActive) return
        // This start is now being fulfilled -- an ACTION_CANCEL_START arriving after this point
        // must not retract it (see handleActionCancelStart()'s !recorder.isActive guard above),
        // and the bounded pending-start timeout must never fire for a session that's already
        // actually recording.
        currentRequestId = requestId
        startRequestPending = false
        // Reset every piece of session state up front, before recorder.start() below even runs.
        // Without this, a session that fails or is stopped before its first segment opens (so
        // onSegmentStarted never fires to overwrite currentTarget) would otherwise leave the
        // *previous* session's file sitting in currentTarget, and stopRecording() would then
        // report that old, already-finished file as if it were this session's newly saved result.
        currentTarget = null
        microphoneInfo = null
        val now = System.currentTimeMillis()
        sessionStartedAtMillis = now
        sessionTimestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(now))
        partCounter.set(0)
        currentPartNumber = 1

        startForegroundCompat(buildNotification(1))

        recorder.start(
            context = applicationContext,
            nextTarget = WavRecorder.NextTarget {
                val part = partCounter.incrementAndGet()
                val fileName = "recording_${sessionTimestamp}_part${String.format(Locale.US, "%02d", part)}.wav"
                destinationManager.createOutputFile(fileName)
            },
            onSegmentStarted = { target ->
                currentTarget = target
                currentPartNumber = partCounter.get()
                updateNotification(currentPartNumber)
                listener?.onSegmentStarted(target, currentPartNumber)
            },
            onAmplitude = { amplitude -> listener?.onAmplitude(amplitude) },
            onError = { e ->
                // The recording thread already flipped its internal flag to false before this
                // fires, which used to make WavRecorder.stop() a silent no-op and leak the mic
                // (AudioRecord never released) on any mid-recording failure. stop() is now safe
                // to call here unconditionally. Its FinalizeResult must take precedence over the
                // generic error `e`: a Failed/Unknown finalization means the file needs recovery
                // or verification, which is a stronger and more actionable claim than "recording
                // failed" alone, and must never be collapsed into a plain onError toast that
                // implies the file itself is fine.
                val target = currentTarget
                val outcome = when (val result = recorder.stop()) {
                    is WavRecorder.FinalizeResult.Failed ->
                        RecordingOutcome.FinalizationFailed(result.target ?: target, result.cause)
                    WavRecorder.FinalizeResult.Unknown -> RecordingOutcome.FinalizationUnknown(target)
                    WavRecorder.FinalizeResult.Ok -> RecordingOutcome.FailedButSaved(target, e)
                }
                reportOutcome(outcome)
                finishRecording()
            },
            onMicrophoneInfo = { info ->
                microphoneInfo = info
                listener?.onMicrophoneInfo(info)
            }
        )

        if (!recorder.isActive) {
            // AudioRecord setup failed synchronously inside recorder.start(); nothing to run.
            finishRecording()
        }
    }

    fun stopRecording() {
        if (!recorder.isActive) return
        val target = currentTarget
        // Exhaustive over the sealed FinalizeResult (no else branch) so a future case added there
        // can't silently fall through to a plain "Saved" outcome the way Unknown previously did
        // here -- it had been lumped into a catch-all `else -> onStopped(target)` alongside Ok.
        val outcome = when (val result = recorder.stop()) {
            is WavRecorder.FinalizeResult.Failed ->
                // result.target (from the recording thread itself) is preferred over the
                // locally-tracked currentTarget since it's the one that was actually being
                // finalized when this failed, but they're normally the same file.
                RecordingOutcome.FinalizationFailed(result.target ?: target, result.cause)
            WavRecorder.FinalizeResult.Unknown -> RecordingOutcome.FinalizationUnknown(target)
            WavRecorder.FinalizeResult.Ok -> RecordingOutcome.Saved(target, sessionStartedAtMillis)
        }
        reportOutcome(outcome)
        finishRecording()
    }

    /** Delivers [outcome] live if a Fragment is actually attached right now, exactly like before;
     * otherwise durably persists it (see [pendingOutcomeStore]) and raises a terminal notification
     * so an unattended failure -- or even just a save -- is never communicated by silently
     * removing the ongoing-recording notification and leaving no explanation. */
    private fun reportOutcome(outcome: RecordingOutcome) {
        val currentListener = listener
        if (currentListener != null) {
            // A still-unconsumed record from an *older* session (never picked up before this,
            // later, session finished with a live listener attached) must not be left sitting in
            // durable storage to be resurrected later and mistaken for describing this session.
            pendingOutcomeStore.clear()
            when (outcome) {
                is RecordingOutcome.Saved -> currentListener.onStopped(outcome.target)
                is RecordingOutcome.FailedButSaved -> currentListener.onError(outcome.cause)
                is RecordingOutcome.FinalizationFailed ->
                    currentListener.onFinalizationFailed(outcome.target, outcome.cause)
                is RecordingOutcome.FinalizationUnknown -> currentListener.onFinalizationUnknown(outcome.target)
            }
        } else {
            // sessionStartedAtMillis is always set by startRecording() before any outcome can
            // exist; the fallback only guards a value class default, never expected to be hit.
            val sessionId = sessionStartedAtMillis ?: System.currentTimeMillis()
            val persisted = pendingOutcomeStore.persist(sessionId, outcome, safeMessageFor(outcome))
            if (!persisted) {
                // The synchronous, durable write itself failed (e.g. disk I/O error) -- rare, and
                // there's no listener attached to inform right now anyway. The terminal
                // notification below is raised regardless, so it remains the user's fallback
                // signal even here; only the in-app "catch up on reopen" path is affected.
                Log.w(TAG, "Failed to durably persist terminal recording outcome ($outcome); " +
                    "only the notification will inform the user if the process doesn't survive")
            }
            showTerminalOutcomeNotification(outcome)
        }
    }

    /** The safe, already-resolved user-facing text for [outcome]'s cause, if any -- this (never
     * the raw [Exception]) is what gets durably persisted; see [PendingOutcomeStore]. */
    private fun safeMessageFor(outcome: RecordingOutcome): String? = when (outcome) {
        is RecordingOutcome.Saved -> null
        is RecordingOutcome.FailedButSaved -> errorMessageFor(outcome.cause)
        is RecordingOutcome.FinalizationFailed -> outcome.cause.message
        is RecordingOutcome.FinalizationUnknown -> null
    }

    private fun finishRecording() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // App swiped away from Recents while recording: save what we have rather than leaving
        // a foreground service running with no UI left able to stop it.
        if (recorder.isActive) stopRecording()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        // Safe even though individual timeout closures are never removed on the ordinary
        // transitions above (see pendingStartHandler's doc) -- this Handler is used for nothing
        // else, so clearing everything unconditionally at actual teardown can't drop something
        // unrelated, and avoids a stale closure referencing a destroyed service instance later.
        pendingStartHandler.removeCallbacksAndMessages(null)
        if (recorder.isActive) recorder.stop()
        super.onDestroy()
    }

    // Lint's NewApi check can't trace an API guard through an arbitrary function call (like
    // foregroundStartModeFor()'s enum result) back to a literal SDK_INT comparison -- it only
    // recognizes @ChecksSdkIntAtLeast as an explicit signal that a helper *is* that comparison,
    // so this thin boolean wrapper exists purely to keep foregroundStartModeFor() as the one
    // real (and independently unit-tested) source of truth while still satisfying lint.
    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.R)
    private fun supportsMicrophoneForegroundType(): Boolean =
        foregroundStartModeFor(Build.VERSION.SDK_INT) == ForegroundStartMode.MICROPHONE_TYPE

    private fun startForegroundCompat(notification: Notification) {
        if (supportsMicrophoneForegroundType()) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
            // Deliberately DEFAULT (not LOW like the silent, ongoing recording channel above): a
            // terminal outcome -- especially a failure -- is exactly the kind of thing a user who
            // isn't currently looking at the app should actually be alerted to, not have arrive as
            // silently as the persistent "recording in progress" notification does.
            manager.createNotificationChannel(
                NotificationChannel(
                    RESULT_CHANNEL_ID,
                    getString(R.string.notification_result_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT
                )
            )
        }
    }

    private fun errorMessageFor(e: Exception): String = when (e) {
        is MicrophoneDisconnectedException -> getString(R.string.mic_disconnected_error)
        is MicrophoneRouteChangedException -> getString(R.string.mic_route_changed_error)
        else -> getString(R.string.recording_error, e.message)
    }

    private fun outcomeNotificationContent(outcome: RecordingOutcome): Pair<String, String> = when (outcome) {
        is RecordingOutcome.Saved -> getString(R.string.notification_result_saved_title) to
            (outcome.target?.let { getString(R.string.notification_result_saved_text, it.displayPath) }
                ?: getString(R.string.notification_result_saved_text_unknown_location))
        is RecordingOutcome.FailedButSaved ->
            getString(R.string.notification_result_error_title) to errorMessageFor(outcome.cause)
        is RecordingOutcome.FinalizationFailed -> getString(R.string.notification_result_needs_recovery_title) to
            (outcome.target?.let {
                getString(R.string.recording_needs_recovery, it.displayPath, outcome.cause.message ?: "")
            } ?: getString(R.string.recording_error, outcome.cause.message))
        is RecordingOutcome.FinalizationUnknown ->
            getString(R.string.notification_result_needs_verification_title) to
                (outcome.target?.let { getString(R.string.recording_needs_verification, it.displayPath) }
                    ?: getString(R.string.recording_needs_verification_unknown_location))
    }

    /** Raised in place of the just-removed ongoing-recording notification whenever a session ends
     * with no Fragment attached to show the result live -- an ordinary (non-ongoing, dismissible)
     * notification, on its own channel/id so it survives independently of the recording
     * notification's own lifecycle and never contends with a later recording's notification. */
    private fun showTerminalOutcomeNotification(outcome: RecordingOutcome) {
        val (title, text) = outcomeNotificationContent(outcome)
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, RESULT_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_notification_mic)
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(contentPendingIntent)
            .build()
        getSystemService(NotificationManager::class.java).notify(RESULT_NOTIFICATION_ID, notification)
    }

    /** Shown only for the brief, bounded window between an accepted ACTION_START and it either
     * being fulfilled by a real [startRecording] call or explicitly retracted -- see
     * [handleActionStart]. Deliberately has no Stop action (unlike [buildNotification]): nothing
     * is actually recording yet, so there's nothing meaningful to stop. */
    private fun buildPreparingNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.status_preparing))
            .setSmallIcon(R.drawable.ic_notification_mic)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentPendingIntent)
            .build()
    }

    private fun buildNotification(part: Int): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, RecordingService::class.java).setAction(ACTION_STOP)
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val text = if (part > 1) getString(R.string.status_recording_part, part) else getString(R.string.status_recording)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification_mic)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentPendingIntent)
            .addAction(0, getString(R.string.stop_recording), stopPendingIntent)
            .build()
    }

    private fun updateNotification(part: Int) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(part))
    }
}

/** Which [Service.startForeground] overload is safe to call at a given API level. Pulled out as a
 * pure function (rather than inlined in [RecordingService.startForegroundCompat]) so the exact
 * API-level boundary can be unit tested directly, without depending on what a Robolectric shadow
 * does or doesn't validate about the (real, OS-enforced) foreground service type system. */
internal enum class ForegroundStartMode { TWO_ARG, MICROPHONE_TYPE }

/** [ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE] was only added in API 30 (R) -- passing it to
 * `startForeground()` on API 29, where the 3-arg overload already exists but that specific type
 * constant doesn't, risks the OS rejecting an unrecognized foreground service type. API 29 (and
 * everything older) must keep using the plain 2-arg call, exactly like pre-Q behavior. */
internal fun foregroundStartModeFor(sdkInt: Int): ForegroundStartMode =
    if (sdkInt >= Build.VERSION_CODES.R) ForegroundStartMode.MICROPHONE_TYPE else ForegroundStartMode.TWO_ARG
