package com.example.wavrecorder

import android.net.Uri
import android.os.Looper
import androidx.fragment.app.testing.launchFragmentInContainer
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowToast
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Covers [LibraryFragment.refreshList] against a [DestinationManager] that fails outright --
 * standing in for a revoked SAF permission or another provider-level failure, which Robolectric
 * can't reliably reproduce at the real DocumentFile/ContentResolver level. Before this, nothing
 * caught listRecordings() throwing, so the background scan thread would just die silently: no
 * crash, but also no list update and no indication to the user that anything went wrong.
 */
@RunWith(RobolectricTestRunner::class)
class LibraryFragmentTest {

    @Test
    fun `refreshList surfaces a revoked SAF permission as a clear error instead of failing silently`() {
        val scenario = launchFragmentInContainer<LibraryFragment>(themeResId = R.style.Theme_WavRecorder)

        scenario.onFragment { fragment ->
            fragment.destinationManager = object : DestinationManager(ApplicationProvider.getApplicationContext()) {
                override fun listRecordings(): List<RecordingItem> {
                    throw SecurityException("Permission to the selected folder has been revoked")
                }
            }
        }
        scenario.onFragment { fragment -> fragment.refreshList() }

        // The scan itself runs on a real background thread, so this polls (rather than a single
        // idle()) to give it a moment to actually reach and throw before there's anything for
        // idle() to deliver.
        val deadline = System.currentTimeMillis() + 2000
        while (ShadowToast.getTextOfLatestToast() == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
            shadowOf(Looper.getMainLooper()).idle()
        }

        val toastText = ShadowToast.getTextOfLatestToast()
        assertTrue(
            "expected a clear error toast mentioning the failure, got: $toastText",
            toastText != null && toastText.contains("Permission to the selected folder has been revoked")
        )
    }

    @Test
    fun `a stale refreshList result cannot overwrite a newer one`() {
        val scenario = launchFragmentInContainer<LibraryFragment>(themeResId = R.style.Theme_WavRecorder)

        val slowItem = RecordingItem(
            name = "slow.wav", uri = Uri.parse("file:///slow.wav"), durationSeconds = 1.0, sizeBytes = 100
        )
        val fastItem = RecordingItem(
            name = "fast.wav", uri = Uri.parse("file:///fast.wav"), durationSeconds = 1.0, sizeBytes = 100
        )
        val releaseSlowScan = CountDownLatch(1)

        scenario.onFragment { fragment ->
            fragment.destinationManager = object : DestinationManager(ApplicationProvider.getApplicationContext()) {
                var callCount = 0
                override fun listRecordings(): List<RecordingItem> {
                    callCount++
                    return if (callCount == 1) {
                        // The first (older) scan blocks until the second (newer) one has already
                        // been kicked off, so it always finishes and posts its result *after* the
                        // newer one -- the exact "out of order completion" scenario a naive
                        // implementation would get wrong.
                        releaseSlowScan.await(5, TimeUnit.SECONDS)
                        listOf(slowItem)
                    } else {
                        listOf(fastItem)
                    }
                }
            }
        }

        scenario.onFragment { fragment -> fragment.refreshList() } // the slow, older scan
        scenario.onFragment { fragment -> fragment.refreshList() } // the fast, newer scan
        releaseSlowScan.countDown() // let the older scan finish, racing to land after the newer one

        val deadline = System.currentTimeMillis() + 2000
        var itemCount = -1
        while (itemCount != 1 && System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
            shadowOf(Looper.getMainLooper()).idle()
            scenario.onFragment { fragment ->
                val recyclerView = fragment.requireView().findViewById<RecyclerView>(R.id.recordingsList)
                itemCount = recyclerView.adapter?.itemCount ?: -1
            }
        }

        var displayedName: String? = null
        scenario.onFragment { fragment ->
            val recyclerView = fragment.requireView().findViewById<RecyclerView>(R.id.recordingsList)
            val adapter = recyclerView.adapter as RecordingsAdapter
            val holder = adapter.onCreateViewHolder(recyclerView, 0)
            adapter.onBindViewHolder(holder, 0)
            displayedName = holder.binding.fileName.text.toString()
        }

        assertEquals(1, itemCount)
        assertTrue(
            "the stale (slower, older) scan's result must not have overwritten the newer one, " +
                "displayed: $displayedName",
            displayedName == RecordingNameFormatter.friendlyTitle(fastItem.name)
        )
    }
}
