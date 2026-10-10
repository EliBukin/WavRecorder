package com.example.wavrecorder

import android.app.Application
import android.net.Uri
import android.os.Looper
import androidx.fragment.app.testing.launchFragmentInContainer
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Rule
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowToast
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The Library can't delete a recording that is being written (audit finding F1), through either
 * delete path, whatever the screen showed when the user confirmed -- while finished recordings,
 * including the completed parts of one still recording, stay deletable.
 */
@RunWith(RobolectricTestRunner::class)
class LibraryDeletionSafetyTest {

    @get:Rule
    val isolation = TestIsolation()

    private fun app(): Application = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() = RecordingOwnership.resetForTest()

    private fun pumpUntil(timeoutMs: Long = 2000, done: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!done() && System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** A microphone delivering a little audio every few ms, until stopped. */
    private fun steadySource() = object : AudioSource {
        override fun startRecording() {}
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            Thread.sleep(5)
            buffer.fill(1, offset, offset + 4)
            return 4
        }
        override fun stop() {}
        override fun release() {}
    }

    private fun recorder(source: AudioSource = steadySource(), segmentSeconds: Long = 60 * 60) =
        WavRecorder(startup = ImmediateStartup, segmentMaxSeconds = segmentSeconds,
            openAudioSource = { WavRecorder.RecorderConfig(source, 48000, 4) })

    private fun startInto(recorder: WavRecorder, targets: () -> OutputTarget) =
        recorder.start(app(), WavRecorder.NextTarget { targets() }, {}, {}, {})

    @Test
    fun `bulk delete keeps the live recording, which then finalizes as a complete WAV`() {
        val manager = DestinationManager(app())
        val target = manager.createOutputFile("live.wav") as OutputTarget.FileTarget
        val recorder = recorder()
        val scenario = launchFragmentInContainer<LibraryFragment>(themeResId = R.style.Theme_WavRecorder)
        try {
            startInto(recorder) { target }
            pumpUntil { target.file.length() > 44 }
            assertTrue("the live file is listed", manager.listRecordings().any { it.uri == Uri.fromFile(target.file) })

            scenario.onFragment { it.performBulkDelete(setOf(Uri.fromFile(target.file))) }
            pumpUntil { ShadowToast.getTextOfLatestToast() != null }

            assertTrue("the live recording was not deleted", target.file.exists())
            assertTrue(recorder.isActive)
            assertEquals(app().getString(R.string.bulk_delete_in_use, 0, 1), ShadowToast.getTextOfLatestToast())
            assertEquals(WavRecorder.FinalizeResult.Ok, recorder.stop())
            assertNotNull("still a valid, finalized WAV", WavRiffParser.parse(target.file.inputStream())?.takeIf { it.dataSize > 0 })
        } finally {
            recorder.stop()
            scenario.close()
        }
    }

    @Test
    fun `a single delete of the live recording is refused with an explanation`() {
        val target = DestinationManager(app()).createOutputFile("live-single.wav") as OutputTarget.FileTarget
        val recorder = recorder()
        val scenario = launchFragmentInContainer<LibraryFragment>(themeResId = R.style.Theme_WavRecorder)
        try {
            startInto(recorder) { target }
            pumpUntil { target.file.length() > 44 }
            scenario.onFragment { fragment ->
                LibraryFragment::class.java.getDeclaredMethod("deleteRecording", RecordingItem::class.java)
                    .apply { isAccessible = true }
                    .invoke(fragment, RecordingItem(target.file.name, Uri.fromFile(target.file), 0.0, 0))
            }
            pumpUntil { ShadowToast.getTextOfLatestToast() != null }

            assertTrue(target.file.exists())
            assertEquals(app().getString(R.string.delete_in_use), ShadowToast.getTextOfLatestToast())
        } finally {
            recorder.stop()
            scenario.close()
        }
    }

