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
 * same input-discovery/source-selection/sample-rate-negotiation/route-verification building
 * blocks [WavRecorder] itself uses for real recording ([openBestAudioRecord], [AudioSource],
 * [MicrophoneInfo], [MicrophoneDisconnectedException], [MicrophoneRouteChangedException],
 * [peakAmplitude]) rather than a second, independently-maintained implementation of USB
 * microphone routing that could quietly drift from the real recording path's behavior.
 *
 * Structurally mirrors [WavRecorder]'s own start/stop/generation pattern (see that class's doc
 * for the full rationale, which applies identically here): [generation] is bumped on every
 * [start] and every [stop] so a callback from an old, already-stopped test session can never
 * update a newer session's (or no session's) UI, and [stop] never blocks its caller -- it
 * synchronously releases the [AudioSource] (which is what actually unblocks a pending [AudioSource.read])
 * and hands the bounded thread-join off to its own short-lived background thread instead.
 *
 * Never opens a segment, never writes a file, never touches [ActiveSegmentJournal], never starts
 * [RecordingService] -- this is a pure, ephemeral, in-memory microphone check.
 */
internal class MicTestSession(
    private val threadJoinTimeoutMs: Long = THREAD_JOIN_TIMEOUT_MS,
    // Same rationale as WavRecorder's own identically-shaped seam: lets tests substitute a fake
    // AudioSource without a real microphone, and reuses openBestAudioRecord() by default so
    // production behavior is exactly what WavRecorder itself would pick.
    private val openAudioSource: (Context) -> WavRecorder.RecorderConfig = ::openBestAudioRecord,
    // Configurable purely so tests can shrink these below their production defaults instead of
    // waiting out real wall-clock time to exercise the cadence.
    private val levelUpdateIntervalMs: Long = LEVEL_UPDATE_INTERVAL_MS,
    private val routeCheckIntervalMs: Long = ROUTE_CHECK_INTERVAL_MS
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
    }

    private var audioSource: AudioSource? = null
    private var testThread: Thread? = null
    private val isActive = AtomicBoolean(false)

    // Bumped on every start() *and* every stop() -- identical rationale to WavRecorder.generation:
    // a callback already posted to the main looper by a just-stopped (or superseded) session must
    // never be allowed to update a newer session's (or no session's) UI once delivered.
    private val generation = AtomicInteger(0)

    val active: Boolean get() = isActive.get()

    /** Starts the test. [onMicrophoneInfo] fires synchronously, before this call returns, exactly
     * like [WavRecorder.start] -- callers must not read it as "verified" any earlier than
     * [MicrophoneInfo.verified] itself says (see that class's doc): this only ever reports what
     * [AudioSource.describeMicrophone] can actually back up right now. [onLevel] fires at most
     * every [levelUpdateIntervalMs] on the main thread. [onError] fires at most once, from the
     * main thread; exactly like [WavRecorder]'s own `onError` contract, the session is *not* yet
     * released when it fires -- the caller must call [stop] in response, which is what actually
     * releases the [AudioSource] and joins the background thread. (This -- rather than releasing
     * directly from the background thread inside the error path itself -- keeps every mutation of
     * [audioSource]/[testThread] confined to the main thread, exactly like [WavRecorder]'s own
     * `audioSource`/`recordingThread` fields: the background loop never touches them, only reads
     * its own local parameters, so there's no unsynchronized cross-thread field access.) */
    fun start(
        context: Context,
        onMicrophoneInfo: (MicrophoneInfo) -> Unit,
        onLevel: (Float) -> Unit,
        onError: (Exception) -> Unit
    ) {
        if (isActive.get()) return

        val config = try {
            openAudioSource(context)
        } catch (e: Exception) {
            onError(e)
            return
        }

        val source = config.source
        audioSource = source
        try {
            source.startRecording()
        } catch (e: Exception) {
            try { source.release() } catch (_: Exception) {}
            audioSource = null
            onError(e)
            return
        }

        // Synchronous, on the caller's own thread, exactly like WavRecorder.start() -- so a UI
        // observing this call sees the pre-verification "connected" status immediately, and the
        // verified one the moment startRecording() above actually confirms it.
        onMicrophoneInfo(source.describeMicrophone())

        val myGeneration = generation.incrementAndGet()
        isActive.set(true)
        testThread = Thread({
            testLoop(myGeneration, source, config.bufferSize, onLevel, onError)
        }, "MicTestThread").apply { start() }
    }

    /** Never blocks: flips [isActive] false and bumps [generation] synchronously (suppressing any
     * callback already queued on the main looper), stops/releases the [AudioSource] synchronously
     * (which is what unblocks a pending [AudioSource.read] on most implementations, same as
     * [WavRecorder.requestStop]), and hands the bounded thread-join off to a short-lived
     * background thread rather than the caller's own -- so this is always safe to call directly
     * from the main thread (a button tap, a lifecycle callback) with zero risk of stalling it,
     * even in the worst case of a genuinely wedged driver. Idempotent: safe to call when already
     * stopped or never started. */
    fun stop() {
        isActive.set(false)
        generation.incrementAndGet()
        audioSource?.let {
            try { it.stop() } catch (_: Exception) {}
            try { it.release() } catch (_: Exception) {}
        }
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

    private fun testLoop(
        myGeneration: Int,
        source: AudioSource,
        bufferSize: Int,
        onLevel: (Float) -> Unit,
        onError: (Exception) -> Unit
    ) {
        val handler = Handler(Looper.getMainLooper())
        val buffer = ByteArray(bufferSize)
        var lastLevelPost = 0L
        var lastRouteCheck = System.currentTimeMillis()

        while (isActive.get() && generation.get() == myGeneration) {
            val read = try {
                source.read(buffer, 0, buffer.size)
            } catch (e: Exception) {
                reportFatal(myGeneration, handler, onError, e)
                break
            }

            // Mirrors WavRecorder.recordLoop's identical recheck: source.read() can block
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
            if (read == 0) continue

            val now = System.currentTimeMillis()
            if (now - lastLevelPost >= levelUpdateIntervalMs) {
                lastLevelPost = now
                val level = peakAmplitude(buffer, read)
                postIfCurrent(myGeneration, handler) { onLevel(level) }
            }

            if (now - lastRouteCheck >= routeCheckIntervalMs) {
                lastRouteCheck = now
                if (!source.isDeviceConnected()) {
                    reportFatal(
                        myGeneration, handler, onError,
                        MicrophoneDisconnectedException("Microphone disconnected during test")
                    )
                    break
                }
                // Checked in addition to (not instead of) isDeviceConnected() above -- a silent
                // reroute to the phone mic may never fire a removal callback at all; see
                // WavRecorder.recordLoop's identical note.
                if (!source.isRouteUnchanged()) {
                    reportFatal(
                        myGeneration, handler, onError,
                        MicrophoneRouteChangedException(
                            "Microphone test input changed unexpectedly; no longer using the " +
                                "verified external microphone"
                        )
                    )
                    break
                }
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
