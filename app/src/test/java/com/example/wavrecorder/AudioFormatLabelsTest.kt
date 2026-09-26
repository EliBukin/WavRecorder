package com.example.wavrecorder

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AudioFormatLabelsTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val nbsp = " "

    @Test
    fun `the format line names the device and the saved format`() {
        assertEquals("Insta360 Mic Air • 48${nbsp}kHz • 16-bit • Mono",
            AudioFormatLabels.line(app, "Insta360 Mic Air", PcmFormat.pcm16Mono(48000)))
        assertEquals("USB Audio Device • 96${nbsp}kHz • 24-bit • Stereo",
            AudioFormatLabels.line(app, "USB Audio Device", PcmFormat.of(96000, PcmEncoding.PCM_24_PACKED, 2)))
        assertEquals("44.1${nbsp}kHz • 32-bit float • 4 channels",
            AudioFormatLabels.line(app, null, PcmFormat.of(44100, PcmEncoding.PCM_FLOAT, 4)))
        assertEquals("22.05${nbsp}kHz • 32-bit • Mono", AudioFormatLabels.format(app, PcmFormat.of(22050, PcmEncoding.PCM_32, 1)))
    }

    @Test
    fun `no note for a matched capture from an advertised format`() {
        val format = PcmFormat.of(48000, PcmEncoding.PCM_24_PACKED, 2)
        val matched = NegotiatedAudio(format, CandidateOrigin.PROFILE, 9, true, 0, captureMode = CaptureMode.MATCHED)
        assertNull(AudioFormatLabels.note(app, format, matched, DeviceSideFormat(48000, format.encoding.androidEncoding, 2)))
        assertNull(AudioFormatLabels.note(app, format, matched, null))
    }

    @Test
    fun `Android conversion is called out instead of presenting the saved format as the device side's`() {
        val format = PcmFormat.of(48000, PcmEncoding.PCM_FLOAT, 2)
        val note = AudioFormatLabels.note(app, format, null, DeviceSideFormat(44100, PcmEncoding.PCM_16.androidEncoding, 2))
        assertEquals("Android converts this from 44.1${nbsp}kHz • 16-bit • Stereo", note)
    }

    @Test
    fun `a matched fallback after advertised formats failed is explained, a plain one is not`() {
        val format = PcmFormat.pcm16Mono(48000)
        val device = DeviceSideFormat(48000, format.encoding.androidEncoding, 1)
        assertEquals(app.getString(R.string.format_note_fallback), AudioFormatLabels.note(app, format,
            NegotiatedAudio(format, CandidateOrigin.COMPATIBILITY_FALLBACK, 1, false, 4, captureMode = CaptureMode.MATCHED), device))
        assertNull(AudioFormatLabels.note(app, format,
            NegotiatedAudio(format, CandidateOrigin.COMPATIBILITY_FALLBACK, 9, false, 0, captureMode = CaptureMode.MATCHED), device))
    }

    @Test
    fun `a device side Android doesn't report is labelled as unreported, never as matched`() {
        val format = PcmFormat.of(96000, PcmEncoding.PCM_24_PACKED, 2)
        val unreported = NegotiatedAudio(format, CandidateOrigin.PROFILE, 9, true, 0, captureMode = CaptureMode.UNREPORTED)
        assertEquals("Android doesn't report whether it converts this format", AudioFormatLabels.note(app, format, unreported, null))
        assertNull("a test source (no negotiation) gets no note", AudioFormatLabels.note(app, format, null, null))
    }

    @Test
    fun `an incomplete search is noted separately from the format evidence, only when a higher format went untried`() {
        val format = PcmFormat.of(48000, PcmEncoding.PCM_24_PACKED, 2)
        val device = DeviceSideFormat(48000, format.encoding.androidEncoding, 2)
        fun negotiated(search: SearchReport, mode: CaptureMode = CaptureMode.MATCHED) =
            NegotiatedAudio(format, CandidateOrigin.PROFILE, 9, true, 0, captureMode = mode, search = search)

        val budget = SearchReport(SearchCoverage.INCOMPLETE, SearchStop.SEARCH_BUDGET, untriedImprovers = 3, untriedHigherFormats = 2)
        assertEquals("The search for a higher-quality format stopped early", AudioFormatLabels.note(app, format, negotiated(budget), device))
        assertEquals("both, independently",
            "Android converts this from 96${nbsp}kHz • 16-bit • Stereo\nThe search for a higher-quality format stopped early",
            AudioFormatLabels.note(app, format, negotiated(budget, CaptureMode.CONVERTED), DeviceSideFormat(96000, 2, 2)))
        assertNull("complete: nothing to say", AudioFormatLabels.note(app, format,
            negotiated(SearchReport(SearchCoverage.COMPLETE, SearchStop.NOTHING_BETTER_POSSIBLE)), device))
        assertNull("only stronger evidence for the same format was skipped", AudioFormatLabels.note(app, format,
            negotiated(SearchReport(SearchCoverage.INCOMPLETE, SearchStop.SEARCH_BUDGET, untriedImprovers = 1, untriedHigherFormats = 0)), device))
        assertEquals("A higher-quality format was found but couldn't be reopened", AudioFormatLabels.note(app, format,
            negotiated(SearchReport(SearchCoverage.INCOMPLETE, SearchStop.BEST_NOT_REOPENED)), device))
    }

    @Test
    fun `unprocessed is only claimed when the platform supports it`() {
        val format = PcmFormat.pcm16Mono(48000)
        assertEquals(true, NegotiatedAudio(format, CandidateOrigin.PROFILE, NegotiatedAudio.AUDIO_SOURCE_UNPROCESSED, true, 0).isUnprocessed)
        assertEquals(false, NegotiatedAudio(format, CandidateOrigin.PROFILE, NegotiatedAudio.AUDIO_SOURCE_UNPROCESSED, false, 0).isUnprocessed)
        assertEquals(false, NegotiatedAudio(format, CandidateOrigin.PROFILE, NegotiatedAudio.AUDIO_SOURCE_MIC, true, 0).isUnprocessed)
        // For diagnostics: processing that can't be ruled out is reported as unverified.
        assertTrue(NegotiatedAudio(format, CandidateOrigin.PROFILE, NegotiatedAudio.AUDIO_SOURCE_UNPROCESSED, false, 0).processing.contains("unverified"))
        assertTrue(NegotiatedAudio(format, CandidateOrigin.PROFILE, NegotiatedAudio.AUDIO_SOURCE_MIC, true, 0).processing.contains("unverified"))
        assertFalse(NegotiatedAudio(format, CandidateOrigin.PROFILE, NegotiatedAudio.AUDIO_SOURCE_UNPROCESSED, true, 0).processing.contains("unverified"))
    }
}
