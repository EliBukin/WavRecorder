package com.example.wavrecorder

import android.net.Uri
import android.os.Looper
import android.view.View
import androidx.fragment.app.testing.FragmentScenario
import androidx.fragment.app.testing.launchFragmentInContainer
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.ShadowToast
import org.robolectric.shadows.util.DataSource
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Covers bulk selection/deletion in the Library: entering/exiting selection mode, toggling
 * multiple recordings, honest partial-success reporting, stopping playback of a selected item
 * before deleting it, and preventing duplicate/stale background work -- the same class of
 * guarantee [LibraryFragmentTest]'s `refreshList` staleness tests already cover for the ordinary
 * scan path, applied to the destructive bulk-delete path instead.
 */
@RunWith(RobolectricTestRunner::class)
class LibraryFragmentBulkDeleteTest {

    private fun localItem(name: String): RecordingItem = RecordingItem(
        name = name, uri = Uri.parse("file:///$name"), durationSeconds = 1.0, sizeBytes = 100
    )

    private fun safItem(name: String): RecordingItem = RecordingItem(
        name = name, uri = Uri.parse("content://com.example.provider/$name"), durationSeconds = 1.0, sizeBytes = 100
    )

    private fun awaitToast(timeoutMs: Long = 2000): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (ShadowToast.getTextOfLatestToast() == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
            shadowOf(Looper.getMainLooper()).idle()
        }
        return ShadowToast.getTextOfLatestToast()
    }

    @Test
    fun `long-pressing a recording enters selection mode with it selected`() {
        val scenario = launchFragmentInContainer<LibraryFragment>(themeResId = R.style.Theme_WavRecorder)
        val item = localItem("a.wav")

        scenario.onFragment { fragment -> fragment.enterSelectionMode(item) }

        scenario.onFragment { fragment ->
            assertTrue(fragment.selectionMode)
            assertEquals(setOf(item.uri), fragment.selectedUris)
            assertEquals(View.VISIBLE, fragment.view!!.findViewById<View>(R.id.selectionToolbar).visibility)
        }
    }

    @Test
    fun `tapping additional recordings toggles their selection and updates the count`() {
        val scenario = launchFragmentInContainer<LibraryFragment>(themeResId = R.style.Theme_WavRecorder)
        val a = localItem("a.wav")
        val b = localItem("b.wav")

        scenario.onFragment { fragment ->
            fragment.enterSelectionMode(a)
            fragment.toggleSelection(b)
        }
        scenario.onFragment { fragment ->
            assertEquals(setOf(a.uri, b.uri), fragment.selectedUris)
            assertEquals(
                "2 selected",
                fragment.view!!.findViewById<android.widget.TextView>(R.id.selectionCountText).text.toString()
            )
        }

        scenario.onFragment { fragment -> fragment.toggleSelection(a) } // deselect

        scenario.onFragment { fragment ->
            assertEquals(setOf(b.uri), fragment.selectedUris)
            assertTrue("still in selection mode with one item left selected", fragment.selectionMode)
        }
    }

    @Test
    fun `deselecting the last item exits selection mode`() {
        val scenario = launchFragmentInContainer<LibraryFragment>(themeResId = R.style.Theme_WavRecorder)
        val a = localItem("a.wav")
        scenario.onFragment { fragment -> fragment.enterSelectionMode(a) }

        scenario.onFragment { fragment -> fragment.toggleSelection(a) }

        scenario.onFragment { fragment ->
            assertFalse(fragment.selectionMode)
            assertTrue(fragment.selectedUris.isEmpty())
        }
    }

    @Test
    fun `cancel exits selection mode without deleting anything`() {
        val scenario = launchFragmentInContainer<LibraryFragment>(themeResId = R.style.Theme_WavRecorder)
        var deleteCalls = 0
        scenario.onFragment { fragment ->
            fragment.destinationManager = object : DestinationManager(ApplicationProvider.getApplicationContext()) {
                override fun deleteRecording(uri: Uri): Boolean { deleteCalls++; return true }
            }
            fragment.enterSelectionMode(localItem("a.wav"))
            fragment.view!!.findViewById<View>(R.id.selectionCancelButton).performClick()
        }

        scenario.onFragment { fragment ->
            assertFalse(fragment.selectionMode)
            assertTrue(fragment.selectedUris.isEmpty())
            assertEquals(View.GONE, fragment.view!!.findViewById<View>(R.id.selectionToolbar).visibility)
        }
        assertEquals("cancel must never delete anything", 0, deleteCalls)
    }

    @Test
    fun `system back exits selection mode without deleting anything`() {
        val scenario = launchFragmentInContainer<LibraryFragment>(themeResId = R.style.Theme_WavRecorder)
        var deleteCalls = 0
        var backCallbackEnabled = false
        scenario.onFragment { fragment ->
            fragment.destinationManager = object : DestinationManager(ApplicationProvider.getApplicationContext()) {
                override fun deleteRecording(uri: Uri): Boolean { deleteCalls++; return true }
            }
            fragment.enterSelectionMode(localItem("a.wav"))
            backCallbackEnabled = fragment.requireActivity().onBackPressedDispatcher.hasEnabledCallbacks()
            fragment.requireActivity().onBackPressedDispatcher.onBackPressed()
        }

        assertTrue("the back callback must be enabled while selecting", backCallbackEnabled)
        scenario.onFragment { fragment -> assertFalse(fragment.selectionMode) }
        assertEquals(0, deleteCalls)
    }

    /** MaterialAlertDialogBuilder renders its title into a real TextView (the standard AppCompat
     * alertTitle id) rather than the framework's own bare Dialog.setTitle() text-only slot, which
     * ShadowAlertDialog.getTitle() doesn't pick up -- read it directly off the inflated view. */
    private fun dialogTitleText(dialog: androidx.appcompat.app.AlertDialog): String? =
        dialog.findViewById<android.widget.TextView>(androidx.appcompat.R.id.alertTitle)?.text?.toString()

    @Test
    fun `confirming deletion with one item selected uses the singular wording`() {
        val scenario = launchFragmentInContainer<LibraryFragment>(themeResId = R.style.Theme_WavRecorder)
        scenario.onFragment { fragment -> fragment.enterSelectionMode(localItem("a.wav")) }

        scenario.onFragment { fragment -> fragment.confirmBulkDelete() }

        val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        assertEquals("Delete 1 recording?", dialogTitleText(dialog))
    }

    @Test
    fun `confirming deletion with five items selected uses the plural wording`() {
        val scenario = launchFragmentInContainer<LibraryFragment>(themeResId = R.style.Theme_WavRecorder)
        scenario.onFragment { fragment ->
            fragment.enterSelectionMode(localItem("a.wav"))
            for (i in 2..5) fragment.toggleSelection(localItem("$i.wav"))
        }

        scenario.onFragment { fragment -> fragment.confirmBulkDelete() }

        val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        assertEquals("Delete 5 recordings?", dialogTitleText(dialog))
    }

    @Test
    fun `cancelling the confirmation dialog deletes nothing`() {
        val scenario = launchFragmentInContainer<LibraryFragment>(themeResId = R.style.Theme_WavRecorder)
        var deleteCalls = 0
        scenario.onFragment { fragment ->
            fragment.destinationManager = object : DestinationManager(ApplicationProvider.getApplicationContext()) {
                override fun deleteRecording(uri: Uri): Boolean { deleteCalls++; return true }
            }
            fragment.enterSelectionMode(localItem("a.wav"))
            fragment.confirmBulkDelete()
        }

        val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        dialog.getButton(android.content.DialogInterface.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(0, deleteCalls)
        scenario.onFragment { fragment -> assertTrue("selection is left intact on cancel", fragment.selectionMode) }
    }

    @Test
    fun `a successful multi-delete removes every target and reports full success`() {
        val scenario = launchFragmentInContainer<LibraryFragment>(themeResId = R.style.Theme_WavRecorder)
        val a = localItem("a.wav")
        val b = safItem("b.wav")
        val deleted = mutableListOf<Uri>()
        scenario.onFragment { fragment ->
            fragment.destinationManager = object : DestinationManager(ApplicationProvider.getApplicationContext()) {
                override fun deleteRecording(uri: Uri): Boolean { deleted.add(uri); return true }
                override fun listRecordings(): List<RecordingItem> = emptyList()
            }
            fragment.enterSelectionMode(a)
            fragment.toggleSelection(b)
            fragment.performBulkDelete(setOf(a.uri, b.uri))
        }

        val toast = awaitToast()

        assertEquals(setOf(a.uri, b.uri), deleted.toSet())
        assertEquals("Deleted 2 recordings", toast)
        scenario.onFragment { fragment -> assertFalse(fragment.selectionMode) }
    }

    @Test
    fun `a mixed success-failure result never claims total success and keeps failed items retryable`() {
        val scenario = launchFragmentInContainer<LibraryFragment>(themeResId = R.style.Theme_WavRecorder)
        val succeeds = localItem("succeeds.wav")
        val fails = localItem("fails.wav")
        scenario.onFragment { fragment ->
            fragment.destinationManager = object : DestinationManager(ApplicationProvider.getApplicationContext()) {
                override fun deleteRecording(uri: Uri): Boolean = uri == succeeds.uri
                // The failed target is still really "on disk" as far as this fake is concerned --
                // a real refresh must find it again, exactly like the production DestinationManager
                // re-scanning a real folder would.
                override fun listRecordings(): List<RecordingItem> = listOf(fails)
            }
            fragment.enterSelectionMode(succeeds)
            fragment.toggleSelection(fails)
            fragment.performBulkDelete(setOf(succeeds.uri, fails.uri))
        }

        val toast = awaitToast()

        assertEquals("1 deleted, 1 could not be deleted", toast)
        assertTrue(
            "a partial failure must never be worded as if everything succeeded",
            toast?.contains("could not be deleted") == true
        )
        // The refreshed list re-derives from disk truth: the still-failed item reappears, still
        // available for the user to select and retry.
        scenario.onFragment { fragment ->
            val adapter = fragment.view!!.findViewById<androidx.recyclerview.widget.RecyclerView>(
                R.id.recordingsList
            ).adapter!!
            assertEquals(
                "the still-failed target must reappear in the refreshed list, retryable",
                1, adapter.itemCount
            )
            assertFalse(fragment.selectionMode)
        }
    }

    /** Registers [uri] with Robolectric's MediaPlayer shadow so setDataSource()/prepareAsync()
     * against it succeeds without needing a real audio file -- same helper LibraryFragmentPlaybackTest
     * uses to establish genuine playback. preparationDelayMs=0: this test has no need to catch the
     * fragment mid-Preparing (that race is already covered elsewhere), just to reach a real
     * playing state deterministically. */
    private fun registerMedia(uri: Uri, durationMs: Int = 5000) {
        ShadowMediaPlayer.addMediaInfo(
            DataSource.toDataSource(ApplicationProvider.getApplicationContext(), uri),
            ShadowMediaPlayer.MediaInfo(durationMs, 0)
        )
    }

    /** A fresh ViewHolder bound to [position] -- same pattern LibraryFragmentPlaybackTest uses to
     * drive real playback through the adapter's own play button, rather than reaching into
     * LibraryFragment's private playback methods directly. */
    private fun holderFor(scenario: FragmentScenario<LibraryFragment>, position: Int): RecordingsAdapter.ViewHolder {
        var holder: RecordingsAdapter.ViewHolder? = null
        scenario.onFragment { fragment ->
            val recyclerView = fragment.requireView().findViewById<RecyclerView>(R.id.recordingsList)
            val adapter = recyclerView.adapter as RecordingsAdapter
            val h = adapter.onCreateViewHolder(recyclerView, 0)
            adapter.onBindViewHolder(h, position)
            holder = h
        }
        return requireNotNull(holder)
    }

    @Test
    fun `deleting a recording that is genuinely playing releases playback before the file is removed`() {
        val scenario = launchFragmentInContainer<LibraryFragment>(themeResId = R.style.Theme_WavRecorder)
        val playing = localItem("playing.wav")
        registerMedia(playing.uri)
        var deleteCalls = 0
        scenario.onFragment { fragment ->
            fragment.destinationManager = object : DestinationManager(ApplicationProvider.getApplicationContext()) {
                override fun deleteRecording(uri: Uri): Boolean { deleteCalls++; return true }
                override fun listRecordings(): List<RecordingItem> = listOf(playing)
            }
            fragment.refreshList()
        }
        // Waits for the async refreshList() coroutine to actually populate the adapter, exactly
        // like LibraryFragmentPlaybackTest.launchWithItems does.
        val deadline = System.currentTimeMillis() + 2000
        var itemCount = -1
        while (itemCount != 1 && System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
            shadowOf(Looper.getMainLooper()).idle()
            scenario.onFragment { fragment ->
                itemCount = fragment.requireView().findViewById<RecyclerView>(R.id.recordingsList).adapter?.itemCount ?: -1
            }
        }
        assertEquals("sanity: the item must actually be in the adapter before its play button can be tapped", 1, itemCount)

        // Tap the row's real play button -- exactly the interaction LibraryFragmentPlaybackTest
        // uses to establish genuine playback, not a hand-set field.
        holderFor(scenario, 0).binding.playButton.performClick()
        shadowOf(Looper.getMainLooper()).idle()

        scenario.onFragment { fragment ->
            assertEquals(
                "sanity: playback of the target recording must genuinely be active before deleting it",
                playing.uri, fragment.activeUri
            )
        }

        scenario.onFragment { fragment ->
            fragment.enterSelectionMode(playing)
            fragment.performBulkDelete(setOf(playing.uri))
        }
        val toast = awaitToast()

        assertEquals("Deleted 1 recording", toast)
        assertEquals(1, deleteCalls)
        scenario.onFragment { fragment ->
            assertNull(
                "playback of the just-deleted recording must be released, not left dangling",
                fragment.activeUri
            )
        }
    }

    @Test
    fun `a second bulk-delete tap while one is already running is ignored`() {
        val scenario = launchFragmentInContainer<LibraryFragment>(themeResId = R.style.Theme_WavRecorder)
        val startedCount = java.util.concurrent.atomic.AtomicInteger(0)
        val releaseDelete = CountDownLatch(1)
        val item = localItem("a.wav")
        scenario.onFragment { fragment ->
            fragment.destinationManager = object : DestinationManager(ApplicationProvider.getApplicationContext()) {
                override fun deleteRecording(uri: Uri): Boolean {
                    startedCount.incrementAndGet()
                    releaseDelete.await(5, TimeUnit.SECONDS)
                    return true
                }
                override fun listRecordings(): List<RecordingItem> = emptyList()
            }
            fragment.enterSelectionMode(item)
            fragment.performBulkDelete(setOf(item.uri)) // first, slow delete in flight
        }
        val deadline = System.currentTimeMillis() + 2000
        while (startedCount.get() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertTrue("sanity: the first delete must have actually started", startedCount.get() >= 1)

        scenario.onFragment { fragment ->
            fragment.enterSelectionMode(item) // re-select the same item while the delete is in flight
            fragment.confirmBulkDelete() // must be ignored: bulkDeleteInProgress is still true
        }

        releaseDelete.countDown()
        awaitToast()

        assertEquals("a second overlapping bulk-delete must never actually run", 1, startedCount.get())
    }

    @Test
    fun `two rapid taps on Delete selected before confirming allow only one confirmation and one deletion pass`() {
        val scenario = launchFragmentInContainer<LibraryFragment>(themeResId = R.style.Theme_WavRecorder)
        val attemptCounts = mutableMapOf<Uri, Int>()
        val item = localItem("a.wav")
        scenario.onFragment { fragment ->
            fragment.destinationManager = object : DestinationManager(ApplicationProvider.getApplicationContext()) {
                override fun deleteRecording(uri: Uri): Boolean {
                    attemptCounts[uri] = (attemptCounts[uri] ?: 0) + 1
                    return true
                }
                override fun listRecordings(): List<RecordingItem> = emptyList()
            }
            fragment.enterSelectionMode(item)
            // Two rapid taps on the toolbar's own delete action, before either dialog is ever
            // confirmed -- exactly the race confirmBulkDelete()'s dialog-dedup guard must close.
            fragment.view!!.findViewById<View>(R.id.selectionDeleteButton).performClick()
            fragment.view!!.findViewById<View>(R.id.selectionDeleteButton).performClick()
        }

        assertEquals(
            "only one confirmation dialog must ever be shown for two rapid delete-action taps",
            1, ShadowDialog.getShownDialogs().size
        )

        val dialog = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
        val toast = awaitToast()

        assertEquals("every target must be attempted at most once", 1, attemptCounts[item.uri])
        assertEquals("only one completion result must ever be applied", "Deleted 1 recording", toast)
    }

    @Test
    fun `a bulk-delete result arriving after the view is destroyed is discarded safely`() {
        val scenario = launchFragmentInContainer<LibraryFragment>(themeResId = R.style.Theme_WavRecorder)
        val releaseDelete = CountDownLatch(1)
        val item = localItem("a.wav")
        scenario.onFragment { fragment ->
            fragment.destinationManager = object : DestinationManager(ApplicationProvider.getApplicationContext()) {
                override fun deleteRecording(uri: Uri): Boolean {
                    releaseDelete.await(5, TimeUnit.SECONDS)
                    return true
                }
            }
            fragment.enterSelectionMode(item)
            fragment.performBulkDelete(setOf(item.uri))
        }

        scenario.moveToState(Lifecycle.State.DESTROYED) // tears down the view mid-delete

        releaseDelete.countDown()
        // Give the background thread's completion Handler.post a real chance to run; must not
        // crash, and there is no view left to assert against -- the absence of a crash is the point.
        Thread.sleep(100)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `a bulk-delete result arriving after a newer scan is not treated as stale by refreshList's own guard, and still reports honestly`() {
        // performBulkDelete() always ends by calling refreshList() itself, which already has its
        // own newest-wins staleness guard (see LibraryFragmentTest) -- this confirms the combined
        // flow still reports the delete's own outcome honestly even when a concurrent scan is
        // triggered in between.
        val scenario = launchFragmentInContainer<LibraryFragment>(themeResId = R.style.Theme_WavRecorder)
        val item = localItem("a.wav")
        scenario.onFragment { fragment ->
            fragment.destinationManager = object : DestinationManager(ApplicationProvider.getApplicationContext()) {
                override fun deleteRecording(uri: Uri): Boolean = true
                override fun listRecordings(): List<RecordingItem> = emptyList()
            }
            fragment.enterSelectionMode(item)
            fragment.performBulkDelete(setOf(item.uri))
            fragment.refreshList() // a newer, independent scan racing the delete's own closing refresh
        }

        val toast = awaitToast()
        assertEquals("Deleted 1 recording", toast)
    }
}
