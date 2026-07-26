package com.example.wavrecorder

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DestinationManagerTest {

    @Test
    fun `createOutputFile never overwrites an existing file when the requested name collides`() {
        // Regression test: recording filenames are derived from a second-precision timestamp, so
        // two sessions started within the same second ask for the identical name. Before this,
        // createOutputFile() handed back a File object for that name unconditionally, and
        // WavRecorder.openSegment() then opens it via `RandomAccessFile(file, "rw"); setLength(0)`
        // -- silently truncating whatever a prior finished session had already written there.
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = DestinationManager(context)

        val fileName = "recording_20260726_115500_part01.wav"
        val first = manager.createOutputFile(fileName) as OutputTarget.FileTarget

        // Simulate a completed prior recording already sitting at that path.
        val priorAudio = ByteArray(1000) { it.toByte() }
        first.file.writeBytes(priorAudio)

        // A second session computing the same timestamp-derived name.
        val second = manager.createOutputFile(fileName) as OutputTarget.FileTarget

        assertNotEquals(
            "a colliding name must resolve to a different file rather than reuse the existing one",
            first.file.absolutePath, second.file.absolutePath
        )
        assertTrue("the second target must be a genuinely new, previously-nonexistent file",
            second.file.exists())
        assertArrayEquals(
            "the first session's finished recording must be untouched by the second session " +
                "claiming the same name",
            priorAudio, first.file.readBytes()
        )
    }
}
