package com.example.wavrecorder

import android.net.Uri
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.fragment.app.testing.FragmentScenario
import androidx.fragment.app.testing.launchFragmentInContainer
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** The Library's own header, its empty state, and the header's Select entry into bulk selection. */
@RunWith(RobolectricTestRunner::class)
class LibraryFragmentRedesignTest {

    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()

    private fun item(name: String) = RecordingItem(name, Uri.parse("file:///$name"), 13.0, 250_000)

    private fun launchWith(items: List<RecordingItem>): FragmentScenario<LibraryFragment> {
        val scenario = launchFragmentInContainer<LibraryFragment>(themeResId = R.style.Theme_WavRecorder)
        scenario.onFragment { fragment ->
            fragment.destinationManager = object : DestinationManager(app) {
                override fun listRecordings() = items
            }
            fragment.refreshList()
        }
        val deadline = System.currentTimeMillis() + 2000
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
            shadowOf(Looper.getMainLooper()).idle()
            var shown = -1
            scenario.onFragment { shown = it.requireView().findViewById<RecyclerView>(R.id.recordingsList).adapter!!.itemCount }
            val emptyVisible = view<View>(scenario, R.id.emptyState).visibility == View.VISIBLE
            if (shown == items.size && (items.isNotEmpty() || emptyVisible)) break
        }
        return scenario
    }

    private fun <T : View> view(scenario: FragmentScenario<LibraryFragment>, id: Int): T {
        var v: T? = null
        scenario.onFragment { v = it.requireView().findViewById(id) }
        return v!!
    }

    @Test
    fun `an empty library shows an icon, a title and a short explanation, and nothing to select`() {
        val scenario = launchWith(emptyList())
        assertEquals(View.VISIBLE, view<View>(scenario, R.id.emptyState).visibility)
        assertEquals(View.VISIBLE, view<View>(scenario, R.id.emptyIcon).visibility)
        assertEquals(app.getString(R.string.empty_recordings), view<TextView>(scenario, R.id.emptyText).text.toString())
        assertEquals(app.getString(R.string.empty_recordings_hint), view<TextView>(scenario, R.id.emptyHint).text.toString())
        assertEquals(View.GONE, view<View>(scenario, R.id.selectButton).visibility)
        assertEquals(app.getString(R.string.library_title), view<TextView>(scenario, R.id.libraryTitle).text.toString())
        assertTrue(ViewCompat.isAccessibilityHeading(view(scenario, R.id.libraryTitle)))
    }

    @Test
    fun `a populated library hides the empty state and offers Select`() {
        val scenario = launchWith(listOf(item("recording_20260924_090000_part01.wav"), item("b.wav")))
        assertEquals(View.GONE, view<View>(scenario, R.id.emptyState).visibility)
        assertEquals(View.VISIBLE, view<View>(scenario, R.id.selectButton).visibility)
        assertEquals(app.getString(R.string.library_select_description),
            view<View>(scenario, R.id.selectButton).contentDescription.toString())
    }

    @Test
    fun `Select enters selection mode with nothing selected and delete unavailable until something is`() {
        val items = listOf(item("a.wav"), item("b.wav"))
        val scenario = launchWith(items)
        view<View>(scenario, R.id.selectButton).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        var selectionMode = false
        scenario.onFragment { selectionMode = it.selectionMode }
        assertTrue(selectionMode)
        assertEquals(View.VISIBLE, view<View>(scenario, R.id.selectionToolbar).visibility)
        assertEquals("the title row gives way to the toolbar", View.GONE, view<View>(scenario, R.id.libraryHeader).visibility)
        assertEquals(app.getString(R.string.selection_none), view<TextView>(scenario, R.id.selectionCountText).text.toString())
        assertFalse(view<View>(scenario, R.id.selectionDeleteButton).isEnabled)

        scenario.onFragment { it.toggleSelection(items[1]) }
        assertEquals(app.resources.getQuantityString(R.plurals.selection_count, 1, 1),
            view<TextView>(scenario, R.id.selectionCountText).text.toString())
        assertTrue(view<View>(scenario, R.id.selectionDeleteButton).isEnabled)
        assertEquals(app.getString(R.string.bulk_delete_action),
            view<View>(scenario, R.id.selectionDeleteButton).contentDescription.toString())

        view<View>(scenario, R.id.selectionCancelButton).performClick()
        assertEquals(View.VISIBLE, view<View>(scenario, R.id.libraryHeader).visibility)
        assertEquals(View.GONE, view<View>(scenario, R.id.selectionToolbar).visibility)
    }
}
