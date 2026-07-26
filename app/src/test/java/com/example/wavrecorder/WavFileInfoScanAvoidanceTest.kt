package com.example.wavrecorder

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.FileInputStream
import java.io.InputStream
import java.io.RandomAccessFile

/**
 * Proves [WavFileInfo.read] stays cheap for the library list: refreshing the library calls it
 * once per file, so it must not stream through a recording's entire audio payload just to
 * report duration/size when the file's actual size is already known via [File.length]/
 * `OpenableColumns.SIZE`.
 */
@RunWith(RobolectricTestRunner::class)
class WavFileInfoScanAvoidanceTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    /** Wraps a real file's stream but fails outright if anything at or past [payloadStartOffset]
     * (i.e. inside the audio payload, not just the header) is ever read. */
    private class PayloadGuardInputStream(
        private val real: InputStream,
        private val payloadStartOffset: Long
    ) : InputStream() {
        private var position = 0L

        override fun read(): Int {
            check(position < payloadStartOffset) { "attempted to read into the audio payload at byte $position" }
            val b = real.read()
            if (b >= 0) position++
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            check(position < payloadStartOffset) { "attempted to read into the audio payload at byte $position" }
            val cappedLen = minOf(len, (payloadStartOffset - position).toInt())
            val n = real.read(b, off, cappedLen)
            if (n > 0) position += n
            return n
        }

        override fun close() = real.close()
    }

    @Test
    fun `WavFileInfo does not read the audio payload when the file size is known`() {
        val file = tempFolder.newFile("large.wav")
        val audioBytes = 5_000_000 // large enough that actually streaming it would be a real regression
        RandomAccessFile(file, "rw").use { raf ->
            val header = WavHeaderWriter.build(
                sampleRate = 44100, channels = 1, bitsPerSample = 16, audioDataLen = audioBytes.toLong()
            )
            val headerBytes = ByteArray(header.remaining())
            header.get(headerBytes)
            raf.write(headerBytes)
            raf.setLength(headerBytes.size.toLong() + audioBytes) // sparse: extends length without writing 5MB
        }

        val uri = Uri.fromFile(file)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val guarded = PayloadGuardInputStream(FileInputStream(file), payloadStartOffset = WavHeaderWriter.HEADER_SIZE.toLong())
        shadowOf(context.contentResolver).registerInputStream(uri, guarded)

        val info = WavFileInfo.read(context, uri)

        assertNotNull("expected a valid, complete file to be read successfully without touching its payload", info)
        assertEquals(audioBytes / (44100.0 * 1 * 2), info!!.durationSeconds, 0.001)
    }
}
