package com.example.wavrecorder

import android.media.AudioFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class PcmFormatTest {

    @Test
    fun `encoding and channel constants match the Android SDK`() {
        assertEquals(AudioFormat.ENCODING_PCM_16BIT, PcmEncoding.PCM_16.androidEncoding)
        assertEquals(AudioFormat.ENCODING_PCM_24BIT_PACKED, PcmEncoding.PCM_24_PACKED.androidEncoding)
        assertEquals(AudioFormat.ENCODING_PCM_32BIT, PcmEncoding.PCM_32.androidEncoding)
        assertEquals(AudioFormat.ENCODING_PCM_FLOAT, PcmEncoding.PCM_FLOAT.androidEncoding)
        assertEquals(AudioFormat.CHANNEL_IN_MONO, PcmFormat.CHANNEL_IN_MONO)
        assertEquals(AudioFormat.CHANNEL_IN_STEREO, PcmFormat.CHANNEL_IN_STEREO)
        assertEquals(android.media.MediaRecorder.AudioSource.UNPROCESSED, NegotiatedAudio.AUDIO_SOURCE_UNPROCESSED)
        assertEquals(android.media.MediaRecorder.AudioSource.MIC, NegotiatedAudio.AUDIO_SOURCE_MIC)
    }

    @Test
    fun `newer encodings are only offered where AudioRecord accepts them`() {
        assertEquals(31, PcmEncoding.PCM_24_PACKED.minSdk)
        assertEquals(31, PcmEncoding.PCM_32.minSdk)
        assertEquals(23, PcmEncoding.PCM_FLOAT.minSdk)
    }

    @Test
    fun `frame size and byte rate follow encoding and channel count`() {
        val cases = mapOf(
            PcmFormat.pcm16Mono(48000) to (2 to 96_000L),
            PcmFormat.of(48000, PcmEncoding.PCM_16, 2) to (4 to 192_000L),
            PcmFormat.of(96000, PcmEncoding.PCM_24_PACKED, 1) to (3 to 288_000L),
            PcmFormat.of(96000, PcmEncoding.PCM_24_PACKED, 2) to (6 to 576_000L),
            PcmFormat.of(192000, PcmEncoding.PCM_32, 2) to (8 to 1_536_000L),
            PcmFormat.of(48000, PcmEncoding.PCM_FLOAT, 1) to (4 to 192_000L),
            PcmFormat.of(48000, PcmEncoding.PCM_24_PACKED, 6) to (18 to 864_000L)
        )
        cases.forEach { (format, expected) ->
            assertEquals("$format frame", expected.first, format.bytesPerFrame)
            assertEquals("$format byte rate", expected.second, format.byteRate)
        }
    }

    @Test
    fun `mono and stereo use positional masks, other counts use an index mask`() {
        assertEquals(PcmFormat.CHANNEL_IN_MONO, PcmFormat.of(48000, PcmEncoding.PCM_16, 1).channelMask)
        assertEquals(PcmFormat.CHANNEL_IN_STEREO, PcmFormat.of(48000, PcmEncoding.PCM_16, 2).channelMask)
        val quad = PcmFormat.of(48000, PcmEncoding.PCM_16, 4)
        assertEquals(0, quad.channelMask)
        assertEquals(0b1111, quad.channelIndexMask)
    }

    @Test
    fun `inconsistent channel descriptions are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { PcmFormat(48000, PcmEncoding.PCM_16, 2, PcmFormat.CHANNEL_IN_MONO, 0) }
        assertThrows(IllegalArgumentException::class.java) { PcmFormat(48000, PcmEncoding.PCM_16, 3, 0, 0b11) }
        assertThrows(IllegalArgumentException::class.java) { PcmFormat(48000, PcmEncoding.PCM_16, 1, PcmFormat.CHANNEL_IN_MONO, 1) }
        assertThrows(IllegalArgumentException::class.java) { PcmFormat(0, PcmEncoding.PCM_16, 1, PcmFormat.CHANNEL_IN_MONO, 0) }
    }

    @Test
    fun `WAV layout and speaker mask per format`() {
        assertEquals(WavLayout.PCM_BASIC, PcmFormat.pcm16Mono(48000).wavLayout)
        assertEquals(WavLayout.PCM_BASIC, PcmFormat.of(44100, PcmEncoding.PCM_16, 2).wavLayout)
        assertEquals(WavLayout.EXTENSIBLE_PCM, PcmFormat.of(48000, PcmEncoding.PCM_16, 4).wavLayout)
        assertEquals(WavLayout.EXTENSIBLE_PCM, PcmFormat.of(48000, PcmEncoding.PCM_24_PACKED, 1).wavLayout)
        assertEquals(WavLayout.EXTENSIBLE_PCM, PcmFormat.of(48000, PcmEncoding.PCM_32, 2).wavLayout)
        assertEquals(WavLayout.FLOAT_BASIC, PcmFormat.of(48000, PcmEncoding.PCM_FLOAT, 2).wavLayout)
        assertEquals(WavLayout.EXTENSIBLE_FLOAT, PcmFormat.of(48000, PcmEncoding.PCM_FLOAT, 3).wavLayout)
        assertEquals(0x4, PcmFormat.of(48000, PcmEncoding.PCM_24_PACKED, 1).wavSpeakerMask)
        assertEquals(0x3, PcmFormat.of(48000, PcmEncoding.PCM_24_PACKED, 2).wavSpeakerMask)
        assertEquals("index-mask channels have no speaker positions", 0,
            PcmFormat.of(48000, PcmEncoding.PCM_24_PACKED, 4).wavSpeakerMask)
    }

    @Test
    fun `durations and splits are whole frames`() {
        val mono24 = PcmFormat.of(96000, PcmEncoding.PCM_24_PACKED, 1)
        assertEquals(0L, mono24.bytesForSeconds(3600) % mono24.bytesPerFrame)
        assertEquals(96000L * 3600 * 3, mono24.bytesForSeconds(3600))
        assertEquals(9L, mono24.alignToFrame(11))
        assertEquals(0L, mono24.alignToFrame(-5))
        assertEquals(3L, mono24.framesIn(11))
        assertEquals(1.0, mono24.durationSeconds(288_000 + 2), 0.0) // a trailing partial frame adds nothing
    }

    @Test
    fun `legacy journal values map to integer PCM`() {
        assertEquals(PcmFormat.pcm16Mono(48000), PcmFormat.fromLegacy(48000, 1, 16))
        assertEquals(PcmFormat.of(44100, PcmEncoding.PCM_24_PACKED, 2), PcmFormat.fromLegacy(44100, 2, 24))
        assertNull(PcmFormat.fromLegacy(48000, 1, 8))
        assertNull(PcmFormat.fromLegacy(0, 1, 16))
        assertNull(PcmFormat.fromLegacy(48000, 0, 16))
    }
}
