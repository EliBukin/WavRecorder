package com.example.wavrecorder

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Lets the user visually confirm the selected microphone is receiving sound -- a live level
 * meter, nothing more -- without ever touching a file, the crash-recovery journal, or
 * [RecordingService]'s foreground-service/notification machinery. Deliberately reuses the exact
 * same input-discovery/source-selection/format-negotiation/route-verification building
 * blocks [WavRecorder] itself uses for real recording ([openBestAudioRecord], [AudioSource],
 * [MicrophoneInfo], [MicrophoneDisconnectedException], [MicrophoneRouteChangedException],
 * [peakAmplitude]) -- including format negotiation, so the test meters exactly the format a real
 * recording would save -- rather than a second, independently-maintained implementation of USB
 * microphone routing that could quietly drift from the real recording path's behavior.
 *
 * Structurally mirrors [WavRecorder]'s own start/stop/generation pattern (see that class's doc
 * for the full rationale, which applies identically here): negotiation runs off the main thread
 * ([AudioStartup]), [generation] is bumped on every activation and every [stop] so a callback
 * from an old, already-stopped test session can never update a newer session's (or no session's)
 * UI, and [stop] never blocks its caller -- it hands the [AudioSource] to the audio worker to be
 * stopped (which is what actually unblocks a pending [AudioSource.read]) and released, and the
 * bounded thread-join to its own short-lived background thread.
 *
 * Never opens a segment, never writes a file, never touches [ActiveSegmentJournal], never starts
 * [RecordingService] -- this is a pure, ephemeral, in-memory microphone check.
 */
