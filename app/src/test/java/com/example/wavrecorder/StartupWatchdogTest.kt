package com.example.wavrecorder

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * [AudioStartup]'s watchdog: a native open, start, stop or release that never returns stalls the
 * serialized audio worker, and the watchdog -- on the owner's thread, independent of that worker --
 * keeps every start bounded and every result exactly-once, without ever opening a second
 * microphone while the first is still held.
 *
 * The worker is a real single thread here, as in production, so a stalled call really blocks it;
 * the watchdog runs on virtual time ([ManualWatchdog]) and deliveries are run by the test (the
 * owner's thread), so every timeout is exact.
 */
@RunWith(RobolectricTestRunner::class)
class StartupWatchdogTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun context() = ApplicationProvider.getApplicationContext<Context>()

    private val timing = VirtualStartupTiming()
    private val watchdog = ManualWatchdog(timing)
    private val deliver = ManualExecutor()
    private val limits = StartupLimits()
    private val workerThread = Executors.newSingleThreadExecutor { Thread(it, "AudioStartup").apply { isDaemon = true } }

    @After
    fun tearDown() {
        workerThread.shutdownNow()
    }

    private fun startup() = AudioStartup(workerThread, deliver, timing, watchdog, limits)

    /** Counts sources held at once (opened and not yet released). */
    private class Owners {
        val open = AtomicInteger()
        val max = AtomicInteger()
        fun acquired() { max.accumulateAndGet(open.incrementAndGet(), ::maxOf) }
        fun released() { open.decrementAndGet() }
    }

    /** A source whose native stop and/or release can stall until [unblock]. */
    private class Source(
        val name: String,
        private val owners: Owners,
        private val stallStop: Boolean = false,
        private val stallRelease: Boolean = false
    ) : AudioSource {
        val unblock = CountDownLatch(1)
        /** Counted down once its stop or release has begun (on the worker). */
        val shuttingDown = CountDownLatch(1)
        @Volatile var starts = 0
        @Volatile var releases = 0
        @Volatile private var held = false

        fun opened() = apply {
            held = true
            owners.acquired()
        }

        override fun startRecording() { starts++ }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = 0
        override fun stop() {
            shuttingDown.countDown()
            if (stallStop) unblock.await(10, TimeUnit.SECONDS)
        }
        override fun release() {
            shuttingDown.countDown()
            if (stallRelease) unblock.await(10, TimeUnit.SECONDS)
            releases++
            if (held) {
                held = false
                owners.released()
            }
        }
        override fun describeMicrophone() = MicrophoneInfo(name, isExternal = true, verified = true)
    }

    private class Calls {
        val errors = mutableListOf<Exception>()
        val microphones = mutableListOf<MicrophoneInfo>()
    }

    private fun WavRecorder.begin(calls: Calls) = start(
        context(),
        WavRecorder.NextTarget { OutputTarget.FileTarget(File(tempFolder.newFolder(), "part.wav").apply { createNewFile() }) },
        onSegmentStarted = {}, onAmplitude = {},
        onError = { calls.errors += it },
        onMicrophoneInfo = { calls.microphones += it }
    )

    /** Waits, in real time, for work on the real worker thread. */
    private fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(2)
        check(condition()) { "timed out waiting: $what" }
    }

    private fun awaitWorkerIdle(startup: AudioStartup) = waitFor("the worker to finish everything") {
        startup.workerState() == AudioStartup.WorkerState.Idle && startup.queuedTasks == 0
    }

    /** [action] returns promptly on the calling (owner's, i.e. main) thread, whatever the worker is doing. */
    private fun assertPrompt(what: String, action: () -> Unit) {
        val started = System.nanoTime()
        action()
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("$what took $tookMs ms", tookMs < 200)
    }

    @Test
    fun `a start queued behind a stalled release times out visibly, once, and never opens the microphone later`() {
        val owners = Owners()
        val first = Source("first", owners, stallRelease = true)
        var opens = 0
        val startup = startup()
        val recorder = WavRecorder(startup = startup, openAudioSource = {
            opens++
            WavRecorder.RecorderConfig((if (opens == 1) first else Source("second", owners)).opened(), 48000, 4096)
        })
        recorder.begin(Calls())
        waitFor("the first microphone to open") { deliver.pending > 0 }
        deliver.runAll()
        assertTrue(recorder.isActive)

        recorder.requestStop()                       // the release stalls on the worker...
        first.shuttingDown.await(5, TimeUnit.SECONDS)
        val queued = Calls()
        assertPrompt("a start behind the stalled release") { recorder.begin(queued) }
        assertTrue("...and this start waits behind it", recorder.isStarting)

        watchdog.advance(limits.startupTimeoutMs - 1)
        assertTrue("not before the limit", recorder.isStarting)
        watchdog.advance(1)
        val timeout = queued.errors.single() as MicrophoneStartTimeoutException
        assertTrue("it says what it waited for", timeout.waitedForRelease)
        assertFalse("no longer starting", recorder.isStarting || recorder.isActive)

        // The release is now stalled: a further start is refused at once, not queued behind it.
        val queuedTasks = startup.queuedTasks
        val refused = Calls()
        recorder.begin(refused)
        deliver.runAll()
        assertTrue(refused.errors.single() is MicrophoneBusyException)
        assertEquals("nothing more queued", queuedTasks, startup.queuedTasks)
        // Nor by a refused start that is stopped before its refusal arrives.
        recorder.begin(Calls())
        recorder.requestStop()
        deliver.runAll()
        assertEquals(queuedTasks, startup.queuedTasks)

        first.unblock.countDown()                     // the release finally returns
        awaitWorkerIdle(startup)
        deliver.runAll()
        assertEquals("the timed-out start never opened a microphone", 1, opens)
        assertEquals(1, first.releases)
        assertEquals("exactly one result each", listOf(1, 1), listOf(queued.errors.size, refused.errors.size))
        assertEquals(1, owners.max.get())
    }

    @Test
    fun `a stalled open keeps the caller responsive, times out once, and what it opens late is released, never used`() {
        val owners = Owners()
        val entered = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val late = Source("late", owners)
        val startup = startup()
        val recorder = WavRecorder(startup = startup, openAudioSource = {
            entered.countDown()
            unblock.await(10, TimeUnit.SECONDS)           // a native open/start that doesn't return
            WavRecorder.RecorderConfig(late.opened(), 48000, 4096)
        })
        val calls = Calls()

        assertPrompt("start") { recorder.begin(calls) }
        entered.await(5, TimeUnit.SECONDS)
        watchdog.advance(limits.startupTimeoutMs)
        assertFalse((calls.errors.single() as MicrophoneStartTimeoutException).waitedForRelease)
        assertEquals(WavRecorder.State.IDLE, recorder.state)

        // Still stuck in the abandoned open: new starts are refused rather than queued behind it.
        val refused = Calls()
        assertPrompt("a start while stalled") { recorder.begin(refused) }
        deliver.runAll()
        assertTrue(refused.errors.single() is MicrophoneBusyException)

        unblock.countDown()                               // the native call finally returns...
        awaitWorkerIdle(startup)
        deliver.runAll()
        assertEquals("...and what it opened is released on the worker", 1, late.releases)
        assertFalse("never activated", recorder.isActive)
        assertTrue(calls.microphones.isEmpty())
        assertEquals("no late second result", 1, calls.errors.size)
        assertEquals(0, owners.open.get())
    }

    @Test
    fun `a stalled stop keeps the caller responsive, refuses recording and the microphone test alike, and recovers when it returns`() {
        val owners = Owners()
        val stuck = Source("test", owners, stallStop = true)
        var opens = 0
        val startup = startup()
        val open: StartupScope.(Context) -> WavRecorder.RecorderConfig = {
            opens++
            WavRecorder.RecorderConfig((if (opens == 1) stuck else Source("next", owners)).opened(), 48000, 4096)
        }
        val test = MicTestSession(openAudioSource = open, startup = startup)
        test.start(context(), {}, {}, {})
        waitFor("the test's microphone to open") { deliver.pending > 0 }
        deliver.runAll()
        assertTrue(test.active)

        assertPrompt("stopping the test") { test.stop() }
        stuck.shuttingDown.await(5, TimeUnit.SECONDS)
        watchdog.advance(limits.releaseStallMs)
        assertTrue(startup.workerState() is AudioStartup.WorkerState.Stalled)

        val testErrors = mutableListOf<Exception>()
        assertPrompt("a test start while stalled") { test.start(context(), {}, {}, { testErrors += it }) }
        deliver.runAll()
        assertTrue(testErrors.single() is MicrophoneBusyException)
        assertEquals(MicTestSession.State.IDLE, test.state)
        val recorder = WavRecorder(startup = startup, openAudioSource = open)
        val calls = Calls()
        recorder.begin(calls)
        deliver.runAll()
        assertTrue("recording is refused the same way", calls.errors.single() is MicrophoneBusyException)
        assertEquals(1, opens)

        stuck.unblock.countDown()
        awaitWorkerIdle(startup)
        assertEquals(1, stuck.releases)

        // Recovered: the next start opens a microphone normally -- after the old one was released.
        recorder.begin(Calls())
        waitFor("the next microphone to open") { deliver.pending > 0 }
        deliver.runAll()
        assertTrue(recorder.isActive)
        assertEquals(1, owners.max.get())
        recorder.requestStop()
        awaitWorkerIdle(startup)
    }

    @Test
    fun `retries against a stuck microphone never have two owners, and each attempt gets at most one result`() {
        val owners = Owners()
        val entered = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        var opens = 0
        val startup = startup()
        val recorder = WavRecorder(startup = startup, openAudioSource = {
            val n = ++opens
            if (n == 1) {
                entered.countDown()
                unblock.await(10, TimeUnit.SECONDS)
            }
            WavRecorder.RecorderConfig(Source("source $n", owners).opened(), 48000, 4096)
        })
        val attempts = List(4) { Calls() }

        recorder.begin(attempts[0])
        entered.await(5, TimeUnit.SECONDS)
        watchdog.advance(1_000)
        recorder.requestStop()                     // the user gives up on it: cancelled, no result
        recorder.begin(attempts[1])                // queued behind the stuck open
        assertTrue(recorder.isStarting)
        watchdog.advance(limits.startupTimeoutMs)  // ...and times out there
        recorder.begin(attempts[2])                // the worker is stalled now: refused at once
        deliver.runAll()

        assertTrue("cancelled: nothing reported", attempts[0].errors.isEmpty())
        assertTrue(attempts[1].errors.single() is MicrophoneStartTimeoutException)
        assertTrue(attempts[2].errors.single() is MicrophoneBusyException)
        assertTrue("bounded: ${startup.queuedTasks} queued", startup.queuedTasks <= 3)

        unblock.countDown()
        awaitWorkerIdle(startup)
        deliver.runAll()
        assertEquals("the queued attempt never opened anything", 1, opens)
        assertEquals(0, owners.open.get())

        recorder.begin(attempts[3])
        waitFor("a microphone to open") { deliver.pending > 0 }
        deliver.runAll()
        assertTrue(recorder.isActive)
        assertEquals("never two owners", 1, owners.max.get())
        assertEquals(listOf(0, 1, 1, 0), attempts.map { it.errors.size })
        recorder.requestStop()
        awaitWorkerIdle(startup)
    }

    // ---- Deterministic, single-threaded ------------------------------------------------------------

    @Test
    fun `a success or a failure that arrives after the timeout is suppressed, and a late source is cleaned up`() {
        val worker = ManualExecutor()
        val startup = AudioStartup(worker, deliver, timing, watchdog, limits)
        val source = Source("late", Owners())
        // Time passes inside the native open: the watchdog fires while it is still running.
        val lateSuccess = WavRecorder(startup = startup, openAudioSource = {
            watchdog.advance(limits.startupTimeoutMs)
            WavRecorder.RecorderConfig(source.opened(), 48000, 4096)
        })
        val calls = Calls()
        lateSuccess.begin(calls)
        worker.runAll()
        deliver.runAll()
        worker.runAll()
        assertTrue(calls.errors.single() is MicrophoneStartTimeoutException)
        assertTrue(calls.microphones.isEmpty())
        assertFalse(lateSuccess.isActive)
        assertEquals("released on the worker, never used", 1, source.releases)

        val lateFailure = WavRecorder(startup = startup, openAudioSource = {
            watchdog.advance(limits.startupTimeoutMs)
            throw IllegalStateException("the microphone failed, too late")
        })
        val failure = Calls()
        lateFailure.begin(failure)
        worker.runAll()
        deliver.runAll()
        assertTrue("only the timeout", failure.errors.single() is MicrophoneStartTimeoutException)
    }

    @Test
    fun `a refused microphone whose release stalls past the limit ends the start once, and the late refusal is dropped`() {
        val worker = ManualExecutor()
        val startup = AudioStartup(worker, deliver, timing, watchdog, limits)
        val source = Source("phone", Owners())
        val recorder = WavRecorder(startup = startup, openAudioSource = { WavRecorder.RecorderConfig(source.opened(), 48000, 4096) })
        val errors = mutableListOf<Exception>()
        recorder.start(
            context(), WavRecorder.NextTarget { throw AssertionError("no file for a refused microphone") },
            onSegmentStarted = {}, onAmplitude = {}, onError = { errors += it },
            acceptMicrophone = { ExternalMicrophoneNotInUseException("not the external microphone") }
        )
        worker.runNext()   // opened
        deliver.runAll()   // refused: its release is queued, and the start stays STARTING until it's done
        assertTrue(recorder.isStarting)

        watchdog.advance(limits.startupTimeoutMs)   // the release hasn't run by then
        assertTrue(errors.single() is MicrophoneStartTimeoutException)
        assertEquals(WavRecorder.State.IDLE, recorder.state)

        worker.runAll()    // the release finally runs...
        deliver.runAll()   // ...and its report arrives late
        assertEquals(1, source.releases)
        assertEquals("the refusal isn't reported on top of the timeout", 1, errors.size)
    }

    @Test
    fun `a start that is delivered in time disarms its timeout`() {
        val worker = ManualExecutor()
        val startup = AudioStartup(worker, deliver, timing, watchdog, limits)
        val source = Source("ok", Owners())
        val recorder = WavRecorder(startup = startup, openAudioSource = { WavRecorder.RecorderConfig(source.opened(), 48000, 4096) })
        val calls = Calls()
        recorder.begin(calls)
        assertEquals(1, watchdog.pending)
        worker.runAll()
        deliver.runAll()
        assertTrue(recorder.isActive)
        assertEquals(0, watchdog.pending)

        watchdog.advance(limits.startupTimeoutMs * 2)
        assertTrue("no timeout after success", calls.errors.isEmpty())
        assertTrue(recorder.isActive)
        recorder.requestStop()
        worker.runAll()
        assertEquals(1, source.releases)
    }
}
