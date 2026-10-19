package bukin.wavrecorder

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
 * group (i.e. its label differs from the row above it). A row whose name doesn't carry the app's
 * timestamp -- a renamed recording, say -- is dated by when its file was last written instead, and
 * labelled "Other recordings" only if that isn't known either.
 */
internal object RecordingDateGroups {

    /** [lastModifiedMillis] dates a file whose name has no timestamp; 0 means unknown. */
    fun label(context: Context, fileName: String, nowMillis: Long, lastModifiedMillis: Long = 0L): String {
        val started = RecordingNameFormatter.startedAt(fileName)?.time
            ?: lastModifiedMillis.takeIf { it > 0L }
            ?: return context.getString(R.string.date_group_other)
        val today = startOfDay(nowMillis)
        val day = startOfDay(started)
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
        val current = labelOf(context, items[position], nowMillis)
        if (position == 0) return current
        return if (labelOf(context, items[position - 1], nowMillis) == current) null else current
    }

    private fun labelOf(context: Context, item: RecordingItem, nowMillis: Long): String =
        label(context, item.name, nowMillis, item.lastModifiedMillis)

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
