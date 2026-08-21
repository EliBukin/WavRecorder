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
        private const val NOTIFICATION_ID = 1001
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
        /** Finalization has begun -- a Stop was requested, or a fatal error triggered one -- and
         * is now running in the background; capture has already stopped, but the final segment's
         * header/writer close hasn't completed yet (it can take up to a couple of seconds; see
         * [WavRecorder]'s join timeout). Fired at most once per session, always before exactly
         * one of [onStopped]/[onError]/[onFinalizationFailed]/[onFinalizationUnknown]. Default
         * no-op so existing implementations don't have to handle it. */
        fun onStopping() {}
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
        /** A start attempt (either entry point) was rejected because a previous session's
         * finalization is still in progress -- see [ServiceState]. The UI should surface this
         * rather than silently doing nothing; default no-op so existing implementations aren't
         * forced to handle it. */
        fun onStartRejected() {}
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
    // Settable (like recorder/destinationManager above) so tests can substitute a manager that
    // reports a scripted outcome without needing a real interrupted file on disk.
    internal var recoveryManager: WavRecoveryManager = WavRecoveryManager()

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

    // Delivers a stop/finalization outcome back to the main thread once the background join in
    // beginAsyncFinalize() completes -- a separate Handler instance from pendingStartHandler
    // purely for clarity (the two serve unrelated concerns), both backed by the same real main
    // looper.
    private val mainHandler = Handler(Looper.getMainLooper())

    // The request id (see currentRequestId) of the session whose finalization is currently
    // running in the background, or null if none. This is what backs ServiceState.FINALIZING (see
    // that enum's own doc for the single-session invariant this service enforces overall) --
    // session-scoped, not a single shared flag, so that beginAsyncFinalize() can tell a true
    // duplicate call for the *same* session (e.g. a rapid double Stop tap landing before
    // WavRecorder.requestStop() has flipped recorder.isActive) apart from a call for a different
    // session, which by construction can't happen while this is non-null: both of this service's
    // own start entry points (startRecording()/handleActionStart()) reject a new attempt outright
    // while state == FINALIZING, before ever touching currentRequestId. Written only from the main
    // thread (both callers of beginAsyncFinalize -- stopRecording() and the onError callback wired
    // up in startRecording() -- always run there), so a plain var is safe; no additional locking
    // needed. internal (not private) so tests can poll [isFinalizing] instead of guessing how long
    // the background join might take.
    internal val isFinalizing: Boolean get() = finalizingRequestId != null
    private var finalizingRequestId: Long? = null

    // A single, derived (not separately mutated) view of this service's session lifecycle --
    // computed, rather than tracked as its own field, specifically so it can never drift out of
    // sync with the individual flags it's built from. FINALIZING is checked before RECORDING
    // because requestStop() (called synchronously at the start of beginAsyncFinalize()) has
    // already flipped recorder.isActive false by the time isFinalizing becomes true; without this
    // ordering a still-finalizing session would misreport as IDLE. See ServiceState's own doc for
    // why serializing on this (rather than on recorder.isActive alone, as before) is what actually
    // closes the "session 2 starts while session 1 is still finalizing" race -- session 1's
    // requestStop() already makes recorder.isActive false well before its background join (and
    // thus its own outcome/journal ownership) is actually settled.
    internal val state: ServiceState
        get() = when {
            isFinalizing -> ServiceState.FINALIZING
            recorder.isActive -> ServiceState.RECORDING
            startRequestPending -> ServiceState.PREPARING
            else -> ServiceState.IDLE
        }

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
        // Synchronous and first: atomically claims whatever segment record survived from a
        // previous, *different* process (if any) as a new recovery candidate appended to the
        // durable queue, *before* anything else in this service's startup can possibly lead to a
        // new segment being opened (that always happens later, from a bound Fragment's or
        // ACTION_START's own call, never from onCreate() itself) -- see
        // WavRecoveryManager.claimPreviousSession()'s doc for why this ordering is what actually
        // closes the live-segment-vs-recovery race. Cheap and bounded (a handful of
        // SharedPreferences keys), unlike the actual recovery file I/O below.
        //
        // Every non-Claimed result is logged honestly rather than silently treated as "nothing to
        // recover": Empty is the ordinary, expected case (a clean previous exit, or first launch)
        // and isn't logged; OwnedByThisProcess means this Service instance was recreated while a
        // previous instance's own recording thread is still finishing its own non-blocking
        // teardown in this same process -- not a crash, nothing to claim yet; Failed means a
        // genuine foreign-process record exists but the durable move into the recovery queue
        // itself failed, which is worth knowing about even though the record was left safely in
        // place (see ActiveSegmentJournal.ClaimResult).
        when (val claimResult = recoveryManager.claimPreviousSession(applicationContext)) {
            is ActiveSegmentJournal.ClaimResult.Failed -> Log.w(
                TAG, "Could not durably move a previous process's active-segment record " +
                    "(${claimResult.record.target.displayPath}) into the recovery queue; it " +
                    "remains in place and will be retried at the next launch"
            )
            ActiveSegmentJournal.ClaimResult.OwnedByThisProcess -> Log.i(
                TAG, "This service instance was recreated while a previous instance's recording " +
                    "thread was still finishing its own teardown in this same process; its " +
                    "active-segment record was left untouched, not claimed for recovery"
            )
            else -> Unit
        }
        runStartupRecoveryCheck()
    }

    /**
     * One-shot, best-effort check for a segment an abrupt *previous* process death left mid-write
     * -- see [WavRecoveryManager]/[ActiveSegmentJournal]. Only ever operates on the recovery
     * candidate already claimed synchronously above, never on a live ACTIVE record. Deliberately
     * does real file I/O (open/seek/read/write) off the main thread, and this is a fire-and-forget
     * background check: nothing in this service's own startup depends on its result, so a slow or
     * failing recovery attempt must never delay `onCreate()` (which callers, including the OS
     * itself, expect back quickly) or any recording the user starts in the meantime -- recovering
     * an old, already-shut file competes with nothing a fresh recording needs.
     */
    private fun runStartupRecoveryCheck() {
        val appContext = applicationContext
        val manager = recoveryManager
        Thread({
            // Every currently-queued candidate is attempted independently -- one candidate
            // failing to recover never blocks or affects a different, independent candidate; see
            // WavRecoveryManager.recoverIfNeeded's doc.
            manager.recoverIfNeeded(appContext).forEach { outcome ->
                when (outcome) {
                    is RecoveryOutcome.Recovered -> Log.i(
                        TAG, "Recovered an interrupted recording from a previous session: " +
                            "${outcome.target.displayPath} (${outcome.recoveredAudioBytes} audio bytes)"
                    )
                    is RecoveryOutcome.VerifiedButNotCleared -> Log.w(
                        TAG, "Recovered and verified an interrupted recording " +
                            "(${outcome.target.displayPath}, ${outcome.recoveredAudioBytes} audio " +
                            "bytes), but could not durably remove it from the recovery queue -- " +
                            "the file itself is fine; it will be harmlessly re-verified at a later launch"
                    )
                    is RecoveryOutcome.Failed -> Log.w(
                        TAG, "Could not recover an interrupted recording from a previous session " +
                            "(${outcome.target.displayPath}): ${outcome.reason}. " +
                            when (outcome.mutation) {
                                RecoveryOutcome.Mutation.UNTOUCHED -> "The original file was left untouched."
                                RecoveryOutcome.Mutation.MAYBE_PARTIAL -> "A header write was " +
                                    "attempted and may have partially landed -- the file was NOT " +
                                    "confirmed left untouched."
                                RecoveryOutcome.Mutation.WRITTEN_UNVERIFIED -> "A header patch was " +
                                    "written and flushed to storage but could not be verified -- " +
                                    "the file was NOT left untouched."
                                RecoveryOutcome.Mutation.WRITTEN_VERIFIED -> "A header patch was " +
                                    "written, flushed to storage, and verified byte-correct -- the " +
                                    "file's contents are confirmed fine; only a cleanup step " +
                                    "afterward failed, so this will be harmlessly re-verified at a " +
                                    "later launch."
                            }
                    )
                    RecoveryOutcome.NothingToRecover -> Unit
                }
            }
        }, "WavRecoveryThread").start()
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
        if (state == ServiceState.RECORDING) return
        // A previous session is still finalizing in the background -- this must be rejected, not
        // silently ignored *or* accepted: accepting it would overwrite currentRequestId, which the
        // still-in-flight beginAsyncFinalize() background thread rechecks to decide whether its
        // own outcome is still current, discarding that session's real terminal result as "stale".
        // See ServiceState's doc. The service is already legitimately in the foreground for the
        // still-finalizing session, so there's no startForeground()/stopSelf() obligation to
        // satisfy or retract here -- just reject and give explicit feedback if a listener happens
        // to be attached.
        if (state == ServiceState.FINALIZING) {
            listener?.onStartRejected()
            return
        }
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
        if (state == ServiceState.RECORDING) return
        // A previous session's finalization is still running in the background -- see
        // ServiceState's doc and handleActionStart()'s identical guard above. Rejecting here
        // (rather than proceeding, as before) is what actually prevents a second, competing
        // WavRecorder session: recorder.isActive is already false by this point in the still-
        // finalizing session (requestStop() flips it synchronously), so without this explicit
        // state check this call would otherwise fall through and start capturing audio while the
        // older session's own background join, outcome delivery, and journal-record ownership are
        // all still unresolved.
        if (state == ServiceState.FINALIZING) {
            listener?.onStartRejected()
            return
        }
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
                // (AudioRecord never released) on any mid-recording failure. Finalizing is now
                // safe to trigger here unconditionally. Its FinalizeResult must take precedence
                // over the generic error `e`: a Failed/Unknown finalization means the file needs
                // recovery or verification, which is a stronger and more actionable claim than
                // "recording failed" alone, and must never be collapsed into a plain onError
                // toast that implies the file itself is fine -- see beginAsyncFinalize().
                beginAsyncFinalize(genericError = e)
            },
            onMicrophoneInfo = { info ->
                microphoneInfo = info
                listener?.onMicrophoneInfo(info)
            }
        )

        // Deliberately no `if (!recorder.isActive) finishRecording()` here anymore: every path
        // that can leave recorder.isActive false at this point (AudioRecord setup failing
        // synchronously inside recorder.start() above) already called `onError` -- synchronously,
        // before recorder.start() returned -- which triggers beginAsyncFinalize() and, through
        // it, exactly one eventual finishRecording() call once the (here, trivially fast, since
        // there's no thread to join) async completion runs. A second, synchronous call here used
        // to race that: it tore down the foreground notification and called stopSelf() *before*
        // the outcome was even computed, then beginAsyncFinalize's own completion did it again.
    }

    fun stopRecording() {
        if (!recorder.isActive) return
        beginAsyncFinalize(genericError = null)
    }

    /**
     * Shared stop/finalization path for both a user-initiated Stop ([stopRecording]) and a fatal
     * mid-recording error (the `onError` callback wired up in [startRecording]). Returns promptly
     * without waiting for the recording thread to actually finish: [WavRecorder.requestStop] (the
     * fast, non-blocking part -- flip isRecording false, release the mic) runs synchronously
     * right here, but the up-to-[WavRecorder]-join-timeout wait for the recording thread itself
     * ([WavRecorder.awaitFinalization]) runs on a background thread instead, so neither caller
     * (typically the main thread, e.g. a button tap or the recording thread's own posted error)
     * is ever blocked for that long -- a blocked driver or SAF write must not be able to stall
     * the app's main thread just because the user tapped Stop.
     *
     * [finalizingRequestId] guards only against a true duplicate call for the *same* session (e.g.
     * a rapid double Stop tap landing before [WavRecorder.requestStop] below has flipped
     * `recorder.isActive`) -- see that field's own doc. [currentRequestId] is snapshotted up front
     * and re-checked once the background join completes purely as defense-in-depth: under every
     * current production entry point a *different* session's [currentRequestId] genuinely cannot
     * change while this one is finalizing (both [startRecording] and [handleActionStart] reject a
     * new attempt outright while `state == FINALIZING`, before ever touching it), so this check is
     * not expected to actually trigger today. It's kept so that if that gate is ever weakened, or
     * a future entry point is added that bypasses it, a stale outcome still can't report a result
     * for -- or tear down the foreground/self-stop state out from under -- a session it has
     * nothing to do with, rather than silently corrupting one.
     *
     * [genericError], when non-null, is the mid-recording failure that triggered this. If the
     * segment still finalized cleanly despite it, the outcome is "failed, but the file up to that
     * point is saved" ([RecordingOutcome.FailedButSaved]) when a segment actually existed, or
     * plain [RecordingOutcome.Failed] when the failure happened before any segment was ever
     * opened (e.g. the microphone itself never opened) -- never a claim that something was
     * "saved" when there is no file at all. `null` for a normal user-requested stop.
     */
    private fun beginAsyncFinalize(genericError: Exception?) {
        val stoppedRequestId = currentRequestId
        if (finalizingRequestId == stoppedRequestId) return
        finalizingRequestId = stoppedRequestId
        val target = currentTarget
        val startedAt = sessionStartedAtMillis
        listener?.onStopping()
        val pendingThread = recorder.requestStop()
        showFinalizingNotification()
        Thread({
            val result = recorder.awaitFinalization(pendingThread)
            mainHandler.post {
                if (finalizingRequestId == stoppedRequestId) finalizingRequestId = null
                if (currentRequestId != stoppedRequestId) {
                    // Defense-in-depth, not a normally-reachable path -- see beginAsyncFinalize()'s
                    // doc above for why currentRequestId can't actually change while this session
                    // was finalizing under any current entry point.
                    return@post
                }
                // Exhaustive over the sealed FinalizeResult (no else branch) so a future case
                // added there can't silently fall through to a plain "Saved" outcome the way
                // Unknown previously did here -- it had been lumped into a catch-all
                // `else -> onStopped(target)` alongside Ok.
                val outcome = when (result) {
                    is WavRecorder.FinalizeResult.Failed ->
                        // result.target (from the recording thread itself) is preferred over the
                        // locally-tracked currentTarget since it's the one that was actually
                        // being finalized when this failed, but they're normally the same file.
                        RecordingOutcome.FinalizationFailed(result.target ?: target, result.cause)
                    WavRecorder.FinalizeResult.Unknown -> RecordingOutcome.FinalizationUnknown(target)
                    WavRecorder.FinalizeResult.Ok -> when {
                        genericError != null && target != null -> RecordingOutcome.FailedButSaved(target, genericError)
                        genericError != null -> RecordingOutcome.Failed(genericError)
                        else -> RecordingOutcome.Saved(target, startedAt)
                    }
                }
                reportOutcome(outcome)
                finishRecording()
            }
        }, "RecordingFinalizeThread").start()
    }

    /** Delivers [outcome] live if a Fragment is actually attached right now, exactly like before;
     * otherwise durably persists it (see [pendingOutcomeStore]) so it's still available in-app the
     * next time the user opens WavRecorder -- see [consumePendingOutcome]. Deliberately raises no
     * Android notification or launcher badge for an unattended outcome (the app used to; see
     * [LegacyNotificationCleanup] for why that was removed and how the old one is retracted). */
    private fun reportOutcome(outcome: RecordingOutcome) {
        val currentListener = listener
        if (currentListener != null) {
            // A still-unconsumed record from an *older* session (never picked up before this,
            // later, session finished with a live listener attached) must not be left sitting in
            // durable storage to be resurrected later and mistaken for describing this session.
            // This session's own outcome is always delivered live below regardless of this
            // result -- a failed clear only risks a *stale, older* record reappearing after a
            // future process restart (see PendingOutcomeStore.consume()'s doc), never affects
            // what's reported right now. The session id is read *before* clearing: clear()
            // applies to the in-memory record immediately regardless of its own reported result
            // (see ActiveSegmentJournal's class doc for the same SharedPreferences semantics), so
            // reading it after would always see it already gone.
            val staleSessionId = pendingOutcomeStore.peekSessionId()
            if (!pendingOutcomeStore.clear()) {
                Log.w(TAG, "Failed to durably clear a still-pending older outcome (session id " +
                    "$staleSessionId) before delivering this session's own outcome live; the " +
                    "older record may reappear after a future process restart")
            }
            when (outcome) {
                is RecordingOutcome.Saved -> currentListener.onStopped(outcome.target)
                is RecordingOutcome.FailedButSaved -> currentListener.onError(outcome.cause)
                is RecordingOutcome.Failed -> currentListener.onError(outcome.cause)
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
                // there's no listener attached to inform right now anyway, and (deliberately) no
                // notification either -- see this method's own doc. If the process doesn't
                // survive, this outcome is simply lost; logged so it's at least traceable.
                Log.w(TAG, "Failed to durably persist terminal recording outcome ($outcome); " +
                    "the user will not be informed if the process doesn't survive")
            }
        }
    }

    /** The safe, already-resolved user-facing text for [outcome]'s cause, if any -- this (never
     * the raw [Exception]) is what gets durably persisted; see [PendingOutcomeStore]. */
    private fun safeMessageFor(outcome: RecordingOutcome): String? = when (outcome) {
        is RecordingOutcome.Saved -> null
        is RecordingOutcome.FailedButSaved -> errorMessageFor(outcome.cause)
        is RecordingOutcome.Failed -> errorMessageFor(outcome.cause)
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
        // requestStop(), never the blocking stop()/awaitFinalization(): onDestroy() runs on the
        // main thread, and the OS expects it back quickly -- joining the recording thread for up
        // to WavRecorder's own timeout here would be exactly the main-thread stall this whole
        // patch exists to remove, just moved from stopRecording() to teardown instead.
        //
        // This deliberately does not (and cannot) wait to learn or report the final outcome: the
        // recording thread's own finally block (WavRecorder.recordLoop) keeps running
        // independently on its own thread regardless of whether this Service instance is even
        // still around, and still correctly patches the file and clears its ActiveSegmentJournal
        // record once it actually finishes -- Android doesn't kill a live non-daemon thread just
        // because a Service's onDestroy() returns. If the process is killed outright before that
        // thread finishes, the journal record survives and WavRecoveryManager repairs the file on
        // next launch, exactly like any other abrupt-death case. Nothing here ever claims the file
        // was verified/"saved" -- this path never calls reportOutcome() at all, live or durable.
        if (recorder.isActive) recorder.requestStop()
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
        }
    }

    private fun errorMessageFor(e: Exception): String = when (e) {
        is MicrophoneDisconnectedException -> getString(R.string.mic_disconnected_error)
        is MicrophoneRouteChangedException -> getString(R.string.mic_route_changed_error)
        else -> getString(R.string.recording_error, e.message)
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

    /** Shown for the brief, bounded window between a Stop being requested and the background
     * finalization join in [beginAsyncFinalize] actually completing -- keeps the foreground
     * notification honest (and, per Android's foreground-service contract, still present) rather
     * than either leaving the stale "Recording…" text up or removing it before the file is
     * actually confirmed saved. Deliberately has no Stop action: nothing left to stop. */
    private fun buildFinalizingNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.status_finalizing))
            .setSmallIcon(R.drawable.ic_notification_mic)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentPendingIntent)
            .build()
    }

    private fun showFinalizingNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildFinalizingNotification())
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

