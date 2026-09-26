package com.example.wavrecorder

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Capture startup off the main thread ([AudioStartup]) for [WavRecorder] and [MicTestSession]:
 * STARTING while the source is opened on the worker; a stop during STARTING cancels it for good;
 * results of cancelled or superseded starts are released, never used; one owner of the microphone
 * at a time. The worker and the delivery thread are [ManualExecutor]s, so every interleaving is
 * exact -- except where the real shared worker itself is what's being tested.
 */
@RunWith(RobolectricTestRunner::class)
class AsyncStartupTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun context() = ApplicationProvider.getApplicationContext<Context>()

    /** Counts how many sources are open (from being opened until released) at once. */
    private class Owners {
        val open = AtomicInteger()
        val max = AtomicInteger()
        fun acquired() { max.accumulateAndGet(open.incrementAndGet(), ::maxOf) }
        fun released() { open.decrementAndGet() }
    }

    /** A source whose lifecycle is counted. Reads return nothing (paced by EmptyReadBackoff). */
    private class TrackedSource(val name: String, private val owners: Owners? = null) : AudioSource {
        @Volatile var starts = 0
        @Volatile var stops = 0
        @Volatile var releases = 0
        @Volatile private var held = false

        fun opened() = apply {
            held = true
            owners?.acquired()
        }

        override fun startRecording() { starts++ }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = 0
        override fun stop() { stops++ }
        override fun release() {
            releases++
            if (held) {
                held = false
                owners?.released()
            }
        }
        override fun describeMicrophone() = MicrophoneInfo(name, isExternal = true, verified = true)
    }

    private val worker = ManualExecutor()
    private val deliver = ManualExecutor()
    private val manualStartup = AudioStartup(worker, deliver)

    /** Runs the worker and the deliveries until neither has anything left. */
    private fun settle() {
        while (worker.pending > 0 || deliver.pending > 0) {
            worker.runAll()
            deliver.runAll()
        }
    }

    private class Calls {
        var errors = mutableListOf<Exception>()
        var microphones = mutableListOf<MicrophoneInfo>()
        val targets = AtomicInteger()
    }

    private fun WavRecorder.begin(calls: Calls, dir: File = tempFolder.newFolder()) = start(
        context(),
        WavRecorder.NextTarget {
            calls.targets.incrementAndGet()
            OutputTarget.FileTarget(File(dir, "part${calls.targets.get()}.wav").apply { createNewFile() })
        },
        onSegmentStarted = {}, onAmplitude = {},
        onError = { calls.errors += it },
        onMicrophoneInfo = { calls.microphones += it }
    )

    // ---- Off the main thread -------------------------------------------------------------------

    @Test
    fun `recording negotiation runs on the startup worker, never the main thread, and start does not wait for it`() {
        val release = CountDownLatch(1)
        var openThread: Thread? = null
        var onMainLooper: Boolean? = null
        val source = TrackedSource("USB Mic")
        val recorder = WavRecorder(openAudioSource = {
            openThread = Thread.currentThread()
            onMainLooper = Looper.myLooper() == Looper.getMainLooper()
            release.await(5, TimeUnit.SECONDS) // a slow negotiation
            WavRecorder.RecorderConfig(source.opened(), 48000, 4096)
        })
        val calls = Calls()

        recorder.begin(calls)

        assertTrue("start() returned while the negotiation is still running", recorder.isStarting)
        assertEquals(WavRecorder.State.STARTING, recorder.state)
        release.countDown()
        awaitStartupDelivered { recorder.isStarting }
        assertEquals(false, onMainLooper)
        assertNotSame(Looper.getMainLooper().thread, openThread)
        assertEquals("AudioStartup", openThread!!.name)
        assertTrue(recorder.isActive)
        recorder.stop()
        assertEquals(1, source.releases)
    }

    @Test
    fun `microphone test negotiation runs on the same startup worker, never the main thread`() {
        val release = CountDownLatch(1)
        var openThread: Thread? = null
        val session = MicTestSession(openAudioSource = {
            openThread = Thread.currentThread()
            release.await(5, TimeUnit.SECONDS)
            WavRecorder.RecorderConfig(TrackedSource("USB Mic").opened(), 48000, 4096)
        })

        session.start(context(), {}, {}, {})

        assertTrue(session.starting)
        assertEquals(MicTestSession.State.STARTING, session.state)
        release.countDown()
        awaitStartupDelivered { session.starting }
        assertEquals("AudioStartup", openThread!!.name)
        assertTrue(session.active)
        session.stop()
    }

    // ---- Handoff ---------------------------------------------------------------------------------

    @Test
    fun `the accepted source is handed over started, and is not stopped or released during the handoff`() {
        val source = TrackedSource("USB Mic")
        val recorder = WavRecorder(startup = manualStartup, openAudioSource = { WavRecorder.RecorderConfig(source.opened(), 48000, 4096) })
        val calls = Calls()

        recorder.begin(calls)
        assertEquals("nothing opened on the caller's thread", 0, source.starts)
        worker.runAll()
        assertEquals("started on the worker", 1, source.starts)
        assertTrue(recorder.isStarting)
        deliver.runAll()

        assertEquals(WavRecorder.State.RECORDING, recorder.state)
        assertEquals(1, source.starts)
        assertEquals(0, source.stops)
        assertEquals(0, source.releases)
        assertEquals(listOf(MicrophoneInfo("USB Mic", isExternal = true, verified = true)), calls.microphones)
        worker.runAll()
        assertEquals("nothing queued to release it", 0, source.releases)

        val pending = recorder.requestStop()
        assertEquals("the stop itself only changed state; stop and release run on the worker", 0, source.stops + source.releases)
        assertFalse(pending.sourceReleased!!.isDone)
        worker.runAll()
        assertEquals(1, source.stops)
        assertEquals(1, source.releases)
        assertTrue(pending.sourceReleased!!.isDone)
        assertEquals(WavRecorder.FinalizeResult.Ok, recorder.awaitFinalization(pending))
        assertEquals(WavRecorder.State.IDLE, recorder.state)
    }

    // ---- Stop during STARTING --------------------------------------------------------------------

    @Test
    fun `stopping a recording while it is starting means it never starts later, and nothing is reported`() {
        val source = TrackedSource("USB Mic")
        val recorder = WavRecorder(startup = manualStartup, openAudioSource = { WavRecorder.RecorderConfig(source.opened(), 48000, 4096) })
        val calls = Calls()

        recorder.begin(calls)
        assertEquals(WavRecorder.FinalizeResult.Ok, recorder.stop())
        assertEquals(WavRecorder.State.IDLE, recorder.state)
        settle()

        assertFalse(recorder.isActive)
        assertEquals("the startup noticed the cancellation before opening anything", 0, source.starts)
        assertEquals(0, calls.targets.get())
        assertTrue(calls.errors.isEmpty())
        assertTrue(calls.microphones.isEmpty())
    }

    @Test
    fun `a source already opened when the stop arrives is released on the worker, never used`() {
        val source = TrackedSource("USB Mic")
        val recorder = WavRecorder(startup = manualStartup, openAudioSource = { WavRecorder.RecorderConfig(source.opened(), 48000, 4096) })
        val calls = Calls()

        recorder.begin(calls)
        worker.runAll()                 // opened and started; its delivery is queued
        assertEquals(1, source.starts)
        recorder.requestStop()          // the stop lands while it's on its way
        deliver.runAll()                // the delivery finds nothing to hand over
        assertEquals("released on the worker, not on the delivery thread", 0, source.releases)
        worker.runAll()

        assertEquals(1, source.stops)
        assertEquals(1, source.releases)
        assertFalse(recorder.isActive)
        assertEquals(0, calls.targets.get())
        assertTrue(calls.microphones.isEmpty())
    }

    @Test
    fun `stopping a microphone test while it is starting means it never starts later`() {
        val source = TrackedSource("USB Mic")
        val session = MicTestSession(startup = manualStartup, openAudioSource = { WavRecorder.RecorderConfig(source.opened(), 48000, 4096) })
        var infos = 0
        var errors = 0

        session.start(context(), onMicrophoneInfo = { infos++ }, onLevel = {}, onError = { errors++ })
        worker.runAll()
        session.stop()
        assertEquals(MicTestSession.State.IDLE, session.state)
        settle()

        assertFalse(session.active)
        assertEquals(1, source.releases)
        assertEquals(0, infos)
        assertEquals(0, errors)
    }

    // ---- Superseded starts -----------------------------------------------------------------------

    @Test
    fun `a stale result from an earlier start can never replace a newer one`() {
        val first = TrackedSource("first")
        val second = TrackedSource("second")
        var opens = 0
        val recorder = WavRecorder(startup = manualStartup, openAudioSource = {
            opens++
            WavRecorder.RecorderConfig((if (opens == 1) first else second).opened(), 48000, 4096)
        })
        val calls = Calls()

        recorder.begin(calls)
        worker.runAll()           // the first start's source is ready and on its way...
        recorder.requestStop()    // ...but that start is stopped
        recorder.begin(calls)     // and a new one begins before the first's delivery arrives
        settle()

        assertTrue(recorder.isActive)
        assertEquals("only the newer start reported", listOf("second"), calls.microphones.map { it.label })
        assertEquals(1, first.releases)
        assertEquals(0, second.releases)
        // The recording thread creates the newer session's first file (and only that one) as it starts.
        val deadline = System.currentTimeMillis() + 2000
        while (calls.targets.get() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(5)
        recorder.requestStop()
        settle()
        assertEquals(1, calls.targets.get())
        assertEquals(1, second.releases)
    }

    @Test
    fun `a start while one is already starting is ignored`() {
        var opens = 0
        val recorder = WavRecorder(startup = manualStartup, openAudioSource = {
            opens++
            WavRecorder.RecorderConfig(TrackedSource("USB Mic").opened(), 48000, 4096)
        })
        val calls = Calls()

        recorder.begin(calls)
        recorder.begin(calls)
        settle()

        assertEquals(1, opens)
        assertTrue(recorder.isActive)
        recorder.requestStop()
        settle()
    }

    @Test
    fun `a start while recording runs no second format search and never changes the session's format`() {
        var opens = 0
        val format = PcmFormat.of(96000, PcmEncoding.PCM_24_PACKED, 2)
        val recorder = WavRecorder(startup = manualStartup, openAudioSource = {
            opens++
            WavRecorder.RecorderConfig(TrackedSource("USB Mic").opened(), format, 6000)
        })
        recorder.begin(Calls())
        settle()
        assertTrue(recorder.isActive)

        recorder.begin(Calls())
        settle()

        assertEquals("negotiated once, at the start", 1, opens)
        assertTrue(recorder.isActive)
        assertEquals(format, recorder.sessionFormat)
        recorder.requestStop()
        settle()
    }

    @Test
    fun `a cancelled startup's source is released before the next startup can open the microphone`() {
        val owners = Owners()
        val recorder = WavRecorder(startup = manualStartup, openAudioSource = {
            WavRecorder.RecorderConfig(TrackedSource("USB Mic", owners).opened(), 48000, 4096)
        })
        val calls = Calls()

        recorder.begin(calls)
        worker.runAll()           // source 1 opened, in transit
        recorder.requestStop()    // queues its release on the worker...
        recorder.begin(calls)     // ...ahead of this start's work
        settle()

        assertEquals("never two open at once", 1, owners.max.get())
        assertEquals(1, owners.open.get())
        recorder.requestStop()
        assertEquals("released on the worker, not by the stop call itself", 1, owners.open.get())
        settle()
        assertEquals(0, owners.open.get())
    }

    @Test
    fun `rapid start, stop and start on the real startup worker never has two owners of the microphone`() {
        val owners = Owners()
        val sources = mutableListOf<TrackedSource>()
        val recorder = WavRecorder(openAudioSource = {
            Thread.sleep(2) // some real negotiation work
            val source = TrackedSource("USB Mic", owners).opened()
            synchronized(sources) { sources += source }
            WavRecorder.RecorderConfig(source, 48000, 4096)
        })
        val calls = Calls()
        val dir = tempFolder.newFolder()

        repeat(15) { round ->
            recorder.begin(calls, dir)
            if (round % 3 == 0) Thread.sleep(3) // sometimes let the startup finish first
            shadowOf(Looper.getMainLooper()).idle()
            recorder.stop()
        }
        recorder.begin(calls, dir)
        awaitStartupDelivered { recorder.isStarting }
        assertTrue(recorder.isActive)
        recorder.stop()
        // Let any cancelled startups finish cleaning up on the worker.
        val deadline = System.currentTimeMillis() + 3000
        while (owners.open.get() > 0 && System.currentTimeMillis() < deadline) Thread.sleep(5)

        assertEquals("never two open at once", 1, owners.max.get())
        assertEquals("every source was released", 0, owners.open.get())
        assertTrue(synchronized(sources) { sources.all { it.releases == 1 } })
    }

    @Test
    fun `rapid start, stop and start of the microphone test never has two owners either`() {
        val owners = Owners()
        val session = MicTestSession(openAudioSource = {
            Thread.sleep(2)
            WavRecorder.RecorderConfig(TrackedSource("USB Mic", owners).opened(), 48000, 4096)
        })

        repeat(15) { round ->
            session.start(context(), {}, {}, {})
            if (round % 3 == 0) Thread.sleep(3)
            shadowOf(Looper.getMainLooper()).idle()
            session.stop()
        }
        session.start(context(), {}, {}, {})
        awaitStartupDelivered { session.starting }
        assertTrue(session.active)
        session.stop()
        val deadline = System.currentTimeMillis() + 3000
        while (owners.open.get() > 0 && System.currentTimeMillis() < deadline) Thread.sleep(5)

        assertEquals(1, owners.max.get())
        assertEquals(0, owners.open.get())
    }

    // ---- Failed startups -------------------------------------------------------------------------

    @Test
    fun `a failed startup reports its error once and never creates a file`() {
        val boom = FormatNegotiationException("no usable format", null)
        val recorder = WavRecorder(startup = manualStartup, openAudioSource = { throw boom })
        val calls = Calls()

        recorder.begin(calls)
        settle()

        assertEquals(listOf<Exception>(boom), calls.errors)
        assertEquals(0, calls.targets.get())
        assertEquals(WavRecorder.State.IDLE, recorder.state)
    }

    @Test
    fun `a failure after the start was stopped is never reported`() {
        val recorder = WavRecorder(startup = manualStartup, openAudioSource = { throw IllegalStateException("late") })
        val calls = Calls()

        recorder.begin(calls)
        recorder.requestStop()
        settle()

        assertTrue(calls.errors.isEmpty())
    }

    @Test
    fun `a source that fails to start is released on the worker and its error reported`() {
        val source = object : AudioSource {
            var releases = 0
            override fun startRecording() = throw IllegalStateException("mic busy")
            override fun read(buffer: ByteArray, offset: Int, length: Int) = 0
            override fun stop() {}
            override fun release() { releases++ }
        }
        val recorder = WavRecorder(startup = manualStartup, openAudioSource = { WavRecorder.RecorderConfig(source, 48000, 4096) })
        val calls = Calls()

        recorder.begin(calls)
        worker.runAll()
        assertEquals(1, source.releases)
        deliver.runAll()

        assertEquals("mic busy", calls.errors.single().message)
        assertEquals(0, calls.targets.get())
    }

    @Test
    fun `a refused microphone is reported only after it has been released, and never recorded from`() {
        val source = TrackedSource("Phone microphone")
        val recorder = WavRecorder(startup = manualStartup, openAudioSource = { WavRecorder.RecorderConfig(source.opened(), 48000, 4096) })
        val refusal = ExternalMicrophoneNotInUseException("not the external microphone")
        val calls = Calls()

        recorder.start(
            context(), WavRecorder.NextTarget { calls.targets.incrementAndGet(); throw AssertionError("no file") },
            onSegmentStarted = {}, onAmplitude = {}, onError = { calls.errors += it },
            onMicrophoneInfo = { calls.microphones += it },
            acceptMicrophone = { refusal }
        )
        worker.runAll()                 // opened
        deliver.runAll()                // refused: its release is queued on the worker...
        assertTrue("...and the start is still pending until that release is done", recorder.isStarting)
        assertTrue(calls.errors.isEmpty())
        assertEquals(0, source.releases)
        worker.runAll()                 // released
        assertEquals(1, source.releases)
        assertTrue("only now is the reason posted back", calls.errors.isEmpty())
        deliver.runAll()

        assertFalse(recorder.isActive)
        assertFalse(recorder.isStarting)
        assertSame(refusal, calls.errors.single())
        assertEquals("the refused microphone is still reported, for the label", 1, calls.microphones.size)
        assertEquals(0, calls.targets.get())
    }

    @Test
    fun `a stop while a refused microphone is being released suppresses the late report, and still releases it`() {
        val source = TrackedSource("Phone microphone")
        val recorder = WavRecorder(startup = manualStartup, openAudioSource = { WavRecorder.RecorderConfig(source.opened(), 48000, 4096) })
        val calls = Calls()
        recorder.start(
            context(), WavRecorder.NextTarget { throw AssertionError("no file") },
            onSegmentStarted = {}, onAmplitude = {}, onError = { calls.errors += it },
            onMicrophoneInfo = { calls.microphones += it },
            acceptMicrophone = { ExternalMicrophoneNotInUseException("not external") }
        )
        worker.runAll()
        deliver.runAll()

        recorder.requestStop()
        settle()

        assertEquals(1, source.releases)
        assertTrue("nothing reported for a start that was stopped", calls.errors.isEmpty() && calls.microphones.isEmpty())
        assertEquals(WavRecorder.State.IDLE, recorder.state)
    }

    @Test
    fun `a platform API missing on this Android version fails the start instead of leaving it STARTING`() {
        val recorder = WavRecorder(startup = manualStartup, openAudioSource = { throw NoSuchMethodError("AudioRecord.newApi") })
        val calls = Calls()

        recorder.begin(calls)
        settle()

        assertEquals(WavRecorder.State.IDLE, recorder.state)
        assertTrue(calls.errors.single().cause is NoSuchMethodError)
    }

    // ---- AudioStartup itself ---------------------------------------------------------------------

    @Test
    fun `a cancelled startup never calls back, and a declined source's reason is reported only after its release`() {
        val startup = AudioStartup(worker, deliver)
        val source = TrackedSource("a")
        val events = mutableListOf<String>()

        val cancelled = startup.newScope()
        startup.launch(cancelled, context(), { WavRecorder.RecorderConfig(source.opened(), 48000, 4096) },
            onReady = { events += "ready"; AudioStartup.Decision.Keep }, onFailed = { events += "failed" })
        startup.cancel(cancelled)
        settle()
        assertTrue(events.isEmpty())

        val declined = TrackedSource("b")
        val scope = startup.newScope()
        startup.launch(scope, context(), { WavRecorder.RecorderConfig(declined.opened(), 48000, 4096) },
            onReady = { AudioStartup.Decision.Discard { events += "reported with ${declined.releases} releases" } },
            onFailed = { events += "failed" })
        worker.runAll()
        deliver.runAll()
        assertTrue("not reported before the release", events.isEmpty())
        worker.runAll()
        deliver.runAll()

        assertEquals(listOf("reported with 1 releases"), events)
        assertEquals("released on the worker", 1, declined.releases)
        assertNull(scope.take())

        // Cancelled after declining but before the report arrives: released, never reported.
        val late = TrackedSource("c")
        val lateScope = startup.newScope()
        startup.launch(lateScope, context(), { WavRecorder.RecorderConfig(late.opened(), 48000, 4096) },
            onReady = { AudioStartup.Decision.Discard { events += "late" } }, onFailed = { events += "failed" })
        worker.runAll()
        deliver.runAll()
        startup.cancel(lateScope)
        settle()
        assertEquals(1, late.releases)
        assertFalse(events.contains("late"))
    }

    // ---- Active shutdown on the audio worker ---------------------------------------------------

    /** A source whose native stop() or release() stalls until [unblock] -- a misbehaving USB driver. */
    private class StallingSource(private val stallStop: Boolean, private val stallRelease: Boolean) : AudioSource {
        val unblock = CountDownLatch(1)
        @Volatile var stopThread: Thread? = null
        @Volatile var releaseThread: Thread? = null
        @Volatile var releases = 0
        override fun startRecording() {}
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = 0
        override fun stop() {
            stopThread = Thread.currentThread()
            if (stallStop) unblock.await(5, TimeUnit.SECONDS)
        }
        override fun release() {
            releaseThread = Thread.currentThread()
            if (stallRelease) unblock.await(5, TimeUnit.SECONDS)
            releases++
        }
    }

    private fun assertStopDoesNotBlock(source: StallingSource) {
        val recorder = WavRecorder(openAudioSource = { WavRecorder.RecorderConfig(source, 48000, 4096) })
        recorder.begin(Calls())
        awaitStartupDelivered { recorder.isStarting }
        assertTrue(recorder.isActive)

        val started = System.nanoTime()
        val pending = recorder.requestStop()
        val tookMs = (System.nanoTime() - started) / 1_000_000

        assertTrue("requestStop() returned in $tookMs ms while the native call stalls", tookMs < 200)
        assertFalse(recorder.isActive)
        val deadline = System.currentTimeMillis() + 2000
        while ((if (source.stopThread == null) true else false) && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertEquals("AudioStartup", source.stopThread?.name)
        assertFalse(pending.sourceReleased!!.isDone)
        source.unblock.countDown()
        assertTrue(pending.sourceReleased!!.await(2000))
        assertEquals(1, source.releases)
        assertEquals("AudioStartup", source.releaseThread?.name)
    }

    @Test
    fun `a native stop that stalls never blocks the caller's thread`() = assertStopDoesNotBlock(StallingSource(stallStop = true, stallRelease = false))

    @Test
    fun `a native release that stalls never blocks the caller's thread`() = assertStopDoesNotBlock(StallingSource(stallStop = false, stallRelease = true))

    @Test
    fun `a microphone test's stalling stop never blocks the caller's thread either`() {
        val source = StallingSource(stallStop = true, stallRelease = true)
        val session = MicTestSession(openAudioSource = { WavRecorder.RecorderConfig(source, 48000, 4096) })
        session.start(context(), {}, {}, {})
        awaitStartupDelivered { session.starting }

        val started = System.nanoTime()
        session.stop()
        assertTrue((System.nanoTime() - started) / 1_000_000 < 200)
        assertFalse(session.active)
        source.unblock.countDown()
        val deadline = System.currentTimeMillis() + 2000
        while (source.releases == 0 && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertEquals(1, source.releases)
        assertEquals("AudioStartup", source.releaseThread?.name)
    }

    @Test
    fun `a new start can't open a microphone until the previous source's shutdown has finished`() {
        val order = java.util.Collections.synchronizedList(mutableListOf<String>())
        val first = StallingSource(stallStop = false, stallRelease = true)
        var opens = 0
        val recorder = WavRecorder(openAudioSource = {
            opens++
            if (opens == 1) {
                WavRecorder.RecorderConfig(first, 48000, 4096)
            } else {
                order += "second opened after ${first.releases} release(s) of the first"
                WavRecorder.RecorderConfig(TrackedSource("second").opened(), 48000, 4096)
            }
        })
        recorder.begin(Calls())
        awaitStartupDelivered { recorder.isStarting }

        recorder.requestStop()   // the first's release stalls on the worker...
        recorder.begin(Calls())  // ...and this start queues behind it
        Thread.sleep(100)
        assertTrue("the new startup hasn't opened anything while the old release is pending", order.isEmpty())
        assertTrue(recorder.isStarting)
        first.unblock.countDown()
        awaitStartupDelivered { recorder.isStarting }

        assertEquals(listOf("second opened after 1 release(s) of the first"), order)
        assertTrue(recorder.isActive)
        recorder.stop()
    }

    @Test
    fun `a fatal recording error still releases the source exactly once, on the worker`() {
        var releases = 0
        val source = object : AudioSource {
            override fun startRecording() {}
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = AudioRecordErrorDeadObject
            override fun stop() {}
            override fun release() { releases++ }
        }
        val recorder = WavRecorder(startup = manualStartup, openAudioSource = { WavRecorder.RecorderConfig(source, 48000, 4096) })
        val calls = Calls()
        recorder.begin(calls)
        settle()
        val deadline = System.currentTimeMillis() + 2000
        while (recorder.isActive && System.currentTimeMillis() < deadline) Thread.sleep(5)
        org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals("the error is reported", 1, calls.errors.size)

        recorder.requestStop() // what RecordingService does in response
        assertEquals(0, releases)
        settle()
        recorder.requestStop() // idempotent
        settle()
        assertEquals(1, releases)
    }

    private val AudioRecordErrorDeadObject = -6
}
