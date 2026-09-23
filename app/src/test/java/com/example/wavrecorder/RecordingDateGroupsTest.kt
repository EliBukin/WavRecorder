package com.example.wavrecorder

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.text.SimpleDateFormat
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
class RecordingDateGroupsTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val now = SimpleDateFormat("yyyyMMdd HHmmss", Locale.US).parse("20260924 101500")!!.time

    private fun item(name: String) = RecordingItem(name, Uri.parse("file:///$name"), 1.0, 1)

    @Test
    fun `today and yesterday are named, older days are dated`() {
        assertEquals("Today", RecordingDateGroups.label(app, "recording_20260924_000001_part01.wav", now))
        assertEquals("Yesterday", RecordingDateGroups.label(app, "recording_20260923_235959_part03.wav", now))
        val older = RecordingDateGroups.label(app, "recording_20260919_120000_part01.wav", now)
        assertTrue("got $older", older.contains("19") && older.contains("Sep"))
        val lastYear = RecordingDateGroups.label(app, "recording_20251224_120000_part01.wav", now)
        assertTrue("an earlier year should include the year, got $lastYear", lastYear.contains("2025"))
    }

    @Test
    fun `names without the app's timestamp are grouped as other recordings`() {
        assertEquals(app.getString(R.string.date_group_other), RecordingDateGroups.label(app, "interview.wav", now))
    }

    @Test
    fun `a header starts each group and only when the day changes, without reordering anything`() {
        val items = listOf(
            item("recording_20260924_090000_part02.wav"),
            item("recording_20260924_080000_part01.wav"),
            item("recording_20260923_080000_part01.wav"),
            item("imported.wav"),
            // The list's own order (last-modified) is kept even if it puts a day out of sequence.
            item("recording_20260924_070000_part01.wav")
        )
        val snapshot = items.toList()
        val headers = items.indices.map { RecordingDateGroups.headerFor(app, items, it, now) }
        assertEquals(listOf("Today", null, "Yesterday", app.getString(R.string.date_group_other), "Today"), headers)
        assertEquals("grouping must not reorder or change the list", snapshot, items)
        assertNull(RecordingDateGroups.headerFor(app, items, 1, now))
    }
}
