package com.example.wavrecorder

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * One owner of the microphone at a time, whatever the order of starts, stops, deliveries,
 * releases and timeouts -- for the microphone test, recording, and switching between them. A
 * ledger records every source's acquisition and release in the order they happen; every scenario
 * asserts that no source is acquired while another is held.
 *
 * The first scenarios fix the one ordering that used to break this: a startup stopped while its
 * opened source was on its way to the owner, restarted at once, and its delivery run before the
 * cleanup the stop had queued on the worker. The delivery took the source and queued its release
 * behind the new start's open -- so the new start opened a second microphone while the first was
 * still held (the intermittent "two owners" failure on the real worker).
 */
@RunWith(RobolectricTestRunner::class)
class MicrophoneOwnershipTest {

    @get:Rule
    val isolation = TestIsolation()

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun context() = ApplicationProvider.getApplicationContext<Context>()

    /** Every acquisition ("+name") and release ("-name"), in order, and the most held at once. */
    private class Ledger {
        private val events = mutableListOf<String>()
        private val held = mutableSetOf<String>()
        var maxHeld = 0
            private set

        @Synchronized fun acquired(name: String) {
            held += name
            maxHeld = maxOf(maxHeld, held.size)
            events += "+$name"
        }

        @Synchronized fun released(name: String) {
            if (held.remove(name)) events += "-$name"
        }

        val log: List<String> @Synchronized get() = events.toList()
        val holding: Set<String> @Synchronized get() = held.toSet()
    }