internal class MicTestSession(
    private val threadJoinTimeoutMs: Long = THREAD_JOIN_TIMEOUT_MS,
    // Same rationale as WavRecorder's own identically-shaped seam: lets tests substitute a fake
    // AudioSource without a real microphone, and reuses openBestAudioRecord() by default so
    // production behavior is exactly what WavRecorder itself would pick.
    private val openAudioSource: StartupScope.(Context) -> WavRecorder.RecorderConfig = { openBestAudioRecord(this, it) },
    // Configurable purely so tests can shrink these below their production defaults instead of
    // waiting out real wall-clock time to exercise the cadence.
    private val levelUpdateIntervalMs: Long = LEVEL_UPDATE_INTERVAL_MS,
    private val routeCheckIntervalMs: Long = ROUTE_CHECK_INTERVAL_MS,
    // Same as WavRecorder's: negotiation runs on the shared audio-startup worker -- the same single
    // thread real recording uses, so a test's startup (or its cleanup) and a recording's can never
    // hold the microphone at once -- and its result is delivered on the main thread.
    private val startup: AudioStartup = AudioStartup.shared,
    // Monotonic milliseconds for the level and route-check cadence and the no-progress limit --
    // never the wall clock, which can jump. Injectable for tests.
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    // Same limit as WavRecorder's: no audio at all (not even silence) for this long ends the test.
    private val noProgressTimeoutMs: Long = NO_PROGRESS_TIMEOUT_MS
) {
    companion object {
        private const val THREAD_JOIN_TIMEOUT_MS = 2000L

        // Deliberately coarser than a per-buffer-read rate: a level meter genuinely only needs to
        // look continuously "live" to a human eye, not reflect literally every AudioRecord buffer
        // -- posting every single read to the main thread would be needless Handler/View-invalidate
        // churn for a purely cosmetic feature. ~12 updates/sec is comfortably smooth.
        private const val LEVEL_UPDATE_INTERVAL_MS = 80L

        // Same cadence WavRecorder's own header-flush-piggybacked disconnect/route checks use --
        // this doesn't need read()-level responsiveness, just to not go unnoticed for long.
        private const val ROUTE_CHECK_INTERVAL_MS = 3000L

        // See WavRecorder.NO_PROGRESS_TIMEOUT_MS: silent (zero-valued) audio is still audio.
        private const val NO_PROGRESS_TIMEOUT_MS = 10_000L

        // How often the progress watchdog looks, at most (it looks at least four times per limit).
        private const val WATCH_INTERVAL_MS = 250L
    }

    /** When the test loop last received any audio (silence included), in [clock] time. */
    private class Progress(start: Long) {
        @Volatile var lastAudioAt = start
    }

    // Cancels the current test's progress watchdog at once -- main-thread confined, like
    // audioSource/testThread.
    private var watchdogCancel: java.util.concurrent.CountDownLatch? = null

    /** IDLE -> STARTING ([start]: negotiating off the main thread) -> TESTING (the level meter
     * runs) -> IDLE ([stop], which also cancels a STARTING test). */
    enum class State { IDLE, STARTING, TESTING }

    private var audioSource: AudioSource? = null
    private var testThread: Thread? = null
    private val isActive = AtomicBoolean(false)

    // The startup in flight, or null -- main-thread confined, exactly like WavRecorder's own
    // pendingStartup: a delivery for a scope that is no longer this one is declined, and its
    // source released on the startup worker.
    @Volatile private var pendingStartup: StartupScope? = null

    // Bumped on every start() *and* every stop() -- identical rationale to WavRecorder.generation:
    // a callback already posted to the main looper by a just-stopped (or superseded) session must
    // never be allowed to update a newer session's (or no session's) UI once delivered.
    private val generation = AtomicInteger(0)

    /** True while TESTING. */
    val active: Boolean get() = isActive.get()

    /** True while STARTING. */
    val starting: Boolean get() = pendingStartup != null

    val state: State
        get() = when {
            pendingStartup != null -> State.STARTING
            isActive.get() -> State.TESTING
            else -> State.IDLE
        }

    /** The format the current (or most recent) test opened the microphone in -- negotiated by the
     * same [openBestAudioRecord] real recording uses, so it's exactly what a recording started now
     * would save. Null before a test has opened, or if the most recent attempt failed. */
    @Volatile var format: PcmFormat? = null
        private set

    /** How [format] was negotiated (null for a test/fake source). */
    @Volatile var negotiation: NegotiatedAudio? = null
        private set

    /** The format Android captures from the device in, when known (see [DeviceSideFormat]). */
    @Volatile var deviceFormat: DeviceSideFormat? = null
        private set

    /** Starts the test: returns at once, in STARTING, while the microphone is negotiated on the
     * startup worker (see [AudioStartup]); the caller's (main) thread never waits for it. Once it's
     * ready the session becomes TESTING, and then -- last, so the caller may [stop] from inside it
     * -- [onMicrophoneInfo] reports the verified route. Callers must not read it as "verified" any
     * earlier than [MicrophoneInfo.verified] itself says (see that class's doc). [onLevel] fires at
     * most every [levelUpdateIntervalMs] on the main thread. [onError] fires at most once, on the
     * main thread -- including for a startup that failed; exactly like [WavRecorder]'s own
     * `onError` contract, the session is *not* yet released when it fires -- the caller must call
     * [stop] in response, which is what actually releases the [AudioSource] and joins the
     * background thread. (This -- rather than releasing directly from the background thread
     * inside the error path itself -- keeps every mutation of [audioSource]/[testThread] confined
     * to the main thread, exactly like [WavRecorder]'s own `audioSource`/`recordingThread` fields:
     * the background loop never touches them, only reads its own local parameters, so there's no
     * unsynchronized cross-thread field access.) A start the audio worker refuses (an earlier
     * release stalled: [MicrophoneBusyException]) or its watchdog ends
     * ([MicrophoneStartTimeoutException]) is reported through [onError] the same way, exactly
     * once. A [stop] while STARTING cancels the startup: nothing further is reported, and what it
     * opened is released on the worker. */
    fun start(
        context: Context,
        onMicrophoneInfo: (MicrophoneInfo) -> Unit,
        onLevel: (Float) -> Unit,
        onError: (Exception) -> Unit
    ) {
        if (isActive.get() || pendingStartup != null) return
        format = null
        negotiation = null
        deviceFormat = null

        val scope = startup.newScope()
        pendingStartup = scope
        startup.launch(
            scope, context, openAudioSource,
            onReady = { config ->
                if (pendingStartup !== scope) {
                    // Stopped or superseded meanwhile: AudioStartup releases the source.
                    AudioStartup.Decision.Discard()
                } else {
                    pendingStartup = null
                    activate(config, onMicrophoneInfo, onLevel, onError)
                    AudioStartup.Decision.Keep
                }
            },
            onFailed = { e ->
                if (pendingStartup === scope) {
                    pendingStartup = null
                    onError(e)
                }
            }
        )
    }

    private fun activate(
        config: WavRecorder.RecorderConfig,
        onMicrophoneInfo: (MicrophoneInfo) -> Unit,
        onLevel: (Float) -> Unit,
        onError: (Exception) -> Unit
    ) {
        format = config.format
        negotiation = config.negotiation
        val source = config.source
        audioSource = source
        deviceFormat = source.deviceSideFormat()

        val myGeneration = generation.incrementAndGet()
        isActive.set(true)
        val progress = Progress(clock())
        // Watches progress from outside the read loop, so a read that never returns is noticed too.
        // Armed before the reader starts, so the very first read is watched.
        val cancel = java.util.concurrent.CountDownLatch(1)
        watchdogCancel = cancel
        Thread({ watchProgress(myGeneration, progress, cancel, onError) }, "MicTestWatchdog").apply {
            isDaemon = true
            start()
        }
        testThread = Thread({
            testLoop(myGeneration, source, config.frameAlignedBufferSize, config.format, progress, onLevel, onError)
        }, "MicTestThread").apply { start() }

        // Last, once fully TESTING: the Record screen's route check may stop the test from right
        // here, and nothing after this line touches the session.
        onMicrophoneInfo(source.describeMicrophone())
    }

    /** Never blocks: flips [isActive] false and bumps [generation] synchronously (suppressing any
     * callback already queued on the main looper), cancels a STARTING test, and hands the active
     * [AudioSource] to the audio worker to be stopped and released there ([AudioStartup.shutdown],
     * exactly like [WavRecorder.requestStop]: stopping it is what unblocks a pending
     * [AudioSource.read], and a native stop or release that stalls stalls the worker, not the
     * caller). The bounded thread-join runs on a short-lived background thread. So this is safe to
     * call directly from the main thread (a button tap, a lifecycle callback): it never waits on a
     * lock, latch, thread or native call. Idempotent: safe to call when already stopped or never
     * started. */
    fun stop() {
        // A test still negotiating is cancelled: its delivery will be declined and whatever it
        // opened released on the startup worker.
        pendingStartup?.let {
            startup.cancel(it)
            pendingStartup = null
        }
        isActive.set(false)
        generation.incrementAndGet()
        watchdogCancel?.countDown()
        watchdogCancel = null
        audioSource?.let { startup.shutdown(it) }
        audioSource = null
        val thread = testThread
        testThread = null
        if (thread != null) {
            Thread({
                thread.join(threadJoinTimeoutMs)
                if (thread.isAlive) thread.interrupt()
            }, "MicTestStopThread").start()
        }
    }

    /**
     * The test's progress watchdog, on its own thread: independent of the read loop, so it notices
     * a read that never returns as well as one that keeps returning nothing -- no audio at all, not
     * even silence, for [noProgressTimeoutMs] ends the test as stalled (reported once). It can't
     * make a native read return; the caller's [stop] then hands the source to the audio worker to
     * be stopped and released, and no new microphone is opened before that release finishes.
     * Ends at once when the test is stopped ([cancel]) or superseded.
     */
    private fun watchProgress(myGeneration: Int, progress: Progress, cancel: java.util.concurrent.CountDownLatch, onError: (Exception) -> Unit) {
        val handler = Handler(Looper.getMainLooper())
        val interval = (noProgressTimeoutMs / 4).coerceIn(1L, WATCH_INTERVAL_MS)
        try {
            while (isActive.get() && generation.get() == myGeneration) {
                if (clock() - progress.lastAudioAt >= noProgressTimeoutMs) {
                    reportFatal(
                        myGeneration, handler, onError,
                        MicrophoneStalledException("The microphone delivered no audio for ${noProgressTimeoutMs} ms")
                    )
                    return
                }
                if (cancel.await(interval, java.util.concurrent.TimeUnit.MILLISECONDS)) return
            }
        } catch (_: InterruptedException) {
            // Only ever abandoned: nothing to report.
        }
    }

    private fun testLoop(
        myGeneration: Int,
        source: AudioSource,
        bufferSize: Int,
        format: PcmFormat,
        progress: Progress,
        onLevel: (Float) -> Unit,
        onError: (Exception) -> Unit
    ) {
        val handler = Handler(Looper.getMainLooper())
        val buffer = ByteArray(bufferSize)
        var lastLevelPost = Long.MIN_VALUE
        var lastRouteCheck = clock()
        val emptyReads = EmptyReadBackoff()

        while (isActive.get() && generation.get() == myGeneration) {
            // Route and disconnect checks run on their own cadence whatever the reads return, so a
            // source that keeps returning nothing is still checked -- see WavRecorder.captureLoop.
            val checkedAt = clock()
            if (checkedAt - lastRouteCheck >= routeCheckIntervalMs) {
                lastRouteCheck = checkedAt
                if (!source.isDeviceConnected()) {
                    reportFatal(myGeneration, handler, onError, MicrophoneDisconnectedException("Microphone disconnected during test"))
                    break
                }
                // Checked in addition to (not instead of) isDeviceConnected() above -- a silent
                // reroute to the phone mic may never fire a removal callback at all; see
                // WavRecorder.captureLoop's identical note.
                if (!source.isRouteUnchanged()) {
                    reportFatal(
                        myGeneration, handler, onError,
                        MicrophoneRouteChangedException(
                            "Microphone test input changed unexpectedly; no longer using the verified external microphone"
                        )
                    )
                    break
                }
            }

            val read = try {
                source.read(buffer, 0, buffer.size)
            } catch (e: Exception) {
                reportFatal(myGeneration, handler, onError, e)
                break
            }

            // Mirrors WavRecorder.captureLoop's identical recheck: source.read() can block
            // arbitrarily long, and stop() intentionally stops/releases the AudioSource *before*
            // this loop notices, specifically to unblock a pending read() -- recheck immediately,
            // before this data (or a negative/zero result caused by the intentional stop) touches
            // any callback.
            if (generation.get() != myGeneration || !isActive.get()) break

            if (read < 0) {
                reportFatal(
                    myGeneration, handler, onError,
                    IllegalStateException("Microphone test stopped unexpectedly (AudioRecord error $read)")
                )
                break
            }
            if (read == 0) {
                // No audio this time; the progress watchdog ends the test if that goes on too long.
                // Paced, not retried immediately: see EmptyReadBackoff. Interrupted only when a
                // stopped test's thread is being abandoned, so that just ends the loop.
                try {
                    emptyReads.onEmpty()
                } catch (_: InterruptedException) {
                    break
                }
                continue
            }
            emptyReads.onData()

            val now = clock()
            progress.lastAudioAt = now
            if (lastLevelPost == Long.MIN_VALUE || now - lastLevelPost >= levelUpdateIntervalMs) {
                lastLevelPost = now
                // Only whole frames are metered; a trailing partial frame is simply not counted.
                val level = peakAmplitude(buffer, read, format)
                postIfCurrent(myGeneration, handler) { onLevel(level) }
            }
        }
    }

    /** Reports a fatal test-loop failure -- unless a newer session has already superseded this
     * one, or it's already been stopped (see [WavRecorder.reportFatal]'s identical rationale).
     * Deliberately does *not* touch [audioSource]/[testThread] itself (see [start]'s doc): the
     * caller's [onError] must call [stop] to actually release them. */
    private fun reportFatal(myGeneration: Int, handler: Handler, onError: (Exception) -> Unit, e: Exception) {
        if (generation.get() == myGeneration && isActive.compareAndSet(true, false)) {
            postIfCurrent(myGeneration, handler) { onError(e) }
        }
    }

    private fun postIfCurrent(myGeneration: Int, handler: Handler, action: () -> Unit) {
        if (generation.get() != myGeneration) return
        handler.post {
            if (generation.get() == myGeneration) action()
        }
    }
}
