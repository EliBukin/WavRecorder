package com.example.wavrecorder

import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Recordings are saved as `recording_yyyyMMdd_HHmmss_partNN.wav` which is great for sorting
 * on disk but unreadable when truncated in a narrow list row. This turns that into something
 * a human can actually parse at a glance, e.g. "Jul 26, 2026 - 11:55 AM (Part 2)". Falls back
 * to the raw file name for anything that doesn't match the expected pattern (older/renamed files).
 */
object RecordingNameFormatter {

    private val NAME_PATTERN = Regex("""recording_(\d{8})_(\d{6})_part(\d+)\.wav""", RegexOption.IGNORE_CASE)
    private val PARSE_FORMAT = SimpleDateFormat("yyyyMMdd HHmmss", Locale.US)
    private val DISPLAY_FORMAT = SimpleDateFormat("MMM d, yyyy - h:mm a", Locale.getDefault())

    fun friendlyTitle(fileName: String): String {
        val match = NAME_PATTERN.matchEntire(fileName) ?: return fileName
        val (datePart, timePart, partPart) = match.destructured
        val date = try {
            PARSE_FORMAT.parse("$datePart $timePart")
        } catch (e: Exception) {
            null
        } ?: return fileName

        val display = DISPLAY_FORMAT.format(date)
        val part = partPart.toIntOrNull() ?: 1
        return if (part > 1) "$display (Part $part)" else display
    }
}
