package bukin.wavrecorder

import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Makes sure a test leaves nothing running that could reach into a later one. A recording, a
 * microphone test, a finalization or a recovery left running -- or a screen left registered for
 * [RecordingOwnership] changes -- keeps posting to the main looper and touching shared state after
 * its test has ended, where it makes some *other* test fail, intermittently (a toast from an
 * earlier test's Library screen landing in a later test was one such leak).
 *
 * After the test and its `@After` methods, this waits -- bounded -- until every such thread the
 * test started has ended, the shared audio worker has nothing left to do, and no screen it opened
 * is still registered; anything still running then fails the test, naming it. A timeout is never counted as
 * a clean finish. What was already running or registered before the test is not its own and is
 * not held against it.
 */
class TestIsolation(private val timeoutMs: Long = 5_000) : TestRule {

    private val toClose = mutableListOf<AutoCloseable>()

    /** Closes [resource] -- a screen the test opened -- once the test and its `@After` methods are
     * over, before checking that nothing it started is still running. */
    fun <T : AutoCloseable> closeAfter(resource: T): T = resource.also { toClose += it }

    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            val alreadyRunning = workThreads().toSet()
            val listenersBefore = RecordingOwnership.listenerCount
            var failure: Throwable? = null
            try {
                base.evaluate()
            } catch (t: Throwable) {
                failure = t
            }
            for (resource in toClose.asReversed()) {
                try {
                    resource.close()
                } catch (t: Throwable) {
                    val first = failure
                    if (first == null) failure = t else first.addSuppressed(t)
                }
            }
            toClose.clear()
            val leaks = awaitQuiet(alreadyRunning, listenersBefore)
            if (leaks.isNotEmpty() && REPORT_ONLY) println("TEST_ISOLATION_LEAK ${description.displayName}: $leaks")
            if (failure != null) {
                if (leaks.isNotEmpty() && !REPORT_ONLY) failure.addSuppressed(AssertionError("Also still running after the test: $leaks"))
                throw failure
            }
            if (leaks.isNotEmpty() && !REPORT_ONLY) {
                throw AssertionError("Still running $timeoutMs ms after the test ended: $leaks")
            }
        }
    }

    /** What is still running, once everything has ended or [timeoutMs] has passed. */
    private fun awaitQuiet(alreadyRunning: Set<Thread>, listenersBefore: Int): List<String> {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val leaks = leaks(alreadyRunning, listenersBefore)
            if (leaks.isEmpty() || System.currentTimeMillis() >= deadline) return leaks
            Thread.sleep(10)
        }
    }

    private fun leaks(alreadyRunning: Set<Thread>, listenersBefore: Int): List<String> {
        val threads = workThreads().filter { it !in alreadyRunning }.map { "thread ${it.name}" }
        val added = RecordingOwnership.listenerCount - listenersBefore
        val listeners = if (added > 0) listOf("$added screen(s) still registered for recording ownership changes") else emptyList()
        val worker = AudioStartup.shared.let {
            if (it.workerState() != AudioStartup.WorkerState.Idle || it.queuedTasks > 0) {
                listOf("shared audio worker: ${it.workerState()}, ${it.queuedTasks} queued")
            } else {
                emptyList()
            }
        }
        return threads + listeners + worker
    }

    private companion object {
        /** Every background thread the app itself starts for recording, the microphone test,
         * finalization, recovery and deletes (the shared audio worker is checked separately: its
         * idle thread outlives its work by design). */
        val WORK_THREADS = setOf(
            "WavRecorderThread", "WavRecorderCapture", "RecordingFinalizeThread", "WavRecoveryThread",
            "MicTestThread", "MicTestWatchdog", "MicTestStopThread", "DeleteRecordingThread", "BulkDeleteThread"
        )

        /** Measures instead of failing (set by a diagnostic run only). */
        val REPORT_ONLY = System.getProperty("wavrecorder.isolation.report") == "true"

        fun workThreads(): List<Thread> = Thread.getAllStackTraces().keys.filter { it.isAlive && it.name in WORK_THREADS }
    }
}
