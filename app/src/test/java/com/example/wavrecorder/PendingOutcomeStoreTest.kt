package com.example.wavrecorder

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
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
}
