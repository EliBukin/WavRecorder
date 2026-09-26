package com.example.wavrecorder

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Thrown inside startup work once that startup has been cancelled. */
internal class StartupCancelledException : CancellationException("Audio startup was cancelled")

/**
 * A start that didn't finish within the startup watchdog's limit ([StartupLimits.startupTimeoutMs]).
 * The app stopped waiting; that doesn't show the native operation stopped or that anything was
 * released -- whatever the start still opens is released on the audio worker when it returns.
 * [waitedForRelease]: it never began, because an earlier release of the microphone was still
 * running.
 */
class MicrophoneStartTimeoutException(message: String, val waitedForRelease: Boolean) : IOException(message)

/** A start refused at once: an earlier stop or release of the microphone (or an abandoned start)
 * is still running long past its normal time, and no new microphone can be opened until it
 * finishes. Nothing was queued; a later start works once it has finished. */
class MicrophoneBusyException(message: String) : IOException(message)

/** The clock and waits startup work uses: injectable, so route-settling windows and deadlines are
 * tested in virtual time rather than with real sleeps. */
internal interface StartupTiming {
    /** Monotonic milliseconds. */
    fun nowMs(): Long

    /** Waits up to [ms], returning early once [scope] is cancelled (the caller then throws). */
    fun pause(ms: Long, scope: StartupScope)
}

/** Real time: a monotonic clock, and waits that end the moment the startup is cancelled. */
internal object SystemStartupTiming : StartupTiming {
    override fun nowMs(): Long = System.nanoTime() / 1_000_000

    override fun pause(ms: Long, scope: StartupScope) {
        scope.awaitCancellation(ms)
    }
}

/**
 * Runs delayed actions on the owner's (main) thread, independently of the audio worker -- so a
 * native call that never returns on the worker can't hold back a timeout.
 */
internal fun interface StartupWatchdog {
    /** Runs [action] after [delayMs] on the owner's thread, unless the returned function is called first. */
    fun schedule(delayMs: Long, action: () -> Unit): () -> Unit

    companion object {
        /** Never fires: for tests that don't exercise timeouts. */
        val NONE = StartupWatchdog { _, _ -> {} }
    }
}

/** The limits [AudioStartup]'s watchdog enforces. */
internal data class StartupLimits(
    /**
     * The longest a start may keep its owner STARTING ("Preparing…"), counted from the start
     * request -- time spent queued behind earlier work on the audio worker included. Negotiation
     * itself ends within [NegotiationLimits.totalMs] (8 s) unless a native call stalls; this adds
     * room for a normal release queued ahead of it and a declined source's release after it, so it
     * only fires when something is genuinely stuck.
     */
    val startupTimeoutMs: Long = 12_000,
    /**
     * A stop or release still running after this long -- or a cancelled start still running this
     * long after it was cancelled (it should notice within one poll) -- is treated as stalled: a
     * native call that isn't returning. Both normally take a few hundred ms at most, even on USB;
     * this matches the recorder's own finalization wait ([WavRecorder.awaitFinalization]).
     */
    val releaseStallMs: Long = 2_000,
    /** Most tasks that may wait for the worker at once: a backstop so repeated retries against a
     * stuck worker can't queue without bound (a stalled worker already refuses new starts). */
    val maxQueuedTasks: Int = 8
)

/**
 * One startup attempt: the scope the blocking startup work runs in, and its cancellation. The
 * owner cancels it (a Stop during startup, the owner going away, or a newer start superseding it);
 * the work notices at its next check or wait and cleans up whatever it holds.
 */
internal class StartupScope(private val timing: StartupTiming = SystemStartupTiming) {
    private val cancelled = CountDownLatch(1)
    @Volatile private var cancelledAtMs = -1L

    // The opened source between the worker finishing and the owner accepting it: whoever takes it
    // first -- the owner's delivery, or the worker cleaning up after a cancel -- owns it.
    private val handoff = AtomicReference<WavRecorder.RecorderConfig?>()

    /** How the startup ended for its owner -- exactly one terminal result (see [AudioStartup]). */
    enum class Resolution { READY, FAILED, TIMED_OUT, CANCELLED }

    private val resolution = AtomicReference<Resolution?>()

    /** Removes the pending timeout, once resolved. */
    @Volatile internal var cancelTimeout: (() -> Unit)? = null