/**
 * [RecordingService]'s serialized session lifecycle. Exactly one session (pending, active, or
 * still finalizing in the background) may occupy this service at a time -- see
 * [RecordingService.state]/[RecordingService.startRecording]/[RecordingService.handleActionStart]
 * for how a new start attempt arriving during FINALIZING is rejected (with explicit feedback via
 * [RecordingService.Listener.onStartRejected]) rather than allowed to silently start a second,
 * competing [WavRecorder] session, overwrite the still-finalizing session's
 * currentRequestId/currentTarget/journal ownership, or cause its terminal outcome to be discarded
 * as "stale" once the newer session's own id has replaced it.
 */
internal enum class ServiceState { IDLE, PREPARING, RECORDING, FINALIZING }

/** [ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE] was only added in API 30 (R) -- passing it to
 * `startForeground()` on API 29, where the 3-arg overload already exists but that specific type
 * constant doesn't, risks the OS rejecting an unrecognized foreground service type. API 29 (and
 * everything older) must keep using the plain 2-arg call, exactly like pre-Q behavior. */
internal fun foregroundStartModeFor(sdkInt: Int): ForegroundStartMode =
    if (sdkInt >= Build.VERSION_CODES.R) ForegroundStartMode.MICROPHONE_TYPE else ForegroundStartMode.TWO_ARG
