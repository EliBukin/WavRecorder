package com.example.wavrecorder

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * The saved-recording duration (audit finding F6) is the audio actually written -- counted from
 * frames, across every file of a session -- fixed when the recording stops and persisted with its
 * outcome, never derived from the wall clock when the summary happens to be shown.
 */
@RunWith(RobolectricTestRunner::class)
class SavedDurationTest {

    @get:Rule
    val isolation = TestIsolation()

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun app(): Application = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() = RecordingOwnership.resetForTest()

    @Test
    fun `the recorded duration is persisted with a saved outcome, and absent for an older record`() {
        val store = PendingOutcomeStore(app())
        val target = OutputTarget.FileTarget(File(app().cacheDir, "r.wav"))
        assertTrue(store.persist(1, RecordingOutcome.Saved(target, 1_000L, recordedMillis = 10_000L), null))
        assertEquals(10_000L, (store.consume() as RecordingOutcome.Saved).recordedMillis)

        assertTrue(store.persist(2, RecordingOutcome.Saved(target, 1_000L), null))
        assertNull("an outcome without one (as an earlier version wrote it) states none", (store.consume() as RecordingOutcome.Saved).recordedMillis)
    }

    @Test
    fun `a stopped session's saved outcome carries the audio it wrote, across split files, not the time it took`() {
        // 48 kHz 16-bit mono in 0.1 s chunks: 25 chunks = 2.5 s, split into 1 s files.
        val chunks = AtomicInteger()
        val source = object : AudioSource {
            override fun startRecording() {}
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (chunks.get() >= 25) {
                    Thread.sleep(1)
                    return 0
                }
                chunks.incrementAndGet()
                buffer.fill(1, offset, offset + 9600)
                return 9600
            }
            override fun stop() {}
            override fun release() {}
        }
        val service = Robolectric.buildService(RecordingService::class.java).create().get()
        service.recorder = WavRecorder(startup = ImmediateStartup, segmentMaxSeconds = 1,
            openAudioSource = { WavRecorder.RecorderConfig(source, 48000, 9600) })
        service.destinationManager = object : DestinationManager(app()) {
            private var n = 0
            override fun createOutputFile(fileName: String): OutputTarget = OutputTarget.FileTarget(tempFolder.newFile("part${++n}.wav"))
        }

        service.startRecording(1L)
        val deadline = System.currentTimeMillis() + 3000
        while (chunks.get() < 25 && System.currentTimeMillis() < deadline) Thread.sleep(5)
        Thread.sleep(1_200) // well over the audio's own length: must not be counted
        service.stopRecording()
        val finalizeDeadline = System.currentTimeMillis() + 5000
        while (service.isFinalizing && System.currentTimeMillis() < finalizeDeadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        shadowOf(Looper.getMainLooper()).idle()

        val outcome = service.consumePendingOutcome() as RecordingOutcome.Saved
        assertEquals(2_500L, outcome.recordedMillis)
        assertEquals(2_500L, service.lastSessionRecordedMillis)
    }
}