    /** Set once this startup's work is handed to the worker (a refused start never is). */
    @Volatile internal var submitted = false

    /** Set once the worker begins this startup (until then it is queued behind earlier work). */
    @Volatile internal var startedOnWorker = false

    internal fun offer(config: WavRecorder.RecorderConfig) = handoff.set(config)

    internal fun take(): WavRecorder.RecorderConfig? = handoff.getAndSet(null)

    val resolvedAs: Resolution? get() = resolution.get()

    /** Records the one terminal result: true for the first caller, false for every later one. */
    internal fun resolve(result: Resolution): Boolean {
        if (!resolution.compareAndSet(null, result)) return false
        cancelTimeout?.invoke()
        cancelTimeout = null
        return true
    }

    val isCancelled: Boolean get() = cancelled.count == 0L

    fun cancel() {
        if (cancelledAtMs < 0) cancelledAtMs = timing.nowMs()
        cancelled.countDown()
    }

    /** How long ago this was cancelled, or -1 if it wasn't. */
    internal fun cancelledForMs(): Long = if (cancelledAtMs < 0) -1 else timing.nowMs() - cancelledAtMs

    fun throwIfCancelled() {
        if (isCancelled) throw StartupCancelledException()
    }

    fun nowMs(): Long = timing.nowMs()

    /** Waits [ms] (cut short by cancellation), then throws [StartupCancelledException] if cancelled. */
    fun pause(ms: Long) {
        throwIfCancelled()
        if (ms > 0) timing.pause(ms, this)
        throwIfCancelled()
    }

    /** For [StartupTiming]: blocks up to [ms] or until cancelled; true if cancelled. */
    fun awaitCancellation(ms: Long): Boolean = cancelled.await(ms, TimeUnit.MILLISECONDS)
}

/**
 * The serialized audio worker and its watchdog. Everything that touches the microphone natively --
 * opening (negotiating) and starting a source ([launch]), and stopping and releasing one
 * ([shutdown], and every candidate a startup rejects or abandons) -- runs on [worker], never on the
 * owner's (main) thread, and results come back through [deliver].
 *
 * [worker] must run one task at a time, in order: the production worker is a single thread shared
 * by recording and the microphone test ([shared]). So a startup always begins after every earlier
 * shutdown and cleanup has *finished* -- the previous source is released before the next is
 * opened, and two owners can never hold the microphone at once -- and a native call that stalls
 * (a misbehaving USB driver or audio HAL) stalls this thread, never the UI.
 *
 * Ownership: whatever [launch]'s work opens belongs to the startup until the owner accepts it on
 * the delivery thread. A startup cancelled before that -- or a result the owner declines -- is
 * stopped and released on [worker]; the owner never sees it. Everything the owner is handed is
 * already started (see [WavRecorder.RecorderConfig.alreadyStarted]).
 *
 * **Watchdog** -- the worker can't be interrupted, so it is supervised from outside, by [watchdog]
 * on the owner's thread:
 *  - Every startup gets exactly one terminal result for its owner: ready, failed, or -- if nothing
 *    else arrives within [StartupLimits.startupTimeoutMs] of [launch], queued time included -- a
 *    [MicrophoneStartTimeoutException]. Whichever comes first wins; anything later (a late success
 *    or error) is suppressed, and a source that arrives late is released on the worker instead of
 *    being used.
 *  - A timeout only means the app stopped waiting. The worker is never replaced, abandoned or
 *    interrupted, so microphone ownership stays serialized: nothing new can be opened until the
 *    stuck call returns and what it held is released.
 *  - While the worker is stalled -- a stop or release, or a cancelled start, still running past
 *    [StartupLimits.releaseStallMs] -- a new start is refused at once ([MicrophoneBusyException])
 *    rather than queued, so the owner shows a clear state instead of "Preparing…", and repeated
 *    retries can't build up a queue ([StartupLimits.maxQueuedTasks] is a further backstop). Once the
 *    stuck call returns, the worker recovers by itself and the next start proceeds normally.
 */
