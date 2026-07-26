package com.example.wavrecorder

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.RandomAccessFile

/**
 * End-to-end coverage (real files, real [WavFileInfo]/[AudioStatsReader] entry points) for a WAV
 * whose "data" chunk declares more bytes than the file actually contains — a damaged or
 * cut-off-mid-write recording. Both readers must reject it outright rather than report a
 * duration/stats derived from the declared-but-unverified size.
 */
@RunWith(RobolectricTestRunner::class)
class WavFileTruncationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun writeWavFile(name: String, actualAudioBytes: Int, declaredDataSize: Int): File {
        val file = tempFolder.newFile(name)
        RandomAccessFile(file, "rw").use { raf ->
            val header = WavHeaderWriter.build(
                sampleRate = 44100,
                channels = 1,
                bitsPerSample = 16,
                audioDataLen = declaredDataSize.toLong()
            )
            val headerBytes = ByteArray(header.remaining())
            header.get(headerBytes)
            raf.write(headerBytes)
            raf.write(ByteArray(actualAudioBytes))
        }
        return file
    }

    @Test
    fun `WavFileInfo rejects a file whose declared data size exceeds what's actually present`() {
        val file = writeWavFile("truncated.wav", actualAudioBytes = 10, declaredDataSize = 1000)

        val info = WavFileInfo.read(ApplicationProvider.getApplicationContext(), Uri.fromFile(file))

        assertNull(info)
    }

    @Test
    fun `WavFileInfo accepts a file whose declared data size matches what's present`() {
        val file = writeWavFile("valid.wav", actualAudioBytes = 100, declaredDataSize = 100)

        val info = WavFileInfo.read(ApplicationProvider.getApplicationContext(), Uri.fromFile(file))

        assertNotNull(info)
    }

    @Test
    fun `AudioStatsReader rejects a file whose declared data size exceeds what's actually present`() {
        val file = writeWavFile("truncated.wav", actualAudioBytes = 10, declaredDataSize = 1000)

        val stats = AudioStatsReader.read(ApplicationProvider.getApplicationContext(), Uri.fromFile(file))

        assertNull(stats)
    }

    @Test
    fun `AudioStatsReader accepts a file whose declared data size matches what's present`() {
        val file = writeWavFile("valid.wav", actualAudioBytes = 100, declaredDataSize = 100)

        val stats = AudioStatsReader.read(ApplicationProvider.getApplicationContext(), Uri.fromFile(file))

        assertNotNull(stats)
        assertEquals(50L, stats?.sampleCount) // 100 bytes / 2 bytes per 16-bit sample
    }
}