    @Test
    fun `a stale selection can't delete a file that came into use after it was selected`() {
        val manager = DestinationManager(app())
        val finished = manager.createOutputFile("finished.wav") as OutputTarget.FileTarget
        val scenario = launchFragmentInContainer<LibraryFragment>(themeResId = R.style.Theme_WavRecorder)
        try {
            // Selected while idle... then recovery (say) starts working on it before the delete runs.
            val claim = RecordingOwnership.tryClaim(finished, RecordingOwnership.Use.RECOVERING)!!
            scenario.onFragment { it.performBulkDelete(setOf(Uri.fromFile(finished.file))) }
            pumpUntil { ShadowToast.getTextOfLatestToast() != null }
            assertTrue(finished.file.exists())

            claim.release()
            ShadowToast.reset()
            scenario.onFragment { it.performBulkDelete(setOf(Uri.fromFile(finished.file))) }
            pumpUntil { ShadowToast.getTextOfLatestToast() != null }
            assertFalse("deletable again once no longer in use", finished.file.exists())
        } finally {
            scenario.close()
        }
    }

    @Test
    fun `a completed part of a recording that is still going can be deleted - the live part can't`() {
        val manager = DestinationManager(app())
        val parts = mutableListOf<OutputTarget.FileTarget>()
        // 1-second files at 48 kHz 16-bit mono (96,000 bytes each), and exactly 1.25 s of audio:
        // so exactly two parts, the second one staying live.
        var chunks = 0
        val recorder = WavRecorder(startup = ImmediateStartup, segmentMaxSeconds = 1,
            openAudioSource = {
                WavRecorder.RecorderConfig(object : AudioSource {
                    override fun startRecording() {}
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        Thread.sleep(2)
                        if (++chunks > 25) return 0
                        buffer.fill(1, offset, offset + 4800)
                        return 4800
                    }
                    override fun stop() {}
                    override fun release() {}
                }, 48000, 4800)
            })
        try {
            startInto(recorder) { (manager.createOutputFile("part${parts.size + 1}.wav") as OutputTarget.FileTarget).also { parts += it } }
            pumpUntil(3000) { parts.size >= 2 && chunks > 25 }
            assertEquals(2, parts.size)

            assertEquals(DestinationManager.DeleteResult.DELETED, manager.deleteRecording(Uri.fromFile(parts[0].file)))
            assertEquals(DestinationManager.DeleteResult.IN_USE, manager.deleteRecording(Uri.fromFile(parts.last().file)))
        } finally {
            recorder.stop()
        }
        assertEquals("finished now, so deletable", DestinationManager.DeleteResult.DELETED,
            manager.deleteRecording(Uri.fromFile(parts.last().file)))
    }

    @Test
    fun `a file still being finalized can't be deleted`() {
        val target = DestinationManager(app()).createOutputFile("finalizing.wav") as OutputTarget.FileTarget
        val closing = CountDownLatch(1)
        val finishClose = CountDownLatch(1)
        val recorder = WavRecorder(startup = ImmediateStartup,
            openAudioSource = { WavRecorder.RecorderConfig(steadySource(), 48000, 4) },
            wrapChannel = { channel ->
                object : SegmentWriter {
                    override fun write(buffer: java.nio.ByteBuffer): Int = channel.write(buffer)
                    override fun position(): Long = channel.position()
                    override fun position(newPosition: Long) { channel.position(newPosition) }
                    override fun close() {
                        closing.countDown()
                        finishClose.await(5, TimeUnit.SECONDS)
                        channel.close()
                    }
                }
            })
        startInto(recorder) { target }
        pumpUntil { target.file.length() > 44 }
        val pending = recorder.requestStop()
        assertTrue(closing.await(3, TimeUnit.SECONDS))

        assertEquals(RecordingOwnership.Use.FINALIZING, RecordingOwnership.useOf(Uri.fromFile(target.file)))
        assertEquals(DestinationManager.DeleteResult.IN_USE, DestinationManager(app()).deleteRecording(Uri.fromFile(target.file)))

        finishClose.countDown()
        assertEquals(WavRecorder.FinalizeResult.Ok, recorder.awaitFinalization(pending))
        assertEquals(DestinationManager.DeleteResult.DELETED, DestinationManager(app()).deleteRecording(Uri.fromFile(target.file)))
        assertFalse(File(target.file.path).exists())
    }
}