internal class AudioStartup(
    private val worker: Executor,
    private val deliver: Executor,
    private val timing: StartupTiming = SystemStartupTiming,
    private val watchdog: StartupWatchdog = StartupWatchdog.NONE,
    private val limits: StartupLimits = StartupLimits()
) {
    /** The kind of work running on the worker. */
    enum class Operation { STARTUP, RELEASE }

    private class Running(val operation: Operation, val sinceMs: Long, val scope: StartupScope?)

    /** What the worker is running now -- written only by the worker thread. */
    @Volatile private var running: Running? = null
    private val queued = AtomicInteger(0)

    /** What the worker is doing, as the watchdog judges it. */
    sealed class WorkerState {
        object Idle : WorkerState()
        data class Busy(val operation: Operation, val forMs: Long) : WorkerState()
        /** Running past its normal time: a native call isn't returning. */
        data class Stalled(val operation: Operation, val forMs: Long) : WorkerState()
    }

    fun workerState(): WorkerState {
        val current = running ?: return WorkerState.Idle
        val forMs = timing.nowMs() - current.sinceMs
        val stalled = when (current.operation) {
            Operation.RELEASE -> forMs >= limits.releaseStallMs
            Operation.STARTUP -> forMs >= limits.startupTimeoutMs ||
                (current.scope?.cancelledForMs() ?: -1) >= limits.releaseStallMs
        }
        return if (stalled) WorkerState.Stalled(current.operation, forMs) else WorkerState.Busy(current.operation, forMs)
    }

    /** Tasks waiting for the worker (not yet running). */
    val queuedTasks: Int get() = queued.get()

    /** A fresh scope for one startup; the owner records it as current *before* [launch]ing, since
     * delivery can happen at any point after (immediately, with an inline executor). */
    fun newScope(): StartupScope = StartupScope(timing)

    /**
     * Opens and starts a source with [open] on the worker, then on the delivery thread calls
     * exactly one of [onReady] or [onFailed] -- or neither, if [scope] was [cancel]led first, in
     * which case the source (if any) is released on the worker. [onReady] decides whether the
     * owner keeps the source ([Decision.Keep]) or gives it back ([Decision.Discard]). [onFailed]
     * also reports a start the worker can't take ([MicrophoneBusyException]) or one that timed out
     * ([MicrophoneStartTimeoutException]) -- see the class doc.
     */
    fun launch(
        scope: StartupScope,
        context: Context,
        open: StartupScope.(Context) -> WavRecorder.RecorderConfig,
        onReady: (WavRecorder.RecorderConfig) -> Decision,
        onFailed: (Exception) -> Unit
    ) {
        val state = workerState()
        val refusal = when {
            state is WorkerState.Stalled ->
                "Android is still releasing the microphone (an earlier ${state.operation.name.lowercase()} has been running for ${state.forMs} ms)"
            queued.get() >= limits.maxQueuedTasks -> "Too many earlier microphone operations are still waiting (${queued.get()})"
            else -> null
        }
        if (refusal != null) {
            deliverSafely { if (scope.resolve(StartupScope.Resolution.FAILED)) onFailed(MicrophoneBusyException(refusal)) }
            return
        }
        // Armed before the work is queued, so even an immediate delivery finds it to disarm.
        scope.cancelTimeout = watchdog.schedule(limits.startupTimeoutMs) { timedOut(scope, onFailed) }
        scope.submitted = true
        if (!submit(Operation.STARTUP, scope) { runStartup(scope, context, open, onReady, onFailed) }) {
            scope.submitted = false
            deliverSafely {
                if (scope.resolve(StartupScope.Resolution.FAILED)) onFailed(RejectedExecutionException("The audio worker is unavailable"))
            }
        }
    }

    /**
     * Cancels [scope] (on the owner's thread): it gets no result. The startup notices at its next
     * check or wait and cleans up what it holds; a source it already finished opening, still on its
     * way to the owner, is reclaimed and released by a worker task queued right now -- so it is
     * released before any startup launched after this call can open the microphone.
     */
    fun cancel(scope: StartupScope) {
        scope.resolve(StartupScope.Resolution.CANCELLED)
        reclaim(scope)
    }

    private fun reclaim(scope: StartupScope) {
        scope.cancel()
        // A start that never reached the worker has nothing to reclaim -- and queues nothing behind
        // a stalled worker.
        if (!scope.submitted) return
        if (!submit(Operation.RELEASE) { scope.take()?.let(::release) }) {
            scope.take()?.let(::release) // Never expected (the worker is never shut down), but never leak.
        }
    }

    /** The watchdog's limit ran out before the startup reached its owner: the owner hears so, once,
     * and the startup is cancelled -- whatever it still opens is released when it returns. */
    private fun timedOut(scope: StartupScope, onFailed: (Exception) -> Unit) {
        if (!scope.resolve(StartupScope.Resolution.TIMED_OUT)) return
        val waitedForRelease = !scope.startedOnWorker && running?.operation == Operation.RELEASE
        reclaim(scope)
        onFailed(
            MicrophoneStartTimeoutException(
                if (waitedForRelease) {
                    "The microphone did not start within ${limits.startupTimeoutMs} ms: Android was still releasing it from an earlier use"
                } else {
                    "The microphone did not start within ${limits.startupTimeoutMs} ms"
                },
                waitedForRelease
            )
        )
    }

    /** What the owner does with a delivered source. */
    sealed class Decision {
        /** The owner takes it: from here on, it is the owner's to stop and release ([shutdown]). */
        object Keep : Decision()

        /** The owner can't use it: it is stopped and released on the worker, and only once that
         * release has *finished* does [then] run, on the owner's thread (e.g. to report why) -- so
         * a retry started from [then] can't overlap it. [then] is dropped if the startup is
         * cancelled or times out first; the source is released either way. */
        class Discard(val then: () -> Unit = {}) : Decision()
    }

    /** Done once an owner's source has been stopped and released on the worker ([shutdown]). */
    class Completion {
        private val done = CountDownLatch(1)
        private val callbacks = mutableListOf<Pair<Executor, () -> Unit>>()

        val isDone: Boolean get() = done.count == 0L

        internal fun complete() {
            val toRun = synchronized(callbacks) {
                done.countDown()
                callbacks.toList().also { callbacks.clear() }
            }
            toRun.forEach { (executor, action) -> runOn(executor, action) }
        }

        /** Blocks up to [timeoutMs] -- so only ever on a background thread -- until done; true if done. */
        fun await(timeoutMs: Long): Boolean = done.await(timeoutMs, TimeUnit.MILLISECONDS)

        /** Runs [action] on [executor] once done -- right away if it already is. */
        fun whenDone(executor: Executor, action: () -> Unit) {
            val now = synchronized(callbacks) {
                if (isDone) true else { callbacks += executor to action; false }
            }
            if (now) runOn(executor, action)
        }

        private fun runOn(executor: Executor, action: () -> Unit) {
            try { executor.execute { action() } } catch (_: RejectedExecutionException) {}
        }
    }

    /**
     * Stops and releases an owner's active [source] on the worker, returning at once. Stopping it
     * is also what unblocks a capture thread waiting in [AudioSource.read]. Queued behind anything
     * already on the worker and ahead of any startup launched after this call, so the next startup
     * can't open a microphone until this one is released. If it stalls, the watchdog refuses new
     * starts meanwhile (see the class doc); [Completion] tells the owner when it has finished.
     */
    fun shutdown(source: AudioSource): Completion {
        val completion = Completion()
        val body = {
            try {
                releaseSource(source)
            } finally {
                completion.complete()
            }
        }
        if (!submit(Operation.RELEASE, body = body)) body() // Never expected (the worker is never shut down), but never leak.
        return completion
    }

    private fun runStartup(
        scope: StartupScope,
        context: Context,
        open: StartupScope.(Context) -> WavRecorder.RecorderConfig,
        onReady: (WavRecorder.RecorderConfig) -> Decision,
        onFailed: (Exception) -> Unit
    ) {
        scope.startedOnWorker = true
        val opened = try {
            scope.throwIfCancelled()
            scope.open(context).also { startIfNeeded(it) }
        } catch (_: StartupCancelledException) {
            return
        } catch (e: Exception) {
            fail(scope, onFailed, e)
            return
        } catch (e: LinkageError) {
            // A platform API missing on this Android version: reported like any failed startup,
            // rather than killing the worker task and leaving its owner STARTING indefinitely.
            fail(scope, onFailed, IllegalStateException("Audio startup failed: $e", e))
            return
        }
        scope.offer(opened)
        if (scope.isCancelled) {
            // Cancelled or timed out while opening: returned late, so it is cleaned up, never used.
            scope.take()?.let { track(Operation.RELEASE) { release(it) } }
            return
        }
        val delivered = deliverSafely {
            // Taken here, on the owner's thread -- unless a cancel's cleanup already took it.
            val config = scope.take() ?: return@deliverSafely
            if (scope.isCancelled || scope.resolvedAs != null) {
                releaseOnWorker(config)
                return@deliverSafely
            }
            when (val decision = onReady(config)) {
                Decision.Keep -> scope.resolve(StartupScope.Resolution.READY)
                // Released on the worker, never here on the owner's thread; the owner hears why only
                // after that release has finished -- and not at all if it cancelled, or the watchdog
                // timed the startup out, meanwhile.
                is Decision.Discard -> releaseOnWorker(config) {
                    deliverSafely { if (scope.resolve(StartupScope.Resolution.FAILED)) decision.then() }
                }
            }
        }
        if (!delivered) scope.take()?.let { track(Operation.RELEASE) { release(it) } }
    }

    private fun fail(scope: StartupScope, onFailed: (Exception) -> Unit, e: Exception) {
        if (!scope.isCancelled) deliverSafely { if (scope.resolve(StartupScope.Resolution.FAILED)) onFailed(e) }
    }

    private fun releaseOnWorker(config: WavRecorder.RecorderConfig, afterwards: () -> Unit = {}) {
        val body = {
            release(config)
            afterwards()
        }
        if (!submit(Operation.RELEASE, body = body)) body() // Never expected (the worker is never shut down), but never leak.
    }

    /** Queues [body] on the worker, tracked (for the watchdog) as [operation]; false if the worker refused it. */
    private fun submit(operation: Operation, scope: StartupScope? = null, body: () -> Unit): Boolean {
        queued.incrementAndGet()
        val task = Runnable {
            queued.decrementAndGet()
            track(operation, scope, body)
        }
        return try {
            worker.execute(task)
            true
        } catch (_: RejectedExecutionException) {
            queued.decrementAndGet()
            false
        }
    }

    /** Runs [body] on the worker thread, recorded as what the worker is doing. */
    private fun track(operation: Operation, scope: StartupScope? = null, body: () -> Unit) {
        val previous = running
        running = Running(operation, timing.nowMs(), scope)
        try {
            body()
        } finally {
            running = previous
        }
    }

    /** A source that negotiation didn't already start (a test fake) is started here, on the
     * worker, so the owner is always handed a running source. */
    private fun startIfNeeded(config: WavRecorder.RecorderConfig) {
        if (config.alreadyStarted) return
        try {
            config.source.startRecording()
        } catch (e: Exception) {
            // Started nothing, but may hold a native AudioRecord: must not leak it.
            try { config.source.release() } catch (_: Exception) {}
            throw e
        }
    }

    private fun release(config: WavRecorder.RecorderConfig) = releaseSource(config.source)

    private fun releaseSource(source: AudioSource) {
        try { source.stop() } catch (_: Exception) {}
        try { source.release() } catch (_: Exception) {}
    }

    private fun deliverSafely(action: () -> Unit): Boolean = try {
        deliver.execute(action)
        true
    } catch (_: RejectedExecutionException) {
        false
    }

    companion object {
        /** How long the shared worker thread stays alive with nothing to do before exiting; it is
         * recreated on the next startup, so nothing ever needs to shut it down. */
        private const val WORKER_IDLE_TIMEOUT_S = 30L

        private val sharedWorker: Executor by lazy {
            ThreadPoolExecutor(1, 1, WORKER_IDLE_TIMEOUT_S, TimeUnit.SECONDS, LinkedBlockingQueue()) { task ->
                Thread(task, "AudioStartup").apply { isDaemon = true }
            }.apply { allowCoreThreadTimeOut(true) }
        }

        private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

        private val mainThread: Executor by lazy {
            Executor { task -> if (!mainHandler.post(task)) throw RejectedExecutionException("Main looper is exiting") }
        }

        /** Timeouts on the main looper: independent of the worker, and on the owner's thread. */
        private val mainWatchdog = StartupWatchdog { delayMs, action ->
            val task = Runnable { action() }
            mainHandler.postDelayed(task, delayMs)
            val cancel: () -> Unit = { mainHandler.removeCallbacks(task) }
            cancel
        }

        /**
         * The production startup: a single worker thread shared by recording and the microphone
         * test, results and timeouts delivered on the main thread. Process-wide by design -- one
         * owner of the microphone at a time -- and self-cleaning: the idle worker exits on its own
         * ([WORKER_IDLE_TIMEOUT_S]), so it is reused across recordings and never shut down.
         */
        val shared: AudioStartup by lazy { AudioStartup(sharedWorker, mainThread, SystemStartupTiming, mainWatchdog) }
    }
}
