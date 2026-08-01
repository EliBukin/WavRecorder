package com.example.wavrecorder

import android.content.Context

/**
 * Formats a duration in whole seconds as compact, human-readable text (e.g. "13 seconds",
 * "3 minutes 12 seconds", "1 hour 5 minutes") for the Record screen's "last recording" summary.
 * Shows at most the two largest non-zero units -- once there's at least an hour, seconds are
 * dropped, since second-level precision stops being useful at that point.
 */
object DurationFormatter {
    fun format(context: Context, totalSeconds: Long): String {
        val seconds = totalSeconds.coerceAtLeast(0)
        val hours = (seconds / 3600).toInt()
        val minutes = ((seconds % 3600) / 60).toInt()
        val secs = (seconds % 60).toInt()

        val parts = mutableListOf<String>()
        when {
            hours > 0 -> {
                parts += context.resources.getQuantityString(R.plurals.duration_hours, hours, hours)
                if (minutes > 0) {
                    parts += context.resources.getQuantityString(R.plurals.duration_minutes, minutes, minutes)
                }
            }
            minutes > 0 -> {
                parts += context.resources.getQuantityString(R.plurals.duration_minutes, minutes, minutes)
                if (secs > 0) {
                    parts += context.resources.getQuantityString(R.plurals.duration_seconds, secs, secs)
                }
            }
            else -> parts += context.resources.getQuantityString(R.plurals.duration_seconds, secs, secs)
        }
        return parts.joinToString(" ")
    }
}
