package com.example.wavrecorder

import android.content.Context
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols

/**
 * Presentation-only, non-technical descriptions of an audio format for the Record screen, e.g.
 * "Insta360 Mic Air • 48 kHz • 16-bit • Mono". Always describes the format actually being written
 * (or metered, during a microphone test) -- the negotiated [PcmFormat] -- never a requested one.
 */
internal object AudioFormatLabels {

    fun sampleRate(context: Context, sampleRate: Int): String {
        val khz = DecimalFormat("0.##", DecimalFormatSymbols.getInstance()).format(sampleRate / 1000.0)
        return context.getString(R.string.format_rate_khz, khz)
    }

    fun bitDepth(context: Context, encoding: PcmEncoding): String = context.getString(
        if (encoding.isFloat) R.string.format_bits_float else R.string.format_bits,
        encoding.validBits
    )

    fun channels(context: Context, channelCount: Int): String = when (channelCount) {
        1 -> context.getString(R.string.audio_channels_mono)
        2 -> context.getString(R.string.audio_channels_stereo)
        else -> context.resources.getQuantityString(R.plurals.audio_channels_count, channelCount, channelCount)
    }

    /** "48 kHz • 24-bit • Stereo". */
    fun format(context: Context, format: PcmFormat): String = listOf(
        sampleRate(context, format.sampleRate),
        bitDepth(context, format.encoding),
        channels(context, format.channelCount)
    ).joinToString(context.getString(R.string.format_separator))

    /** "Insta360 Mic Air • 48 kHz • 24-bit • Stereo", or just the format when the device is unknown. */
    fun line(context: Context, deviceLabel: String?, format: PcmFormat): String {
        val formatText = format(context, format)
        return if (deviceLabel.isNullOrBlank()) formatText
        else deviceLabel + context.getString(R.string.format_separator) + formatText
    }

    /**
     * A short explanation, only when it's useful -- at most one line of format evidence and one of
     * search completeness, kept independent:
     *  - converted: Android reports capturing the device in a different format and converting it to
     *    the saved one, so the saved format mustn't read as what the device side carries (the note
     *    names what it does carry);
     *  - unreported: Android doesn't report the device side, so whether it converts can't be said
     *    (only for a real negotiation -- a test source has none);
     *  - matched: no note -- a matching device side is not presented as proof of microphone
     *    fidelity -- unless the device's advertised formats all failed and a compatibility format
     *    was used instead;
     *  - and, separately, when the search stopped before trying a candidate that could have been
     *    a higher format, or the best one found couldn't be reopened: the format is the best
     *    found, not a verified maximum.
     * Details (every candidate, what Android reported for each, why the search ended) are in the
     * negotiation log, not here.
     */
    fun note(context: Context, format: PcmFormat, negotiation: NegotiatedAudio?, deviceFormat: DeviceSideFormat?): String? {
        val evidence = evidenceNote(context, format, negotiation, deviceFormat)
        val report = negotiation?.search
        val search = when {
            report == null || report.coverage != SearchCoverage.INCOMPLETE -> null
            report.stop == SearchStop.BEST_NOT_REOPENED -> context.getString(R.string.format_note_best_not_reopened)
            // Only when a higher format was left untried -- not when all that was skipped could
            // have done is confirm this same format more strongly.
            report.untriedHigherFormats > 0 -> context.getString(R.string.format_note_search_incomplete)
            else -> null
        }
        return listOfNotNull(evidence, search).joinToString("\n").ifEmpty { null }
    }

    private fun evidenceNote(context: Context, format: PcmFormat, negotiation: NegotiatedAudio?, deviceFormat: DeviceSideFormat?): String? {
        if (deviceFormat != null && deviceFormat.differsFrom(format)) {
            val parts = mutableListOf(sampleRate(context, deviceFormat.sampleRate))
            PcmEncoding.fromAndroidEncoding(if (deviceFormat.encoding == 1) PcmEncoding.PCM_16.androidEncoding else deviceFormat.encoding)
                ?.let { parts += bitDepth(context, it) }
            if (deviceFormat.channelCount > 0) parts += channels(context, deviceFormat.channelCount)
            return context.getString(R.string.format_note_converted, parts.joinToString(context.getString(R.string.format_separator)))
        }
        if (deviceFormat == null && negotiation?.captureMode == CaptureMode.UNREPORTED) {
            return context.getString(R.string.format_note_unreported)
        }
        if (negotiation != null && negotiation.origin == CandidateOrigin.COMPATIBILITY_FALLBACK && negotiation.rejectedAdvertisedAttempts > 0) {
            return context.getString(R.string.format_note_fallback)
        }
        return null
    }
}
