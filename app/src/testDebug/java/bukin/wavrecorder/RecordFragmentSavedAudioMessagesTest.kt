package bukin.wavrecorder

import android.app.Application
import android.content.ComponentName
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.fragment.app.testing.FragmentScenario
import androidx.fragment.app.testing.launchFragmentInContainer
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowToast
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.atomic.AtomicInteger

/**
 * What the Record screen says a session saved matches what actually holds audio: "saved up to
 * that point" only when audio was saved, "nothing was saved" when none was -- live, or caught up
 * on after the app is reopened -- and no "Recording saved" summary for a file deleted as empty.
 *
 * Messages are matched by text among every toast shown, never taken as "the latest toast": a
 * toast posted late by another test's background work can land in any test.
 */
@RunWith(RobolectricTestRunner::class)
class RecordFragmentSavedAudioMessagesTest {

    @get:Rule
    val isolation = TestIsolation()

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun app(): Application = ApplicationProvider.getApplicationContext()

    private val services = mutableListOf<RecordingService>()

    @After
    fun tearDown() {
        // Nothing of this test's sessions may run on into the next test.
        services.forEach { it.stopAndAwaitFinalization() }
        RecordingOwnership.resetForTest()
    }

    /** Delivers [chunks] chunks of [chunkBytes], then only empty reads -- paced like a microphone. */
    private class Source(private val chunks: Int, private val chunkBytes: Int) : AudioSource {
        val reads = AtomicInteger()
        override fun startRecording() {}
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            Thread.sleep(1)
            if (reads.incrementAndGet() <= chunks) {
                buffer.fill(7, offset, offset + chunkBytes)
                return chunkBytes
            }
            Thread.sleep(1)
            return 0
        }
        override fun stop() {}
        override fun release() {}
    }

    /** A file's writer; if [failing], its [failAtWrite]-th audio write throws. */
    private class FailingPartWriter(private val channel: FileChannel, private val failing: Boolean, private val failAtWrite: Int) : SegmentWriter {
        private var audioWrites = 0
        override fun write(buffer: ByteBuffer): Int {
            if (channel.position() >= 44 && failing && ++audioWrites == failAtWrite) throw IOException("injected write failure")
            return channel.write(buffer)
        }
        override fun position() = channel.position()
        override fun position(newPosition: Long) { channel.position(newPosition) }
        override fun close() = channel.close()
    }

    private fun service(recorder: WavRecorder): RecordingService {
        val service = Robolectric.buildService(RecordingService::class.java).create().get()
        shadowOf(app()).setComponentNameAndServiceForBindService(
            ComponentName(app(), RecordingService::class.java), service.LocalBinder())
        service.destinationManager = object : DestinationManager(app()) {
            override fun createOutputFile(fileName: String): OutputTarget = OutputTarget.FileTarget(File(tempFolder.root, fileName))
        }
        service.recorder = recorder
        services += service
        return service
    }

    private fun writersFailing(part: Int, atWrite: Int): (FileChannel) -> SegmentWriter {
        val parts = AtomicInteger()
        return { channel -> FailingPartWriter(channel, parts.incrementAndGet() == part, atWrite) }
    }

    private fun launch(service: RecordingService): FragmentScenario<RecordFragment> {
        val scenario = launchFragmentInContainer<RecordFragment>(themeResId = R.style.Theme_WavRecorder)
        waitFor("the screen is attached") { service.listener != null }
        return scenario
    }

    private fun waitFor(what: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(2)
        }
        shadowOf(Looper.getMainLooper()).idle()
        check(condition()) { "timed out waiting: $what" }
    }

    private fun awaitMessage(id: Int) {
        val message = app().getString(id)
        waitFor("\"$message\" is shown") { ShadowToast.showedToast(message) }
    }

    private fun shown(id: Int) = ShadowToast.showedToast(app().getString(id))

    private fun statusOf(scenario: FragmentScenario<RecordFragment>): Pair<String, Boolean> {
        var status: Pair<String, Boolean>? = null
        scenario.onFragment { fragment ->
            val view = fragment.requireView()
            status = view.findViewById<TextView>(R.id.statusText).text.toString() to
                (view.findViewById<View>(R.id.statusDetailText).visibility == View.VISIBLE)
        }
        return status!!
    }

    /** A recording whose first audio write fails while it is running: nothing was saved. */
    private fun firstWriteFails() = service(WavRecorder(startup = ImmediateStartup,
        openAudioSource = { WavRecorder.RecorderConfig(Source(chunks = 1_000, chunkBytes = 64), 48000, 64) },
        wrapChannel = writersFailing(part = 1, atWrite = 1)))

    @Test
    fun `a write failure before any audio was saved says nothing was saved`() {
        val service = firstWriteFails()
        val scenario = launch(service)
        service.startRecording(1L)

        awaitMessage(R.string.recording_not_saved_error)
        assertFalse("never 'saved up to that point'", shown(R.string.recording_incomplete_error))
        assertNotEquals(app().getString(R.string.status_saved_title), statusOf(scenario).first)
        scenario.close()
    }

    @Test
    fun `a microphone that never delivers audio says nothing was saved, not saved up to that point`() {
        val service = service(WavRecorder(startup = ImmediateStartup, noProgressTimeoutMs = 300,
            openAudioSource = { WavRecorder.RecorderConfig(Source(chunks = 0, chunkBytes = 64), 48000, 64) }))
        val scenario = launch(service)
        service.startRecording(1L)

        awaitMessage(R.string.mic_stalled_nothing_saved)
        assertFalse("never 'saved up to that point'", shown(R.string.mic_stalled_error))
        scenario.close()
    }

    @Test
    fun `when the last file received no audio, the partial save is still reported as saved up to that point`() {
        // 100 Hz 16-bit mono: a 30-minute part is 360,000 bytes, 18 chunks. Part 2's first write fails.
        val service = service(WavRecorder(startup = ImmediateStartup,
            openAudioSource = { WavRecorder.RecorderConfig(Source(chunks = 1_000, chunkBytes = 20_000), 100, 20_000) },
            wrapChannel = writersFailing(part = 2, atWrite = 1)))
        val scenario = launch(service)
        service.startRecording(1L, splitMinutes = 30)

        awaitMessage(R.string.recording_incomplete_error)
        assertFalse(shown(R.string.recording_not_saved_error))
        val left = tempFolder.root.list()!!.toList()
        assertTrue("only part 1, which holds the audio, is left: $left", left.single().endsWith("_part01.wav"))
        scenario.close()
    }

    @Test
    fun `a stop before any audio was recorded shows no 'Recording saved' summary`() {
        val service = service(WavRecorder(startup = ImmediateStartup,
            openAudioSource = { WavRecorder.RecorderConfig(Source(chunks = 0, chunkBytes = 64), 48000, 64) }))
        val scenario = launch(service)
        service.startRecording(1L)
        waitFor("recording") { service.lastTarget != null }
        service.stopRecording()
        waitFor("finalized") { !service.isFinalizing }

        assertEquals(app().getString(R.string.status_idle) to false, statusOf(scenario))
        scenario.close()
    }

    @Test
    fun `caught up on after reopening the app, a nothing-saved outcome still says nothing was saved`() {
        val service = firstWriteFails() // no screen attached yet
        service.startRecording(1L)
        waitFor("the outcome is persisted") { !service.isFinalizing && service.pendingOutcomeStore.peekSessionId() != null }

        val scenario = launch(service)
        awaitMessage(R.string.recording_not_saved_error)
        assertFalse(shown(R.string.recording_incomplete_error))
        assertNotEquals(app().getString(R.string.status_saved_title), statusOf(scenario).first)
        scenario.close()
    }
}
