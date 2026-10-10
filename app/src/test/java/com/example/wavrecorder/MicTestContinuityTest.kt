package com.example.wavrecorder

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Rule
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * The microphone test's safeguards (audit findings F4 and F7), matching the recorder's: route and
 * disconnect checks run whatever the reads return, a source that delivers nothing ends the test,
 * silent audio doesn't, and intervals follow the monotonic clock.
 */
@RunWith(RobolectricTestRunner::class)
class MicTestContinuityTest {

    @get:Rule
    val isolation = TestIsolation()

    private fun context() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun waitFor(what: String, timeoutMs: Long = 3000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(2)
        }
        shadowOf(Looper.getMainLooper()).idle()
        check(condition()) { "timed out waiting: $what" }
    }

    /** Delivers [chunks] chunks of audio, then [afterwards]: empty reads (0) or silence. */
    private class Source(
        private val chunks: Int,
        private val silentAfterwards: Boolean = false,
        private val connected: () -> Boolean = { true }
    ) : AudioSource {
        val reads = AtomicInteger()
        override fun startRecording() {}
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            Thread.sleep(1) // no faster than a real microphone
            val n = reads.incrementAndGet()
            if (n <= chunks) {
                buffer.fill(5, offset, offset + length)
                return length
            }
            if (silentAfterwards) {
                buffer.fill(0, offset, offset + length)
                return length
            }
            Thread.sleep(1)
            return 0
        }
        override fun stop() {}
        override fun release() {}
        override fun isDeviceConnected(): Boolean = connected()
    }

    private fun MicTestSession.begin(): MutableList<Exception> {
        val errors = Collections.synchronizedList(mutableListOf<Exception>())
        start(context(), onMicrophoneInfo = {}, onLevel = {}, onError = { errors += it })
        return errors
    }

    @Test
    fun `a disconnected source that only returns zero bytes is still detected`() {
        val session = MicTestSession(startup = ImmediateStartup, routeCheckIntervalMs = 0,
            openAudioSource = { WavRecorder.RecorderConfig(Source(chunks = 0, connected = { false }), 48000, 4) })
        val errors = session.begin()

        waitFor("the disconnect is reported") { errors.isNotEmpty() }
        assertTrue(errors.single() is MicrophoneDisconnectedException)
        session.stop()
    }

    @Test
    fun `no audio at all for longer than the limit ends the test as stalled`() {
        val session = MicTestSession(startup = ImmediateStartup, noProgressTimeoutMs = 300,
            openAudioSource = { WavRecorder.RecorderConfig(Source(chunks = 2), 48000, 4) })
        val errors = session.begin()

        waitFor("the stall is reported") { errors.isNotEmpty() }
        assertTrue(errors.single() is MicrophoneStalledException)
        session.stop()
    }

    @Test
    fun `silent audio keeps the test running past the limit`() {
        val session = MicTestSession(startup = ImmediateStartup, noProgressTimeoutMs = 150,
            openAudioSource = { WavRecorder.RecorderConfig(Source(chunks = 2, silentAfterwards = true), 48000, 4) })
        val errors = session.begin()

        Thread.sleep(600)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(errors.isEmpty())
        assertTrue(session.active)
        session.stop()
    }

    @Test
    fun `route checks and the stall limit follow the injected monotonic clock`() {
        val now = AtomicLong(1_000_000)
        val connected = AtomicBoolean(true)
        val source = Source(chunks = 1_000_000, connected = { connected.get() })
        val session = MicTestSession(startup = ImmediateStartup, routeCheckIntervalMs = 1_000, clock = { now.get() },
            openAudioSource = { WavRecorder.RecorderConfig(source, 48000, 4) })
        val errors = session.begin()
        waitFor("reading") { source.reads.get() > 5 }

        connected.set(false)
        Thread.sleep(300) // real time passes; the monotonic clock doesn't
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(errors.isEmpty())

        now.addAndGet(1_000)
        waitFor("checked once due on the monotonic clock") { errors.isNotEmpty() }
        assertTrue(errors.single() is MicrophoneDisconnectedException)
        session.stop()
    }

    // ---- An independent progress watchdog (follow-up review R3) -----------------------------------

    /** A source whose read blocks until [release] (stop() and release() also let it go), then
     * optionally returns audio -- a late completion. */
    private class BlockingSource(private val lateAudio: Boolean = false) : AudioSource {
        val release = java.util.concurrent.CountDownLatch(1)
        val entered = java.util.concurrent.CountDownLatch(1)
        override fun startRecording() {}
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            entered.countDown()
            release.await(5, java.util.concurrent.TimeUnit.SECONDS)
            return if (lateAudio) { buffer.fill(9, offset, offset + length); length } else -1
        }
        override fun stop() { release.countDown() }
        override fun release() { release.countDown() }
    }

    private fun watchdogAlive() = Thread.getAllStackTraces().keys.any { it.name == "MicTestWatchdog" && it.isAlive }

    @Test
    fun `a read that never returns is detected by the watchdog, once`() {
        val source = BlockingSource()
        val session = MicTestSession(startup = ImmediateStartup, noProgressTimeoutMs = 100,
            openAudioSource = { WavRecorder.RecorderConfig(source, 48000, 64) })
        val errors = session.begin()
        assertTrue(source.entered.await(2, java.util.concurrent.TimeUnit.SECONDS))

        waitFor("the stall is reported") { errors.isNotEmpty() }
        assertTrue(errors.single() is MicrophoneStalledException)
        Thread.sleep(300)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("reported once", errors.size == 1)
        session.stop()
    }

    @Test
    fun `audio arriving after the stall was reported is ignored, and nothing more is reported`() {
        val source = BlockingSource(lateAudio = true)
        val levels = Collections.synchronizedList(mutableListOf<Float>())
        val errors = Collections.synchronizedList(mutableListOf<Exception>())
        val session = MicTestSession(startup = ImmediateStartup, noProgressTimeoutMs = 100,
            openAudioSource = { WavRecorder.RecorderConfig(source, 48000, 64) })
        session.start(context(), onMicrophoneInfo = {}, onLevel = { levels += it }, onError = { errors += it })
        waitFor("the stall is reported") { errors.isNotEmpty() }

        source.release.countDown() // the native read finally returns -- with audio
        Thread.sleep(200)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(levels.isEmpty())
        assertTrue(errors.size == 1)
        session.stop()
    }

    @Test
    fun `stopping ends the watchdog at once, and a new test starts clean`() {
        val session = MicTestSession(startup = ImmediateStartup, noProgressTimeoutMs = 100,
            openAudioSource = { WavRecorder.RecorderConfig(Source(chunks = 1_000_000), 48000, 4) })
        val first = session.begin()
        waitFor("watching") { watchdogAlive() }
        session.stop()
        waitFor("the watchdog ends with the test", timeoutMs = 1_000) { !watchdogAlive() }

        val second = session.begin()
        Thread.sleep(400) // audio keeps coming: nothing to report
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("first=$first second=$second", first.isEmpty() && second.isEmpty())
        assertTrue(session.active)
        session.stop()
    }
}
