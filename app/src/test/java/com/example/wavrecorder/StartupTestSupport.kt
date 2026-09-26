package com.example.wavrecorder

import java.util.concurrent.Executor

/**
 * Runs audio startup work and its delivery inline, on the calling thread. For tests of what a
 * session does once started (rollover, errors, finalization, the Record screen's UI) rather than
 * of the startup threading itself: with it, [WavRecorder.start] and [MicTestSession.start] finish
 * before returning, as they did before startup moved off the main thread. The asynchronous path
 * is covered by [AsyncStartupTest] and the other startup tests, with [ManualExecutor]s and the
 * real [AudioStartup.shared].
 */
internal val ImmediateStartup = AudioStartup(worker = Executor { it.run() }, deliver = Executor { it.run() })

/** An executor that only queues: a test runs the queued tasks when it chooses, so every
 * interleaving of startup work, delivery, stops and new starts is exact and repeatable. */
internal class ManualExecutor : Executor {
    private val queue = ArrayDeque<Runnable>()

    val pending: Int get() = synchronized(queue) { queue.size }

    override fun execute(command: Runnable) {
        synchronized(queue) { queue.addLast(command) }
    }

    /** Runs the oldest queued task; false if there was none. */
    fun runNext(): Boolean {
        val next = synchronized(queue) { queue.removeFirstOrNull() } ?: return false
        next.run()
        return true
    }

    /** Runs queued tasks, including any they queue, until none are left. */
    fun runAll() {
        while (runNext()) Unit
    }
}

/**
 * Virtual time for startup work: [nowMs] moves only when something pauses, by exactly the pause,
 * so route-settling windows and deadlines are exact and nothing really sleeps. [onPause] runs after
 * each pause, at the new time -- e.g. to cancel the startup mid-wait.
 */
internal class VirtualStartupTiming(
    start: Long = 0,
    private val onPause: (nowMs: Long) -> Unit = {}
) : StartupTiming {
    @Volatile var now: Long = start
        private set
    val pauses = mutableListOf<Long>()

    override fun nowMs(): Long = now

    override fun pause(ms: Long, scope: StartupScope) {
        synchronized(pauses) { pauses += ms }
        now += ms
        onPause(now)
    }

    /** Moves the clock without a pause (time passing elsewhere -- e.g. in a stalled native call). */
    fun advance(ms: Long) {
        now += ms
    }
}

/**
 * [AudioStartup]'s watchdog on virtual time: [advance] moves [timing]'s clock and runs every action
 * that came due, in due order, on the calling thread -- the owner's thread, as the production
 * watchdog (the main looper) does.
 */
internal class ManualWatchdog(private val timing: VirtualStartupTiming) : StartupWatchdog {
    private class Entry(val dueAt: Long, val action: () -> Unit) {
        @Volatile var cancelled = false
    }

    private val entries = mutableListOf<Entry>()

    /** Scheduled actions not yet run or cancelled. */
    val pending: Int get() = synchronized(entries) { entries.count { !it.cancelled } }

    override fun schedule(delayMs: Long, action: () -> Unit): () -> Unit {
        val entry = Entry(timing.now + delayMs, action)
        synchronized(entries) { entries += entry }
        return { entry.cancelled = true }
    }

    fun advance(ms: Long) {
        timing.advance(ms)
        while (true) {
            val due = synchronized(entries) {
                entries.filter { !it.cancelled && it.dueAt <= timing.now }.minByOrNull { it.dueAt }?.also { entries.remove(it) }
            } ?: break
            due.action()
        }
    }
}

/**
 * For tests on the real, asynchronous startup ([AudioStartup.shared]): waits for [starting] to turn
 * false while running the (Robolectric, paused) main looper, which is where the startup delivers
 * its result.
 */
internal fun awaitStartupDelivered(timeoutMs: Long = 5_000, starting: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (starting() && System.currentTimeMillis() < deadline) {
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        Thread.sleep(5)
    }
    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    check(!starting()) { "startup was not delivered within $timeoutMs ms" }
}