    /** A source whose release is recorded; [releaseGate], if given, holds its release (a slow native release). */
    private class Source(val name: String, private val ledger: Ledger, private val releaseGate: CountDownLatch? = null) : AudioSource {
        override fun startRecording() {}
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            Thread.sleep(2)
            return 0
        }
        override fun stop() {}
        override fun release() {
            releaseGate?.await(5, TimeUnit.SECONDS)
            ledger.released(name)
        }
        override fun describeMicrophone() = MicrophoneInfo(name, isExternal = true, verified = true)
    }

    /** Opens sources named [prefix]1, [prefix]2, ...; [beforeOpening] runs first, on the worker. */
    private fun opener(
        ledger: Ledger,
        prefix: String,
        releaseGate: CountDownLatch? = null,
        beforeOpening: (Int) -> Unit = {}
    ): StartupScope.(Context) -> WavRecorder.RecorderConfig {
        val count = AtomicInteger()
        return {
            val n = count.incrementAndGet()
            beforeOpening(n)
            val source = Source("$prefix$n", ledger, releaseGate)
            ledger.acquired(source.name)
            WavRecorder.RecorderConfig(source, 48000, 4096)
        }
    }

    // Everything a test starts is stopped and confirmed finished afterwards -- see tearDown.
    private val sessions = mutableListOf<MicTestSession>()
    private val recorders = mutableListOf<WavRecorder>()
    private val workers = mutableListOf<ExecutorService>()
    private val gates = mutableListOf<CountDownLatch>()

    @After
    fun tearDown() {
        gates.forEach { it.countDown() }
        sessions.forEach { it.stop() }
        recorders.forEach { it.stop() }
        workers.forEach { it.shutdown() }
        workers.forEach { assertTrue("the audio worker finished its work", it.awaitTermination(5, TimeUnit.SECONDS)) }
        RecordingOwnership.resetForTest()
    }

    private fun gate() = CountDownLatch(1).also { gates += it }

    private fun realWorker(): ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "OwnershipTestWorker") }.also { workers += it }

    private val mainThread = Executor { task -> Handler(Looper.getMainLooper()).post(task) }

    private fun session(startup: AudioStartup, open: StartupScope.(Context) -> WavRecorder.RecorderConfig) =
        MicTestSession(startup = startup, openAudioSource = open).also { sessions += it }

    private fun recorder(startup: AudioStartup, open: StartupScope.(Context) -> WavRecorder.RecorderConfig) =
        WavRecorder(startup = startup, openAudioSource = open).also { recorders += it }

    private fun MicTestSession.begin(errors: MutableList<Exception> = mutableListOf()) =
        start(context(), onMicrophoneInfo = {}, onLevel = {}, onError = { errors += it })

    private fun WavRecorder.begin(dir: File = tempFolder.newFolder()) {
        var part = 0
        start(context(), WavRecorder.NextTarget { OutputTarget.FileTarget(File(dir, "part${++part}.wav")) },
            onSegmentStarted = {}, onAmplitude = {}, onError = {})
    }

    private fun waitFor(what: String, timeoutMs: Long = 5000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(2)
        }
        shadowOf(Looper.getMainLooper()).idle()
        check(condition()) { "timed out waiting: $what" }
    }

    // ---- A delivery that runs before the stop's cleanup -----------------------------------------

    @Test
    fun `microphone test - a result delivered after its stop is released before an immediate restart opens the microphone`() {
        val ledger = Ledger()
        val worker = ManualExecutor()
        val deliver = ManualExecutor()
        val session = session(AudioStartup(worker, deliver), opener(ledger, "test"))

        session.begin()
        worker.runAll()     // test1 is opened; its delivery is on its way
        session.stop()      // cancelled: the stop queues test1's cleanup on the worker...
        session.begin()     // ...ahead of test2's open
        deliver.runAll()    // test1's delivery runs before that cleanup
        worker.runAll()
        deliver.runAll()

        assertEquals(listOf("+test1", "-test1", "+test2"), ledger.log)
        assertEquals("never two owners", 1, ledger.maxHeld)
        assertTrue(session.active)
        session.stop()
        worker.runAll()
        assertEquals(emptySet<String>(), ledger.holding)
    }

    @Test
    fun `recording - a result delivered after its stop is released before an immediate restart opens the microphone`() {
        val ledger = Ledger()
        val worker = ManualExecutor()
        val deliver = ManualExecutor()
        val recorder = recorder(AudioStartup(worker, deliver), opener(ledger, "rec"))

        recorder.begin()
        worker.runAll()
        recorder.requestStop()
        recorder.begin()
        deliver.runAll()
        worker.runAll()
        deliver.runAll()

        assertEquals(listOf("+rec1", "-rec1", "+rec2"), ledger.log)
        assertEquals(1, ledger.maxHeld)
        assertTrue(recorder.isActive)
        val stopping = recorder.requestStop()
        worker.runAll()
        recorder.awaitFinalization(stopping)
        assertEquals(emptySet<String>(), ledger.holding)
    }

    @Test
    fun `switching - a microphone test delivered after its stop is released before recording opens the microphone`() {
        val ledger = Ledger()
        val worker = ManualExecutor()
        val deliver = ManualExecutor()
        val startup = AudioStartup(worker, deliver)
        val session = session(startup, opener(ledger, "test"))
        val recorder = recorder(startup, opener(ledger, "rec"))

        session.begin()
        worker.runAll()
        session.stop()
        recorder.begin()
        deliver.runAll()
        worker.runAll()
        deliver.runAll()

        assertEquals(listOf("+test1", "-test1", "+rec1"), ledger.log)
        assertEquals(1, ledger.maxHeld)
        assertTrue(recorder.isActive)

        // ...and back: recording delivered after its stop, then the microphone test.
        val stopping = recorder.requestStop()
        worker.runAll()
        recorder.awaitFinalization(stopping)
        recorder.begin()
        worker.runAll()
        recorder.requestStop()
        session.begin()
        deliver.runAll()
        worker.runAll()
        deliver.runAll()

        assertEquals(listOf("+test1", "-test1", "+rec1", "-rec1", "+rec2", "-rec2", "+test2"), ledger.log)
        assertEquals(1, ledger.maxHeld)
        assertTrue(session.active)
    }

    // ---- Stop during startup, and an immediate restart ------------------------------------------

    @Test
    fun `a stop before the startup ran, then an immediate restart - the stopped one never opens`() {
        val ledger = Ledger()
        val worker = ManualExecutor()
        val deliver = ManualExecutor()
        val startup = AudioStartup(worker, deliver)
        val session = session(startup, opener(ledger, "test"))
        val recorder = recorder(startup, opener(ledger, "rec"))

        session.begin()
        session.stop()
        recorder.begin()
        recorder.requestStop()
        session.begin()
        while (worker.pending > 0 || deliver.pending > 0) {
            deliver.runAll()
            worker.runAll()
        }

        // The stopped starts never reached their open; the only open that ran is the last start's.
        assertEquals("only the last start opened anything", listOf("+test1"), ledger.log)
        assertTrue(session.active)
    }

    @Test
    fun `stopped and restarted while its open is still running - what it opens late is released first`() {
        val ledger = Ledger()
        val worker = ManualExecutor()
        val deliver = ManualExecutor()
        lateinit var session: MicTestSession
        session = session(AudioStartup(worker, deliver), opener(ledger, "test") { n ->
            if (n == 1) {
                // The owner acts while the worker is still inside the first open.
                session.stop()
                session.begin()
            }
        })

        session.begin()
        while (worker.pending > 0 || deliver.pending > 0) {
            deliver.runAll()
            worker.runAll()
        }

        assertEquals(listOf("+test1", "-test1", "+test2"), ledger.log)
        assertEquals(1, ledger.maxHeld)
        assertTrue(session.active)
    }

    // ---- Delayed completion and release, on a real worker thread --------------------------------

    @Test
    fun `a slow native release holds the next open until it has finished`() {
        val ledger = Ledger()
        val releaseGate = gate()
        val deliver = ManualExecutor()
        val session = session(AudioStartup(realWorker(), deliver), opener(ledger, "test", releaseGate))

        session.begin()
        waitFor("test1 delivered") { deliver.pending > 0 }
        deliver.runAll()
        assertTrue(session.active)

        session.stop()      // test1's release starts on the worker, and stalls
        session.begin()     // test2's open waits behind it
        Thread.sleep(200)
        assertEquals("test2 not opened while test1 is held", listOf("+test1"), ledger.log)

        releaseGate.countDown()
        waitFor("test2 delivered") { deliver.pending > 0 }
        deliver.runAll()
        assertEquals(listOf("+test1", "-test1", "+test2"), ledger.log)
        assertEquals(1, ledger.maxHeld)
    }

    @Test
    fun `a slow open stopped and restarted meanwhile - it is released when it returns, before the restart opens`() {
        val ledger = Ledger()
        val openGate = gate()
        val opening = CountDownLatch(1)
        val deliver = ManualExecutor()
        val session = session(AudioStartup(realWorker(), deliver), opener(ledger, "test") { n ->
            if (n == 1) {
                opening.countDown()
                openGate.await(5, TimeUnit.SECONDS)
            }
        })

        session.begin()
        assertTrue(opening.await(3, TimeUnit.SECONDS))
        session.stop()
        session.begin()
        openGate.countDown()    // test1's open returns late
        waitFor("test2 delivered") { deliver.pending > 0 && ledger.log.size == 3 }
        deliver.runAll()

        assertEquals(listOf("+test1", "-test1", "+test2"), ledger.log)
        assertEquals(1, ledger.maxHeld)
        assertTrue(session.active)
    }

    // ---- Watchdog timeout, then a restart ----------------------------------------------------------

    @Test
    fun `a start the watchdog timed out is released when its open returns, before a restart opens the microphone`() {
        val ledger = Ledger()
        val openGate = gate()
        val opening = CountDownLatch(1)
        val timing = VirtualStartupTiming()
        val watchdog = ManualWatchdog(timing)
        val deliver = ManualExecutor()
        val startup = AudioStartup(realWorker(), deliver, timing, watchdog)
        val session = session(startup, opener(ledger, "test") { n ->
            if (n == 1) {
                opening.countDown()
                openGate.await(5, TimeUnit.SECONDS)
            }
        })
        val errors = Collections.synchronizedList(mutableListOf<Exception>())

        session.begin(errors)
        assertTrue(opening.await(3, TimeUnit.SECONDS))
        watchdog.advance(StartupLimits().startupTimeoutMs)    // the stuck open times out
        assertTrue("got $errors", errors.single() is MicrophoneStartTimeoutException)

        session.begin(errors)    // refused at once while the open is still stuck
        deliver.runAll()
        assertTrue("got $errors", errors.last() is MicrophoneBusyException)
        assertEquals(emptyList<String>(), ledger.log)

        openGate.countDown()     // the stuck open returns: released, never used
        waitFor("test1 released") { ledger.log == listOf("+test1", "-test1") && startup.workerState() == AudioStartup.WorkerState.Idle }
        session.begin(errors)
        waitFor("test2 delivered") { deliver.pending > 0 }
        deliver.runAll()

        assertEquals(listOf("+test1", "-test1", "+test2"), ledger.log)
        assertEquals(1, ledger.maxHeld)
        assertTrue(session.active)
    }

    // ---- Rapid switching on a real worker and the main looper -----------------------------------------

    @Test
    fun `rapid switching between the microphone test and recording never has two owners`() {
        val ledger = Ledger()
        val startup = AudioStartup(realWorker(), mainThread)
        val session = session(startup, opener(ledger, "test") { Thread.sleep(1) })
        val recorder = recorder(startup, opener(ledger, "rec") { Thread.sleep(1) })
        val dir = tempFolder.newFolder()
        val random = java.util.Random(42)

        repeat(40) { round ->
            if (round % 2 == 0) session.begin() else recorder.begin(dir)
            if (random.nextBoolean()) Thread.sleep(random.nextInt(4).toLong())
            if (random.nextBoolean()) shadowOf(Looper.getMainLooper()).idle()
            if (round % 2 == 0) session.stop() else recorder.requestStop()
        }
        session.begin()
        awaitStartupDelivered { session.starting }
        assertTrue(session.active)
        session.stop()
        waitFor("everything released") { ledger.holding.isEmpty() && startup.workerState() == AudioStartup.WorkerState.Idle }

        assertEquals("never two owners: ${ledger.log}", 1, ledger.maxHeld)
    }
}
