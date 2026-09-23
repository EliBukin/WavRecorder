package com.example.wavrecorder

import android.content.Context
import android.text.format.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Presentation-only date grouping for the Library list: "Today", "Yesterday", or a formatted
 * date, derived from the timestamp in each recording's file name. It never reorders anything --
 * [headerFor] only decides whether a given row, in the order the list already has, starts a new
 * group (i.e. its label differs from the row above it). Rows whose names don't carry the app's
 * timestamp are labelled "Other recordings".
 */
internal object RecordingDateGroups {

    fun label(context: Context, fileName: String, nowMillis: Long): String {
        val started = RecordingNameFormatter.startedAt(fileName)
            ?: return context.getString(R.string.date_group_other)
        val today = startOfDay(nowMillis)
        val day = startOfDay(started.time)
        val yesterday = Calendar.getInstance().apply {
            timeInMillis = today
            add(Calendar.DAY_OF_YEAR, -1)
        }.timeInMillis
        return when (day) {
            today -> context.getString(R.string.date_group_today)
            yesterday -> context.getString(R.string.date_group_yesterday)
            else -> dayFormat(sameYear(day, today)).format(Date(day))
        }
    }

    /** The group label to show above row [position], or null if it continues the group above. */
    fun headerFor(context: Context, items: List<RecordingItem>, position: Int, nowMillis: Long): String? {
        val current = label(context, items[position].name, nowMillis)
        if (position == 0) return current
        return if (label(context, items[position - 1].name, nowMillis) == current) null else current
    }

    private fun startOfDay(millis: Long): Long = Calendar.getInstance().apply {
        timeInMillis = millis
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun sameYear(a: Long, b: Long): Boolean {
        val ca = Calendar.getInstance().apply { timeInMillis = a }
        val cb = Calendar.getInstance().apply { timeInMillis = b }
        return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR)
    }

    private fun dayFormat(sameYear: Boolean): SimpleDateFormat {
        val locale = Locale.getDefault()
        val skeleton = if (sameYear) "EEEEMMMMd" else "EEEMMMdyyyy"
        return SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, skeleton), locale)
    }
}
