package com.example.wavrecorder

import android.net.Uri
import android.os.Looper
import androidx.fragment.app.testing.launchFragmentInContainer
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowToast
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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
        val slowScanStarted = CountDownLatch(1)
        val callCount = AtomicInteger(0)

        scenario.onFragment { fragment ->
            fragment.destinationManager = object : DestinationManager(ApplicationProvider.getApplicationContext()) {
                override fun listRecordings(): List<RecordingItem> {
                    return if (callCount.incrementAndGet() == 1) {
                        // The first (older) scan blocks until the second (newer) one has already
                        // been kicked off, so it always finishes and posts its result *after* the
                        // newer one -- the exact "out of order completion" scenario a naive
                        // implementation would get wrong.
                        slowScanStarted.countDown()
                        releaseSlowScan.await(5, TimeUnit.SECONDS)
                        listOf(slowItem)
                    } else {
                        listOf(fastItem)
                    }
                }
            }
        }

        scenario.onFragment { fragment -> fragment.refreshList() } // the slow, older scan
        // Deterministically wait for the slow scan to have actually entered its blocking read
        // (runs on a real Dispatchers.IO thread, asynchronously to this test thread) before
        // triggering the second call -- without this, the two calls' own listRecordings()
        // invocations could in principle race on which one Dispatchers.IO's shared thread pool
        // schedules first, making "callCount == 1" ambiguous between them.
        assertTrue("sanity: the slow scan must have actually started", slowScanStarted.await(2, TimeUnit.SECONDS))
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

    @Test
    fun `destroying the view while a scan is still in flight produces no crash and no stale UI update`() {
        val scenario = launchFragmentInContainer<LibraryFragment>(themeResId = R.style.Theme_WavRecorder)
        val releaseScan = CountDownLatch(1)
        var scanReached = false

        scenario.onFragment { fragment ->
            fragment.destinationManager = object : DestinationManager(ApplicationProvider.getApplicationContext()) {
                override fun listRecordings(): List<RecordingItem> {
                    scanReached = true
                    releaseScan.await(5, TimeUnit.SECONDS)
                    return listOf(
                        RecordingItem(
                            name = "late.wav", uri = Uri.parse("file:///late.wav"),
                            durationSeconds = 1.0, sizeBytes = 100
                        )
                    )
                }
            }
        }
        scenario.onFragment { fragment -> fragment.refreshList() }

        val deadline = System.currentTimeMillis() + 2000
        while (!scanReached && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertTrue("sanity: expected the background scan to have actually started", scanReached)

        scenario.moveToState(androidx.lifecycle.Lifecycle.State.DESTROYED)
        releaseScan.countDown() // let the scan finish only after the view is already gone

        // If the posted result isn't guarded by the _binding == null check, this throws (touching
        // a destroyed view's binding/adapter). Reaching the end without an exception is itself the
        // assertion, exactly like LibraryFragmentPlaybackTest's equivalent for playback.
        val idleDeadline = System.currentTimeMillis() + 2000
        while (System.currentTimeMillis() < idleDeadline) {
            Thread.sleep(5)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun `repeated refresh requests coalesce so stale scans are cancelled rather than piling up unbounded work`() {
        val scenario = launchFragmentInContainer<LibraryFragment>(themeResId = R.style.Theme_WavRecorder)
        val invocationCount = AtomicInteger(0)

        scenario.onFragment { fragment ->
            fragment.destinationManager = object : DestinationManager(ApplicationProvider.getApplicationContext()) {
                override fun listRecordings(): List<RecordingItem> {
                    val call = invocationCount.incrementAndGet()
                    return listOf(
                        RecordingItem(
                            name = "call$call.wav", uri = Uri.parse("file:///call$call.wav"),
                            durationSeconds = 1.0, sizeBytes = 100
                        )
                    )
                }
            }
        }

        // A burst of rapid, repeated refresh requests -- e.g. onResume() firing more than once in
        // quick succession -- must coalesce to the newest one (via refreshJob?.cancel()), not
        // accumulate one full scan per call the way the old unbounded-queue executor did.
        repeat(10) {
            scenario.onFragment { fragment -> fragment.refreshList() }
        }

        val deadline = System.currentTimeMillis() + 3000
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

        assertEquals(
            "expected only the newest of the 10 rapid refresh requests to actually apply a result",
            RecordingNameFormatter.friendlyTitle("call${invocationCount.get()}.wav"), displayedName
        )
        assertTrue(
            "expected cancellation to bound how many of the 10 rapid requests actually ran a " +
                "full scan, not one scan per call; ran ${invocationCount.get()}",
            invocationCount.get() < 10
        )
    }

    @Test
    fun `a blocking refresh observes interruption and exits after view destruction without the test manually releasing it`() {
        // Regression test for the switch to runInterruptible: unlike the old withContext-based
        // scan (which could only ever have its *result* discarded, never actually stopped mid-call
        // -- see LibraryFragmentPlaybackTest-style tests elsewhere for that weaker guarantee),
        // destroying the view must now genuinely interrupt a blocking scan that itself respects
        // interruption (like CountDownLatch.await()), without the test ever releasing the latch
        // itself.
        val scenario = launchFragmentInContainer<LibraryFragment>(themeResId = R.style.Theme_WavRecorder)
        val neverReleased = CountDownLatch(1) // deliberately never counted down by the test
        var scanReached = false
        var observedInterruption = false

        scenario.onFragment { fragment ->
            fragment.destinationManager = object : DestinationManager(ApplicationProvider.getApplicationContext()) {
                override fun listRecordings(): List<RecordingItem> {
                    scanReached = true
                    try {
                        neverReleased.await(5, TimeUnit.SECONDS)
                    } catch (e: InterruptedException) {
                        observedInterruption = true
                        throw e
                    }
                    return listOf(
                        RecordingItem(name = "late.wav", uri = Uri.parse("file:///late.wav"),
                            durationSeconds = 1.0, sizeBytes = 100)
                    )
                }
            }
        }
        scenario.onFragment { fragment -> fragment.refreshList() }

        val deadline = System.currentTimeMillis() + 2000
        while (!scanReached && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertTrue("sanity: expected the background scan to have actually started", scanReached)

        scenario.moveToState(androidx.lifecycle.Lifecycle.State.DESTROYED)

        val interruptDeadline = System.currentTimeMillis() + 2000
        while (!observedInterruption && System.currentTimeMillis() < interruptDeadline) {
            Thread.sleep(5)
            shadowOf(Looper.getMainLooper()).idle()
        }

        assertTrue("expected view destruction to genuinely interrupt the blocking scan, not just " +
            "discard its eventual result", observedInterruption)
    }

    @Test
    fun `destroying the view cancels a queued or in-flight refresh so it never applies a result`() {
        val scenario = launchFragmentInContainer<LibraryFragment>(themeResId = R.style.Theme_WavRecorder)
        val releaseScan = CountDownLatch(1)
        var scanReached = false

        scenario.onFragment { fragment ->
            fragment.destinationManager = object : DestinationManager(ApplicationProvider.getApplicationContext()) {
                override fun listRecordings(): List<RecordingItem> {
                    scanReached = true
                    // Not itself interruptible (ignores InterruptedException, unlike
                    // CountDownLatch.await()) -- standing in for a provider that genuinely can't
                    // be interrupted, so the *result-discarded* guarantee (never applying a result
                    // to a destroyed view) is what's actually under test here, distinct from the
                    // genuine-interruption guarantee covered above.
                    val deadline = System.currentTimeMillis() + 2000
                    while (releaseScan.count > 0 && System.currentTimeMillis() < deadline) Thread.sleep(5)
                    return listOf(
                        RecordingItem(name = "late.wav", uri = Uri.parse("file:///late.wav"),
                            durationSeconds = 1.0, sizeBytes = 100)
                    )
                }
            }
        }
        scenario.onFragment { fragment -> fragment.refreshList() }

        val deadline = System.currentTimeMillis() + 2000
        while (!scanReached && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertTrue("sanity: expected the background scan to have actually started", scanReached)

        scenario.moveToState(androidx.lifecycle.Lifecycle.State.DESTROYED)
        releaseScan.countDown()

        val idleDeadline = System.currentTimeMillis() + 2000
        while (System.currentTimeMillis() < idleDeadline) {
            Thread.sleep(5)
            shadowOf(Looper.getMainLooper()).idle()
        }

        // A provider that genuinely ignores interruption must still never update a destroyed
        // view -- reaching here without an exception (touching a null _binding/adapter) is the
        // assertion.
    }

    @Test
    fun `dismissing the stats dialog cancels its job and never updates the dialog afterward`() {
        val scenario = launchFragmentInContainer<LibraryFragment>(themeResId = R.style.Theme_WavRecorder)
        val item = RecordingItem(
            name = "track.wav", uri = Uri.parse("file:///track.wav"), durationSeconds = 1.0, sizeBytes = 100
        )
        val releaseScan = CountDownLatch(1)
        var scanReached = false

        scenario.onFragment { fragment ->
            fragment.statsReader = { _, _ ->
                scanReached = true
                releaseScan.await(5, TimeUnit.SECONDS)
                AudioStats(
                    sampleRate = 48000, channels = 1, bitsPerSample = 16, durationSeconds = 1.0,
                    sizeBytes = 100, sampleCount = 100, peakDbfs = null, rmsDbfs = null, clippedSamples = 0
                )
            }
        }
        scenario.onFragment { fragment -> fragment.showStats(item) }

        val deadline = System.currentTimeMillis() + 2000
        while (!scanReached && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertTrue("sanity: expected the stats scan to have actually started", scanReached)

        val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog() as? androidx.appcompat.app.AlertDialog
        assertNotNull("sanity: expected a stats dialog to have been shown", dialog)
        dialog!!.dismiss()
        releaseScan.countDown() // let the (now-cancelled, dismissed) scan actually return

        val idleDeadline = System.currentTimeMillis() + 2000
        while (System.currentTimeMillis() < idleDeadline) {
            Thread.sleep(5)
            shadowOf(Looper.getMainLooper()).idle()
        }

        val statsText = dialog.findViewById<android.widget.TextView>(R.id.statsText)
        assertEquals(
            "the dialog's stats text must never be updated with the scan's result once the " +
                "dialog was already dismissed",
            android.view.View.GONE, statsText?.visibility ?: android.view.View.GONE
        )
    }

    @Test
    fun `destroying the view during a statistics scan causes no crash and no update to a torn-down dialog`() {
        val scenario = launchFragmentInContainer<LibraryFragment>(themeResId = R.style.Theme_WavRecorder)
        val item = RecordingItem(
            name = "track.wav", uri = Uri.parse("file:///track.wav"), durationSeconds = 1.0, sizeBytes = 100
        )
        val releaseScan = CountDownLatch(1)
        var scanReached = false

        scenario.onFragment { fragment ->
            fragment.statsReader = { _, _ ->
                scanReached = true
                releaseScan.await(5, TimeUnit.SECONDS)
                null
            }
        }
        scenario.onFragment { fragment -> fragment.showStats(item) }

        val deadline = System.currentTimeMillis() + 2000
        while (!scanReached && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertTrue(scanReached)

        scenario.moveToState(androidx.lifecycle.Lifecycle.State.DESTROYED)
        releaseScan.countDown()

        // Reaching this point without an exception (touching a destroyed view/binding/dialog) is
        // the assertion, mirroring the equivalent refreshList()/playback tests.
        val idleDeadline = System.currentTimeMillis() + 2000
        while (System.currentTimeMillis() < idleDeadline) {
            Thread.sleep(5)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun `a fresh refreshList call after the view is recreated is not affected by the old view's cancelled work`() {
        val scenario = launchFragmentInContainer<LibraryFragment>(themeResId = R.style.Theme_WavRecorder)
        val oldViewReleaseScan = CountDownLatch(1)
        var oldViewScanReached = false

        scenario.onFragment { fragment ->
            fragment.destinationManager = object : DestinationManager(ApplicationProvider.getApplicationContext()) {
                override fun listRecordings(): List<RecordingItem> {
                    oldViewScanReached = true
                    oldViewReleaseScan.await(5, TimeUnit.SECONDS)
                    return listOf(
                        RecordingItem(name = "stale.wav", uri = Uri.parse("file:///stale.wav"),
                            durationSeconds = 1.0, sizeBytes = 100)
                    )
                }
            }
        }
        scenario.onFragment { fragment -> fragment.refreshList() } // old view's in-flight scan

        val deadline = System.currentTimeMillis() + 2000
        while (!oldViewScanReached && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertTrue(oldViewScanReached)

        // Destroy the whole scenario (standing in for the old view/lifecycleScope going away) and
        // launch a completely fresh Fragment instance -- exercising that a new refreshList() call
        // against a genuinely new view/scope works cleanly, with nothing needing to be manually
        // reinitialized the way the old backgroundExecutor field once did in onViewCreated().
        scenario.moveToState(androidx.lifecycle.Lifecycle.State.DESTROYED)
        oldViewReleaseScan.countDown()

        val freshScenario = launchFragmentInContainer<LibraryFragment>(themeResId = R.style.Theme_WavRecorder)
        val freshItem = RecordingItem(name = "fresh.wav", uri = Uri.parse("file:///fresh.wav"),
            durationSeconds = 1.0, sizeBytes = 100)
        freshScenario.onFragment { fragment ->
            fragment.destinationManager = object : DestinationManager(ApplicationProvider.getApplicationContext()) {
                override fun listRecordings(): List<RecordingItem> = listOf(freshItem)
            }
        }
        freshScenario.onFragment { fragment -> fragment.refreshList() }

        val freshDeadline = System.currentTimeMillis() + 2000
        var itemCount = -1
        while (itemCount != 1 && System.currentTimeMillis() < freshDeadline) {
            Thread.sleep(5)
            shadowOf(Looper.getMainLooper()).idle()
            freshScenario.onFragment { fragment ->
                val recyclerView = fragment.requireView().findViewById<RecyclerView>(R.id.recordingsList)
                itemCount = recyclerView.adapter?.itemCount ?: -1
            }
        }

        assertEquals("expected the fresh Fragment/view's own refreshList() to work cleanly, " +
            "unaffected by the old (destroyed) view's cancelled scan", 1, itemCount)
    }

    @Test
    fun `rapid refresh requests genuinely interrupt the prior reader rather than merely discarding its result`() {
        val scenario = launchFragmentInContainer<LibraryFragment>(themeResId = R.style.Theme_WavRecorder)
        val firstScanReached = CountDownLatch(1)
        var firstScanInterrupted = false

        scenario.onFragment { fragment ->
            fragment.destinationManager = object : DestinationManager(ApplicationProvider.getApplicationContext()) {
                var callCount = 0
                override fun listRecordings(): List<RecordingItem> {
                    callCount++
                    if (callCount == 1) {
                        firstScanReached.countDown()
                        try {
                            CountDownLatch(1).await(5, TimeUnit.SECONDS) // never released -- must be interrupted, not waited out
                        } catch (e: InterruptedException) {
                            firstScanInterrupted = true
                            throw e
                        }
                    }
                    return listOf(RecordingItem(name = "newest.wav", uri = Uri.parse("file:///newest.wav"),
                        durationSeconds = 1.0, sizeBytes = 100))
                }
            }
        }

        scenario.onFragment { fragment -> fragment.refreshList() } // the older, soon-to-be-superseded scan
        assertTrue("sanity: the older scan must have actually started",
            firstScanReached.await(2, TimeUnit.SECONDS))
        scenario.onFragment { fragment -> fragment.refreshList() } // supersedes it

        val deadline = System.currentTimeMillis() + 2000
        while (!firstScanInterrupted && System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
            shadowOf(Looper.getMainLooper()).idle()
        }

        assertTrue("expected the superseded scan to be genuinely interrupted, not left to run to " +
            "completion as abandoned background work", firstScanInterrupted)
    }

    @Test
    fun `dismissing the statistics dialog interrupts a cooperative reader and closes its stream`() {
        val scenario = launchFragmentInContainer<LibraryFragment>(themeResId = R.style.Theme_WavRecorder)
        val item = RecordingItem(
            name = "track.wav", uri = Uri.parse("file:///track.wav"), durationSeconds = 1.0, sizeBytes = 100
        )
        var scanReached = false
        var streamClosed = false
        var observedInterruption = false

        scenario.onFragment { fragment ->
            fragment.statsReader = { _, _ ->
                scanReached = true
                try {
                    try {
                        CountDownLatch(1).await(5, TimeUnit.SECONDS) // never released
                    } catch (e: InterruptedException) {
                        observedInterruption = true
                        throw e
                    }
                    null
                } finally {
                    // Models a try-with-resources/use{}-closed stream: must run regardless of how
                    // the read above actually terminated.
                    streamClosed = true
                }
            }
        }
        scenario.onFragment { fragment -> fragment.showStats(item) }

        val deadline = System.currentTimeMillis() + 2000
        while (!scanReached && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertTrue("sanity: expected the stats scan to have actually started", scanReached)

        val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog() as? androidx.appcompat.app.AlertDialog
        assertNotNull(dialog)
        dialog!!.dismiss()

        val interruptDeadline = System.currentTimeMillis() + 2000
        while (!observedInterruption && System.currentTimeMillis() < interruptDeadline) {
            Thread.sleep(5)
            shadowOf(Looper.getMainLooper()).idle()
        }

        assertTrue("expected dismissing the dialog to genuinely interrupt the cooperative reader",
            observedInterruption)
        assertTrue("expected the reader's own stream-closing cleanup to have run", streamClosed)
    }

    @Test
    fun `destroying the view dismisses an open statistics dialog and releases its binding`() {
        val scenario = launchFragmentInContainer<LibraryFragment>(themeResId = R.style.Theme_WavRecorder)
        val item = RecordingItem(
            name = "track.wav", uri = Uri.parse("file:///track.wav"), durationSeconds = 1.0, sizeBytes = 100
        )
        var scanReached = false

        scenario.onFragment { fragment ->
            fragment.statsReader = { _, _ ->
                scanReached = true
                CountDownLatch(1).await(5, TimeUnit.SECONDS) // never released
                null
            }
        }
        scenario.onFragment { fragment -> fragment.showStats(item) }

        val deadline = System.currentTimeMillis() + 2000
        while (!scanReached && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertTrue(scanReached)

        val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog() as? androidx.appcompat.app.AlertDialog
        assertNotNull("sanity: expected a stats dialog to have been shown", dialog)
        assertTrue("sanity: the dialog must genuinely be showing before view destruction", dialog!!.isShowing)

        scenario.moveToState(androidx.lifecycle.Lifecycle.State.DESTROYED)

        assertTrue(
            "expected onDestroyView() to explicitly dismiss a still-open statistics dialog, " +
                "rather than leaving it on screen with a stale window reference",
            !dialog.isShowing
        )
    }

    @Test
    fun `a CancellationException from a superseded refresh never produces a failure toast`() {
        val scenario = launchFragmentInContainer<LibraryFragment>(themeResId = R.style.Theme_WavRecorder)
        val firstScanReached = CountDownLatch(1)

        scenario.onFragment { fragment ->
            fragment.destinationManager = object : DestinationManager(ApplicationProvider.getApplicationContext()) {
                var callCount = 0
                override fun listRecordings(): List<RecordingItem> {
                    callCount++
                    if (callCount == 1) {
                        firstScanReached.countDown()
                        CountDownLatch(1).await(5, TimeUnit.SECONDS) // interrupted by the superseding call below
                    }
                    return listOf(RecordingItem(name = "newest.wav", uri = Uri.parse("file:///newest.wav"),
                        durationSeconds = 1.0, sizeBytes = 100))
                }
            }
        }

        scenario.onFragment { fragment -> fragment.refreshList() }
        assertTrue(firstScanReached.await(2, TimeUnit.SECONDS))
        scenario.onFragment { fragment -> fragment.refreshList() } // interrupts and supersedes it

        val deadline = System.currentTimeMillis() + 2000
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
            shadowOf(Looper.getMainLooper()).idle()
        }

        assertEquals(
            "an interrupted/cancelled superseded scan must never be shown as a load-failure toast " +
                "-- it was superseded, not genuinely broken",
            null, ShadowToast.getTextOfLatestToast()
        )
    }
}
