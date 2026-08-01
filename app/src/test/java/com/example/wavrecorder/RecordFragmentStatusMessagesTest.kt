package com.example.wavrecorder

import android.app.Application
import android.content.ComponentName
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.fragment.app.testing.launchFragmentInContainer
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.junit.runner.RunWith
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Covers what the Record screen's "Status" section actually shows once a session ends: a friendly
 * "Recording saved" summary on a normal stop, but the pre-existing finalization-failure /
 * unknown-finalization messages left completely intact and never replaced by that friendly summary
 * -- the whole point of keeping them prominent is defeated if a UI refactor accidentally routes
 * them through the same "saved" wording.
 *
 * Drives [RecordingService] directly (rather than through [RecordFragment]'s Record button and its
 * confirmation dialogs) since [RecordFragment.recordingListener] is wired up as soon as the
 * fragment binds to the service -- calling the service's own start/stop directly still fires every
 * listener callback the fragment reacts to, without the test needing to also navigate the
 * no-external-mic and stop-confirmation dialogs to get there.
 */
@RunWith(RobolectricTestRunner::class)
class RecordFragmentStatusMessagesTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun app(): Application = ApplicationProvider.getApplicationContext()

    /** Fails only the seek-back-to-0 that a header patch always does, simulating a finalization
     * failure without disturbing normal data writes -- same technique as WavRecorderTest. */
    private class FailingHeaderPatchWriter(private val channel: FileChannel) : SegmentWriter {
        override fun write(buffer: ByteBuffer): Int = channel.write(buffer)
        override fun position(): Long = channel.position()
        override fun position(newPosition: Long) {
            if (newPosition == 0L) throw IOException("simulated header patch failure")
            channel.position(newPosition)
        }
        override fun close() = channel.close()
    }

    private fun bindRealService(): RecordingService {
        val service = Robolectric.buildService(RecordingService::class.java).create().get()
        shadowOf(app()).setComponentNameAndServiceForBindService(
            ComponentName(app(), RecordingService::class.java),
            service.LocalBinder()
        )
        return service
    }

    private fun awaitListenerAttached(scenario: androidx.fragment.app.testing.FragmentScenario<RecordFragment>, service: RecordingService) {
        val deadline = System.currentTimeMillis() + 2000
        while (service.listener == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun statusText(scenario: androidx.fragment.app.testing.FragmentScenario<RecordFragment>): String {
        var text: String? = null
        scenario.onFragment { fragment ->
            text = fragment.view!!.findViewById<TextView>(R.id.statusText).text.toString()
        }
        return text!!
    }

    private fun detailVisible(scenario: androidx.fragment.app.testing.FragmentScenario<RecordFragment>): Boolean {
        var visible = false
        scenario.onFragment { fragment ->
            visible = fragment.view!!.findViewById<View>(R.id.statusDetailText).visibility == View.VISIBLE
        }
        return visible
    }

    @Test
    fun `a finalization failure keeps the recovery message and never shows a normal saved status`() {
        val service = bindRealService()
        val chunk = byteArrayOf(1, 2, 3, 4)
        val blockUntilStopped = CountDownLatch(1)
        var reads = 0
        val source = object : AudioSource {
            override fun startRecording() {}
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                reads++
                if (reads == 1) {
                    System.arraycopy(chunk, 0, buffer, offset, chunk.size)
                    return chunk.size
                }
                blockUntilStopped.await(5, TimeUnit.SECONDS)
                return -1
            }
            override fun stop() { blockUntilStopped.countDown() }
            override fun release() {}
        }
        val segmentFile = tempFolder.newFile("segment.wav")
        service.destinationManager = object : DestinationManager(app()) {
            override fun createOutputFile(fileName: String): OutputTarget = OutputTarget.FileTarget(segmentFile)
        }
        service.recorder = WavRecorder(
            openAudioSource = { WavRecorder.RecorderConfig(source, sampleRate = 48000, bufferSize = chunk.size) },
            wrapChannel = { channel -> FailingHeaderPatchWriter(channel) }
        )

        val scenario = launchFragmentInContainer<RecordFragment>(themeResId = R.style.Theme_WavRecorder)
        awaitListenerAttached(scenario, service)

        service.startRecording()
        val deadline = System.currentTimeMillis() + 2000
        while (reads < 1 && System.currentTimeMillis() < deadline) Thread.sleep(5)
        Thread.sleep(20)
        service.stopRecording()
        shadowOf(Looper.getMainLooper()).idle()

        val text = statusText(scenario)
        val savedTitle = app().getString(R.string.status_saved_title)
        assertFalse("expected the recovery message, never the normal saved title; got: $text",
            text.contains(savedTitle))
        assertFalse("the friendly saved date/duration detail must stay hidden on failure",
            detailVisible(scenario))
    }

    @Test
    fun `a finalization result of Unknown keeps the verification-needed message and never shows a normal saved status`() {
        val service = bindRealService()
        val chunk = byteArrayOf(1, 2, 3, 4)
        val source = object : AudioSource {
            override fun startRecording() {}
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                // Never returns, and stop() deliberately does not unblock it -- forces
                // WavRecorder.stop()'s join timeout to give up, producing FinalizeResult.Unknown.
                Thread.sleep(10_000)
                return -1
            }
            override fun stop() {}
            override fun release() {}
        }
        val segmentFile = tempFolder.newFile("segment.wav")
        service.destinationManager = object : DestinationManager(app()) {
            override fun createOutputFile(fileName: String): OutputTarget = OutputTarget.FileTarget(segmentFile)
        }
        service.recorder = WavRecorder(
            threadJoinTimeoutMs = 50,
            openAudioSource = { WavRecorder.RecorderConfig(source, sampleRate = 48000, bufferSize = chunk.size) }
        )

        val scenario = launchFragmentInContainer<RecordFragment>(themeResId = R.style.Theme_WavRecorder)
        awaitListenerAttached(scenario, service)

        service.startRecording()
        val segmentOpenedDeadline = System.currentTimeMillis() + 2000
        while (service.lastTarget == null && System.currentTimeMillis() < segmentOpenedDeadline) {
            Thread.sleep(5)
            shadowOf(Looper.getMainLooper()).idle()
        }
        service.stopRecording()
        shadowOf(Looper.getMainLooper()).idle()

        val text = statusText(scenario)
        val savedTitle = app().getString(R.string.status_saved_title)
        assertFalse("expected the verification-needed message, never the normal saved title; got: $text",
            text.contains(savedTitle))
        assertFalse("the friendly saved date/duration detail must stay hidden when unconfirmed",
            detailVisible(scenario))
    }

    @Test
    fun `a normal stop shows the friendly 'Recording saved' summary with a date and duration, not the raw filename`() {
        val service = bindRealService()
        val chunk = byteArrayOf(1, 2, 3, 4)
        val blockUntilStopped = CountDownLatch(1)
        var reads = 0
        val source = object : AudioSource {
            override fun startRecording() {}
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                reads++
                if (reads == 1) {
                    System.arraycopy(chunk, 0, buffer, offset, chunk.size)
                    return chunk.size
                }
                blockUntilStopped.await(5, TimeUnit.SECONDS)
                return -1
            }
            override fun stop() { blockUntilStopped.countDown() }
            override fun release() {}
        }
        val segmentFile = tempFolder.newFile("segment.wav")
        service.destinationManager = object : DestinationManager(app()) {
            override fun createOutputFile(fileName: String): OutputTarget = OutputTarget.FileTarget(segmentFile)
        }
        service.recorder = WavRecorder(
            openAudioSource = { WavRecorder.RecorderConfig(source, sampleRate = 48000, bufferSize = chunk.size) }
        )

        val scenario = launchFragmentInContainer<RecordFragment>(themeResId = R.style.Theme_WavRecorder)
        awaitListenerAttached(scenario, service)

        service.startRecording()
        // onSegmentStarted's post to the main looper (which is what sets currentTarget, the
        // value onStopped below reports) needs the looper actually drained, not just wall-clock
        // time for the background thread to reach it -- a plain Thread.sleep() poll on `reads`
        // isn't enough, unlike the finalization-failure/unknown tests above whose target comes
        // from the recording thread's own FinalizeResult instead of this looper-delivered value.
        val deadline = System.currentTimeMillis() + 2000
        while (service.lastTarget == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
            shadowOf(Looper.getMainLooper()).idle()
        }
        while (reads < 1 && System.currentTimeMillis() < deadline) Thread.sleep(5)
        Thread.sleep(20)
        service.stopRecording()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(app().getString(R.string.status_saved_title), statusText(scenario))
        assertTrue("expected the friendly date/duration detail to be shown", detailVisible(scenario))

        var detailText: String? = null
        scenario.onFragment { fragment ->
            detailText = fragment.view!!.findViewById<TextView>(R.id.statusDetailText).text.toString()
        }
        assertFalse("the raw segment file name must not appear in the friendly summary",
            detailText!!.contains(segmentFile.name))
        assertTrue("expected a duration to be shown", detailText!!.contains("second"))
    }
}
