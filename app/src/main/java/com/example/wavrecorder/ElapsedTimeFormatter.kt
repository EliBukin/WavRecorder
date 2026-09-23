package com.example.wavrecorder

import java.util.Locale

/** Formats a running recording's elapsed time as a compact clock: "00:07", "12:34", "1:02:03".
 * Presentation-only -- the Record screen's timer display, never recording state. */
internal object ElapsedTimeFormatter {

    fun format(elapsedMillis: Long): String {
        val totalSeconds = elapsedMillis.coerceAtLeast(0) / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }
}
