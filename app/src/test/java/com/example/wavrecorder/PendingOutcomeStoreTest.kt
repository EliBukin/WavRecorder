package com.example.wavrecorder

import android.app.Application
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Focused coverage for [PendingOutcomeStore]'s durability contract: [PendingOutcomeStore.persist]
 * and [PendingOutcomeStore.clear] use a synchronous [android.content.SharedPreferences.Editor.commit]
 * (not the asynchronous, write-behind `apply()`) and surface its success/failure, and every entry
 * point is synchronized so overlapping calls can't corrupt or double-deliver the single pending
 * record. See the class doc on [PendingOutcomeStore] for the residual guarantee limitation this
 * can't close (the OS killing the process in the middle of `commit()`'s own write syscall).
 */
@RunWith(RobolectricTestRunner::class)
class PendingOutcomeStoreTest {

    private fun app(): Application = ApplicationProvider.getApplicationContext()

    @Test
    fun `persist reports commit success and the record is immediately readable through a separate store instance`() {
        val writer = PendingOutcomeStore(app())
        val persisted = writer.persist(42L, RecordingOutcome.Saved(null, 1_000L), null)

        assertTrue("expected the synchronous commit() to report success", persisted)

        // A separate PendingOutcomeStore instance, standing in for a later app process reading
        // whatever the previous one durably wrote -- not the same object, so this can't be passing
        // by accident via some in-memory-only reference.
        val reader = PendingOutcomeStore(app())
        val consumed = reader.consume()
        assertTrue(consumed is RecordingOutcome.Saved)
        assertEquals(1_000L, (consumed as RecordingOutcome.Saved).startedAtMillis)
    }

    @Test
    fun `clear reports whether its synchronous commit succeeded`() {
        val store = PendingOutcomeStore(app())
        store.persist(1L, RecordingOutcome.FinalizationUnknown(null), null)

        assertTrue("expected the synchronous clear() commit to report success", store.clear())
        assertNull("expected the record to actually be gone", store.consume())
    }

    @Test
    fun `consume is safe against overlapping calls -- exactly one caller receives the pending outcome`() {
        val store = PendingOutcomeStore(app())
        store.persist(7L, RecordingOutcome.Saved(null, 5_000L), null)

        // Two threads racing to consume() the same single record: without the synchronized guard
        // in PendingOutcomeStore, both could read the record before either clears it, delivering
        // the same terminal outcome twice -- exactly the double-delivery this must prevent.
        val results = Collections.synchronizedList(mutableListOf<RecordingOutcome?>())
        val ready = CountDownLatch(2)
        val go = CountDownLatch(1)
        val threads = List(2) {
            Thread {
                ready.countDown()
                go.await(5, TimeUnit.SECONDS)
                results.add(store.consume())
            }
        }
        threads.forEach { it.start() }
        ready.await(5, TimeUnit.SECONDS)
        go.countDown()
        threads.forEach { it.join(5_000) }

        assertEquals("exactly one of the two overlapping calls must have received the record",
            1, results.count { it != null })
        assertEquals("the other must have found it already cleared, not a corrupted/partial read",
            1, results.count { it == null })
    }

    /** A store whose `commitEditor` seam calls the real, Robolectric-backed `editor.commit()` --
     * so its in-memory mutation is genuine -- and then forces the caller-visible result to
     * `false`, regardless of what the real commit actually returned. This validates same-process
     * behavior *after* a false result (e.g. that [PendingOutcomeStore.consume] still returns the
     * real decoded record even when its own clear attempt reports failure); see
     * [ActiveSegmentJournalTest]'s identical `falseReportingCommitJournal()` for why this (not a
     * hand-rolled fake that skips the real `commit()`) is the right way to exercise that.
     *
     * What this does **not** reproduce: an actual failed disk write, stale bytes left on disk, a
     * process restart reading back a partially-applied edit, or a process death mid-retry. */
    private fun falseReportingCommitStore() = PendingOutcomeStore(app(), commitEditor = { editor -> editor.commit(); false })

    @Test
    fun `consume where the outcome is decoded but the durable clear fails still returns the real outcome, and logs honestly`() {
        ShadowLog.stream = null
        ShadowLog.clear()
        val store = falseReportingCommitStore()
        store.persist(99L, RecordingOutcome.FinalizationUnknown(null), null)

        val consumed = store.consume()

        assertTrue("the returned outcome must be the real, correctly-decoded record regardless " +
            "of whether the durable clear afterward succeeded", consumed is RecordingOutcome.FinalizationUnknown)
        val warnings = ShadowLog.getLogsForTag("PendingOutcomeStore").filter { it.type == Log.WARN }
        assertTrue("expected a warning naming the session id when the durable clear fails, got: " +
            "${ShadowLog.getLogs().map { it.msg }}",
            warnings.any { it.msg.contains("99") })
    }

    @Test
    fun `a persistently failing clear reports false honestly -- both attempts fail, never silently claiming success`() {
        // Not a claim that the record actually survives -- SharedPreferencesImpl's real
        // memory-first semantics mean the in-memory map genuinely is cleared even when commit()
        // reports failure (see class doc), so a later same-process read correctly sees it gone
        // either way. What must never happen is clear()/consume() reporting a durable success (a
        // bare `true`) it can't actually back up when the seam reports failure on both attempts.
        val store = falseReportingCommitStore()
        store.persist(7L, RecordingOutcome.Saved(null, 1_000L), null)

        val cleared = store.clear()

        assertFalse("a clear whose commitEditor reports failure on both the initial attempt and " +
            "its single retry must never be reported as true", cleared)
    }

    @Test
    fun `clearLocked's single retry recovers from a transient first-attempt failure`() {
        // Persisted through a plain, normally-succeeding store first -- the counting fake below is
        // only wired up for the clear() call under test, so it isn't also intercepted by persist().
        PendingOutcomeStore(app()).persist(3L, RecordingOutcome.FinalizationUnknown(null), null)

        var attempt = 0
        val store = PendingOutcomeStore(app(), commitEditor = { editor ->
            attempt++
            if (attempt == 1) { editor.commit(); false } else editor.commit()
        })
        val cleared = store.clear()

        assertTrue("a transient first-attempt failure must be recovered by the built-in retry", cleared)
        assertEquals(2, attempt)
        assertNull(PendingOutcomeStore(app()).consume())
    }
}
