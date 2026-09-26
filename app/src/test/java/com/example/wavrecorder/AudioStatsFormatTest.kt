package com.example.wavrecorder

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Library statistics and durations for the new formats, read from real files. */
@RunWith(RobolectricTestRunner::class)
class AudioStatsFormatTest {

    @get:Rule
    val tempFolder = TemporaryFolder()
    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun wav(format: PcmFormat, audio: ByteArray): File {
        val header = WavHeaderWriter.build(format, audio.size.toLong())
        val bytes = ByteArray(header.remaining()).also { header.get(it) } + audio + (if (audio.size % 2 == 1) byteArrayOf(0) else ByteArray(0))
        return tempFolder.newFile().apply { writeBytes(bytes) }
    }

    @Test
    fun `24-bit stereo statistics and duration`() {
        val format = PcmFormat.of(48000, PcmEncoding.PCM_24_PACKED, 2)
        val frame = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0x7F, 0, 0, 0) // full-scale left, silent right
        val file = wav(format, ByteArray(6 * 47999) + frame)
        val stats = AudioStatsReader.read(app, Uri.fromFile(file))!!
        assertEquals(24, stats.bitsPerSample)
        assertEquals(2, stats.channels)
        assertEquals(1.0, stats.durationSeconds, 1e-9)
        assertEquals(0.0, stats.peakDbfs!!, 0.001)
        assertEquals(96000L, stats.sampleCount)
        assertEquals(1L, stats.clippedSamples)
        val info = WavFileInfo.read(app, Uri.fromFile(file))!!
        assertEquals(1.0, info.durationSeconds, 1e-9)
    }

    @Test
    fun `float statistics stay finite with NaN and infinite samples`() {
        val format = PcmFormat.of(48000, PcmEncoding.PCM_FLOAT, 1)
        val audio = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .putFloat(Float.NaN).putFloat(0.5f).putFloat(Float.NEGATIVE_INFINITY).putFloat(0.25f).array()
        val stats = AudioStatsReader.read(app, Uri.fromFile(wav(format, audio)))!!
        assertTrue(stats.isFloat)
        assertEquals(0.0, stats.peakDbfs!!, 1e-9) // -inf clamps to full scale
        assertTrue(stats.rmsDbfs!!.isFinite())
        assertEquals(1L, stats.clippedSamples)
    }

    @Test
    fun `odd-length 24-bit mono files are read correctly`() {
        val format = PcmFormat.of(8000, PcmEncoding.PCM_24_PACKED, 1)
        val stats = AudioStatsReader.read(app, Uri.fromFile(wav(format, ByteArray(3 * 8000 + 3))))
        assertNotNull(stats)
        assertEquals(8001L, stats!!.sampleCount)
    }
}
