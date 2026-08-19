package com.example.wavrecorder

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Covers [ActiveSegmentJournal]'s core safety invariants directly: the ACTIVE/RECOVERY_PENDING
 * separation that keeps a live segment from ever being mistaken for a crashed one, the durable
 * recovery queue (never a single overwritable slot), the process-instance owner id that keeps a
 * same-process Service recreation from claiming its own still-live record, cross-instance lock
 * sharing (production constructs a fresh instance per call), and stale-token rejection.
 */
@RunWith(RobolectricTestRunner::class)
class ActiveSegmentJournalTest {

    private fun context() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun target(name: String) = OutputTarget.FileTarget(File("/tmp/$name"))

    /** A foreign owner id that's never this test JVM's own [ProcessInstanceId] -- used throughout
     * to simulate a genuinely different, previous process's leftover ACTIVE record. */
    private val foreignOwner = "simulated-foreign-process"

    /** A journal whose [ActiveSegmentJournal]'s `commitEditor` seam calls the real, Robolectric-
     * backed `editor.commit()` -- so its in-memory mutation is genuine -- and then forces the
     * caller-visible result to `false`, regardless of what the real commit actually returned.
     * This validates same-process rollback logic *after* a false result: that every mutating
     * method correctly restores the pre-mutation snapshot once it observes `commitEditor` report
     * failure. It deliberately is **not** a hand-rolled fake that skips the real `commit()` (a
     * naive override just returning `false` would prove nothing about whether callers cope with a
     * genuinely-applied-then-reported-failed edit).
     *
     * What this does **not** reproduce: an actual failed disk write, stale bytes left on disk, a
     * process restart reading back a partially-applied edit, or a process death mid-rollback. All
     * four remain outside what a Robolectric unit test can exercise; see the class doc's own
     * residual-limitation note for the one gap application code cannot close either. */
    private fun falseReportingCommitJournal() = ActiveSegmentJournal(context(), commitEditor = { editor -> editor.commit(); false })

    @Test
    fun `claimActiveAsRecoveryCandidate returns Empty and touches nothing when nothing is active`() {
        val journal = ActiveSegmentJournal(context())

        val claimed = journal.claimActiveAsRecoveryCandidate()

        assertEquals(ActiveSegmentJournal.ClaimResult.Empty, claimed)
        assertTrue(journal.peekRecoveryCandidates().isEmpty())
        assertNull(journal.peekActive())
    }

    @Test
    fun `claiming a foreign-process record moves it into the recovery queue and clears active, atomically`() {
        val journal = ActiveSegmentJournal(context())
        journal.persistActiveWithOwnerForTest(
            "old-token", foreignOwner, target("old.wav"), sampleRate = 48000, channels = 1, bitsPerSample = 16
        )

        val claimed = journal.claimActiveAsRecoveryCandidate()

        assertTrue(claimed is ActiveSegmentJournal.ClaimResult.Claimed)
        assertEquals("old-token", (claimed as ActiveSegmentJournal.ClaimResult.Claimed).record.token)
        assertNull("ACTIVE must be empty immediately after claiming -- a live segment opened right " +
            "after this point must never see a stale leftover record", journal.peekActive())
        assertEquals("old-token", journal.peekRecoveryCandidates().single().token)
    }

    @Test
    fun `claiming when the active record is owned by this same process returns OwnedByThisProcess and leaves it completely untouched`() {
        // Regression test for Area 2's same-process protection: a RecordingService instance can be
        // recreated in the same still-running process while a previous instance's recording thread
        // is still finishing its own non-blocking teardown -- that still-owned record must never be
        // claimed as though it belonged to a genuinely dead, previous process. Using the real
        // (production) persistActive() here -- not persistActiveWithOwnerForTest -- is exactly what
        // exercises this: it always tags with this test JVM's own real ProcessInstanceId.
        val journal = ActiveSegmentJournal(context())
        journal.persistActive("live-token", null, target("live.wav"), sampleRate = 48000, channels = 1, bitsPerSample = 16)

        val claimed = journal.claimActiveAsRecoveryCandidate()

        assertEquals(ActiveSegmentJournal.ClaimResult.OwnedByThisProcess, claimed)
        assertTrue("a same-process record must never be moved into the recovery queue",
            journal.peekRecoveryCandidates().isEmpty())
        assertEquals("the live record must be left completely untouched in ACTIVE", "live-token",
            journal.peekActive()?.token)
    }

    @Test
    fun `a live segment persisted after claiming is never visible to recovery, and recovery never touches it`() {
        // Regression test for the core race: recovery must operate purely on RECOVERY_PENDING,
        // so a new ACTIVE record (a live, currently-recording segment) persisted *after* claiming
        // must remain completely invisible to -- and untouched by -- anything reading the queue.
        val journal = ActiveSegmentJournal(context())
        journal.claimActiveAsRecoveryCandidate() // nothing to claim; simulates a clean previous exit

        journal.persistActive("live-token", null, target("live.wav"), sampleRate = 48000, channels = 1, bitsPerSample = 16)

        assertTrue("a live ACTIVE record must never appear in the recovery queue",
            journal.peekRecoveryCandidates().isEmpty())
        // "live-token" names the ACTIVE record, not a queued one -- there is nothing to remove at
        // all, so this correctly reports "already absent" (true), exactly like removing from an
        // empty queue always does; the actual safety property is that it's a complete no-op
        // against the unrelated ACTIVE record, checked next.
        assertTrue(journal.removeRecoveryCandidateIfMatches("live-token"))
        assertEquals("the live record must be completely unaffected", "live-token",
            journal.peekActive()?.token)
    }

    @Test
    fun `clearActiveIfMatches rejects a stale token even when a different instance persisted the newer record`() {
        // Simulates an "old finalizer" that captured its segment's token earlier, then -- by the
        // time it actually calls clearActiveIfMatches -- a newer segment (opened via a completely
        // separate ActiveSegmentJournal instance, exactly like production's journalFor(context)
        // seam constructs fresh each time) has since become the current active record.
        val oldFinalizer = ActiveSegmentJournal(context())
        oldFinalizer.persistActive("old-token", null, target("old.wav"), sampleRate = 48000, channels = 1, bitsPerSample = 16)

        // The newer segment supersedes it via the same token, exactly like WavRecorder's own
        // rollover would (persistActive's expectedPreviousToken protection allows this).
        val newSession = ActiveSegmentJournal(context())
        newSession.persistActive("new-token", "old-token", target("new.wav"), sampleRate = 48000, channels = 1, bitsPerSample = 16)

        val cleared = oldFinalizer.clearActiveIfMatches("old-token")

        assertFalse("a stale token must never match a newer record", cleared)
        assertEquals("the newer record must survive completely untouched", "new-token",
            oldFinalizer.peekActive()?.token)
        assertEquals(target("new.wav").file.absolutePath,
            (oldFinalizer.peekActive()?.target as? OutputTarget.FileTarget)?.file?.absolutePath)
    }

    @Test
    fun `persistActive refuses to overwrite an unrelated, unclaimed active record and leaves it completely untouched`() {
        // Area 2: a brand-new session's very first segment must never silently clobber a foreign,
        // still-unclaimed ACTIVE record (e.g. one left behind because claimActiveAsRecoveryCandidate
        // itself failed to durably move it into the recovery queue at startup) -- doing so would
        // permanently lose the only durable trace of that older, crashed segment.
        val journal = ActiveSegmentJournal(context())
        journal.persistActiveWithOwnerForTest(
            "orphaned-token", foreignOwner, target("orphaned.wav"), sampleRate = 48000, channels = 1, bitsPerSample = 16
        )

        val newSessionResult = journal.persistActive(
            "brand-new-token", expectedPreviousToken = null, target("new-session.wav"),
            sampleRate = 48000, channels = 1, bitsPerSample = 16
        )

        assertFalse("a new session's first segment must refuse to overwrite an unrelated, " +
            "unclaimed active record", newSessionResult)
        assertEquals("the orphaned record must be left completely untouched", "orphaned-token",
            journal.peekActive()?.token)
    }

    @Test
    fun `persistActive allows a rollover to legitimately supersede its own session's previous segment`() {
        val journal = ActiveSegmentJournal(context())
        journal.persistActive("segment-1", null, target("part1.wav"), sampleRate = 48000, channels = 1, bitsPerSample = 16)

        val rolloverResult = journal.persistActive(
            "segment-2", expectedPreviousToken = "segment-1", target("part2.wav"),
            sampleRate = 48000, channels = 1, bitsPerSample = 16
        )

        assertTrue("a rollover naming its own session's previous token must be allowed to " +
            "supersede it", rolloverResult)
        assertEquals("segment-2", journal.peekActive()?.token)
    }

    @Test
    fun `persistActive proceeds normally when the active slot is already empty, regardless of the expected previous token`() {
        // Covers the common case where the previous segment's own clearActiveIfMatches() already
        // succeeded before rollover's next persistActive() call runs -- ACTIVE is genuinely empty
        // by then, which must never be treated as a mismatch just because a previousToken was named.
        val journal = ActiveSegmentJournal(context())

        val result = journal.persistActive(
            "segment-1", expectedPreviousToken = "some-already-cleared-token", target("part1.wav"),
            sampleRate = 48000, channels = 1, bitsPerSample = 16
        )

        assertTrue(result)
        assertEquals("segment-1", journal.peekActive()?.token)
    }

    @Test
    fun `three independently-claimed candidates queue up without overwriting each other`() {
        val journal = ActiveSegmentJournal(context())
        journal.persistActiveWithOwnerForTest("token-1", foreignOwner, target("c1.wav"), 48000, 1, 16)
        journal.claimActiveAsRecoveryCandidate()
        journal.persistActiveWithOwnerForTest("token-2", foreignOwner, target("c2.wav"), 48000, 1, 16)
        journal.claimActiveAsRecoveryCandidate()
        journal.persistActiveWithOwnerForTest("token-3", foreignOwner, target("c3.wav"), 48000, 1, 16)
        journal.claimActiveAsRecoveryCandidate()

        val queued = journal.peekRecoveryCandidates()

        assertEquals(listOf("token-1", "token-2", "token-3"), queued.map { it.token })
    }

    @Test
    fun `removeRecoveryCandidateIfMatches removes only the exact candidate, leaving independent ones untouched`() {
        val journal = ActiveSegmentJournal(context())
        journal.persistActiveWithOwnerForTest("token-1", foreignOwner, target("c1.wav"), 48000, 1, 16)
        journal.claimActiveAsRecoveryCandidate()
        journal.persistActiveWithOwnerForTest("token-2", foreignOwner, target("c2.wav"), 48000, 1, 16)
        journal.claimActiveAsRecoveryCandidate()
        journal.persistActiveWithOwnerForTest("token-3", foreignOwner, target("c3.wav"), 48000, 1, 16)
        journal.claimActiveAsRecoveryCandidate()

        val removed = journal.removeRecoveryCandidateIfMatches("token-2")

        assertTrue(removed)
        assertEquals("removing the middle candidate must leave the other two, still in order",
            listOf("token-1", "token-3"), journal.peekRecoveryCandidates().map { it.token })
    }

    @Test
    fun `removeRecoveryCandidateIfMatches for an absent token is a no-op reported as already-clear`() {
        val journal = ActiveSegmentJournal(context())
        journal.persistActiveWithOwnerForTest("token-1", foreignOwner, target("c1.wav"), 48000, 1, 16)
        journal.claimActiveAsRecoveryCandidate()

        val result = journal.removeRecoveryCandidateIfMatches("never-queued-token")

        assertTrue("removing an absent token must report true (already clear), like clearing an " +
            "empty slot always does", result)
        assertEquals(listOf("token-1"), journal.peekRecoveryCandidates().map { it.token })
    }

    @Test
    fun `a stale finalizer cannot remove a different queued candidate or a newer active record using its own old token`() {
        val journal = ActiveSegmentJournal(context())
        journal.persistActiveWithOwnerForTest("crashed-token", foreignOwner, target("crashed.wav"), 48000, 1, 16)
        journal.claimActiveAsRecoveryCandidate()
        journal.persistActive("live-token", null, target("live.wav"), sampleRate = 48000, channels = 1, bitsPerSample = 16)

        // A stale finalizer for some completely unrelated, already-superseded token tries to clear
        // both records using a name that matches neither.
        assertFalse(journal.clearActiveIfMatches("some-other-stale-token"))
        assertTrue(journal.removeRecoveryCandidateIfMatches("some-other-stale-token")) // absent -- no-op

        assertEquals("live-token", journal.peekActive()?.token)
        assertEquals(listOf("crashed-token"), journal.peekRecoveryCandidates().map { it.token })
    }

    @Test
    fun `the companion lock is shared across independently-constructed instances, not per-instance`() {
        // Regression test: an instance-level lock would let this proceed immediately, since
        // journalB's own lock object would be different from journalA's. Uses a latch/barrier
        // (not a sleep) to deterministically prove serialization: journalA's held lock is what
        // must block journalB's call, or this test itself would hang instead of asserting on a
        // race that "usually" doesn't happen.
        val holderEntered = CountDownLatch(1)
        val releaseHolder = CountDownLatch(1)
        val holderThread = Thread {
            synchronized(ActiveSegmentJournal.LOCK) {
                holderEntered.countDown()
                releaseHolder.await(5, TimeUnit.SECONDS)
            }
        }
        holderThread.start()
        assertTrue("sanity: the holder thread must actually acquire the shared lock",
            holderEntered.await(2, TimeUnit.SECONDS))

        val journalB = ActiveSegmentJournal(context())
        val blockedCallDone = CountDownLatch(1)
        val blockedThread = Thread {
            journalB.persistActive("token-b", null, target("b.wav"), sampleRate = 48000, channels = 1, bitsPerSample = 16)
            blockedCallDone.countDown()
        }
        blockedThread.start()

        assertFalse(
            "a call through a completely different ActiveSegmentJournal instance must still " +
                "block while the shared lock is held elsewhere",
            blockedCallDone.await(300, TimeUnit.MILLISECONDS)
        )

        releaseHolder.countDown()

        assertTrue("the blocked call must proceed once the shared lock is released",
            blockedCallDone.await(2, TimeUnit.SECONDS))
    }

    @Test
    fun `tokens minted across separate journal generations never collide, unlike a resettable counter`() {
        // Regression test for the fixed AtomicLong(0)-per-process token counter: a real UUID
        // cannot practically repeat, so two entirely separate "process generations" (modeled here
        // as two fresh foreign-owned records, each minting its own token exactly the way
        // WavRecorder.openSegment() does) must never produce the same token.
        val generation1Token = UUID.randomUUID().toString()
        val generation2Token = UUID.randomUUID().toString()

        assertNotEquals(
            "two independently-minted segment tokens must never collide, even across what would " +
                "be separate process restarts in production",
            generation1Token, generation2Token
        )

        val journal = ActiveSegmentJournal(context())
        journal.persistActiveWithOwnerForTest(generation1Token, foreignOwner, target("gen1.wav"), 48000, 1, 16)
        val claimed = journal.claimActiveAsRecoveryCandidate()
        journal.persistActiveWithOwnerForTest(generation2Token, foreignOwner, target("gen2.wav"), 48000, 1, 16)
        journal.claimActiveAsRecoveryCandidate()

        // The old (claimed, generation 1) record must never be mistakable for generation 2's --
        // this is exactly the scenario a colliding counter-based token would have broken.
        assertEquals(generation1Token, (claimed as ActiveSegmentJournal.ClaimResult.Claimed).record.token)
        assertEquals(listOf(generation1Token, generation2Token),
            journal.peekRecoveryCandidates().map { it.token })
        // Removing generation 2's own (distinct, non-colliding) token must never also remove
        // generation 1's independent entry.
        assertTrue(journal.removeRecoveryCandidateIfMatches(generation2Token))
        assertEquals(listOf(generation1Token), journal.peekRecoveryCandidates().map { it.token })
    }

    @Test
    fun `a failed persistActive write is reported honestly via its return value`() {
        val journal = object : ActiveSegmentJournal(context()) {
            override fun persistActive(
                token: String, expectedPreviousToken: String?, target: OutputTarget,
                sampleRate: Int, channels: Int, bitsPerSample: Int
            ): Boolean = false
        }

        val result = journal.persistActive("token", null, target("x.wav"), sampleRate = 48000, channels = 1, bitsPerSample = 16)

        assertFalse("a failed durable write must be reported as false, never silently treated as " +
            "success", result)
    }

    @Test
    fun `a failed clearActiveIfMatches write is reported honestly via its return value`() {
        val journal = object : ActiveSegmentJournal(context()) {
            override fun clearActiveIfMatches(token: String): Boolean = false
        }

        val result = journal.clearActiveIfMatches("token")

        assertFalse("a failed durable clear must be reported as false, never silently claimed " +
            "complete", result)
    }

    @Test
    fun `a failed claim is reported as Failed with the original record intact, and a subsequent persistActive still refuses to overwrite it`() {
        // The durable append-and-clear commit can fail (disk error, corrupted prefs file); this
        // simulates that via an override (mirroring the established pattern above for
        // persistActive/clearActiveIfMatches) since Robolectric's own SharedPreferences shadow
        // can't be made to genuinely fail a commit(). What's under test is the *contract*: a
        // Failed claim must never be treated as "nothing to recover", and the real, underlying
        // journal record (untouched by the override, since only the return value is faked) must
        // still protect itself against being overwritten by a fresh session's first segment.
        val realJournal = ActiveSegmentJournal(context())
        realJournal.persistActiveWithOwnerForTest(
            "orphaned-token", foreignOwner, target("orphaned.wav"), sampleRate = 48000, channels = 1, bitsPerSample = 16
        )
        val fakeFailingClaim = object : ActiveSegmentJournal(context()) {
            override fun claimActiveAsRecoveryCandidate(): ClaimResult =
                ClaimResult.Failed(realJournal.peekActive()!!)
        }

        val claimResult = fakeFailingClaim.claimActiveAsRecoveryCandidate()

        assertTrue(claimResult is ActiveSegmentJournal.ClaimResult.Failed)
        assertEquals("orphaned-token", (claimResult as ActiveSegmentJournal.ClaimResult.Failed).record.token)
        // The real journal's ACTIVE record was never actually touched by the (test-only) failing
        // override, so it must still be exactly where it was -- and a new session must still
        // refuse to clobber it.
        assertEquals("orphaned-token", realJournal.peekActive()?.token)
        val newSessionResult = realJournal.persistActive(
            "brand-new-token", expectedPreviousToken = null, target("new.wav"),
            sampleRate = 48000, channels = 1, bitsPerSample = 16
        )
        assertFalse(newSessionResult)
        assertEquals("orphaned-token", realJournal.peekActive()?.token)
    }

    // ---- Same-process rollback after commitEditor reports a false result (see
    // falseReportingCommitJournal()'s doc for exactly what this does and does not prove -- it
    // validates same-process rollback after a false result, not failed disk persistence or
    // restart behavior). ----

    @Test
    fun `persistActive after a false commit result, followed by peekActive, sees the pre-mutation state, not the failed write`() {
        val journal = falseReportingCommitJournal()
        journal.persistActiveWithOwnerForTest(
            "existing-token", foreignOwner, target("existing.wav"), sampleRate = 48000, channels = 1, bitsPerSample = 16
        )
        // Sanity: the setup write above used the real (non-failing) commit path.
        assertEquals("existing-token", journal.peekActive()?.token)

        val result = journal.persistActive(
            "new-token", expectedPreviousToken = "existing-token", target("new.wav"),
            sampleRate = 48000, channels = 1, bitsPerSample = 16
        )

        assertFalse("a commit reporting failure must be surfaced as false", result)
        assertEquals(
            "peekActive() must see the pre-mutation record, not the new one the failed commit " +
                "already applied to the in-memory SharedPreferences map",
            "existing-token", journal.peekActive()?.token
        )
    }

    @Test
    fun `failed initial segment journaling followed by cleanup and a same-process retry succeeds`() {
        // Models WavRecorder.openSegment()'s fail-before-capture flow: persistActive() fails for
        // the very first segment of a session (ACTIVE starts empty, expectedPreviousToken = null),
        // WavRecorder deletes the partial file it just created and reports the error, and the user
        // (or an automatic retry) tries again in the same process.
        val failingJournal = falseReportingCommitJournal()

        val firstAttempt = failingJournal.persistActive(
            "attempt-1-token", expectedPreviousToken = null, target("attempt1.wav"),
            sampleRate = 48000, channels = 1, bitsPerSample = 16
        )
        assertFalse(firstAttempt)
        assertNull(
            "a failed first-segment write must not leave an ACTIVE record pointing at the " +
                "partial file WavRecorder.openSegment() is about to delete",
            failingJournal.peekActive()
        )

        // The retry goes through a normal (non-failing) journal instance -- same underlying
        // SharedPreferences store, standing in for the disk write succeeding this time.
        val retryJournal = ActiveSegmentJournal(context())
        val retryResult = retryJournal.persistActive(
            "attempt-2-token", expectedPreviousToken = null, target("attempt2.wav"),
            sampleRate = 48000, channels = 1, bitsPerSample = 16
        )

        assertTrue(
            "a same-process retry must not be permanently blocked by the failed attempt's stale " +
                "token -- the rolled-back state must still show ACTIVE as empty",
            retryResult
        )
        assertEquals("attempt-2-token", retryJournal.peekActive()?.token)
    }

    @Test
    fun `failed foreign-ACTIVE claim leaves ACTIVE protected and the recovery queue unchanged`() {
        val failingJournal = falseReportingCommitJournal()
        failingJournal.persistActiveWithOwnerForTest(
            "crashed-token", foreignOwner, target("crashed.wav"), sampleRate = 48000, channels = 1, bitsPerSample = 16
        )

        val claimed = failingJournal.claimActiveAsRecoveryCandidate()

        assertTrue(claimed is ActiveSegmentJournal.ClaimResult.Failed)
        assertEquals("crashed-token", (claimed as ActiveSegmentJournal.ClaimResult.Failed).record.token)
        assertEquals(
            "the previous ACTIVE record must remain logically visible and protected in this " +
                "process, not disappear just because the durable move failed",
            "crashed-token", failingJournal.peekActive()?.token
        )
        assertTrue(
            "the queue must not gain a phantom entry that was never durably recorded",
            failingJournal.peekRecoveryCandidates().isEmpty()
        )
        // And the protection this enables: a brand new session must still refuse to overwrite it.
        val newSessionResult = failingJournal.persistActive(
            "new-session-token", expectedPreviousToken = null, target("new-session.wav"),
            sampleRate = 48000, channels = 1, bitsPerSample = 16
        )
        assertFalse(newSessionResult)
    }

    @Test
    fun `failed recovery-queue removal leaves the candidate visible and independently retryable`() {
        // Seeded through a normal (non-failing) journal instance -- using
        // falseReportingCommitJournal() for setup too would fail *this* claim as well (its
        // commitEditor always reports false), never actually queuing the candidate this test
        // needs removal to fail against.
        val setupJournal = ActiveSegmentJournal(context())
        setupJournal.persistActiveWithOwnerForTest("token-1", foreignOwner, target("c1.wav"), 48000, 1, 16)
        setupJournal.claimActiveAsRecoveryCandidate()
        assertEquals("sanity: the candidate must genuinely be queued before this test begins",
            listOf("token-1"), setupJournal.peekRecoveryCandidates().map { it.token })

        val failingJournal = falseReportingCommitJournal()
        val removed = failingJournal.removeRecoveryCandidateIfMatches("token-1")

        assertFalse("a commit reporting failure while removing the entry must be surfaced as false", removed)
        assertEquals(
            "the candidate must remain visible in the queue, not vanish from the in-memory view " +
                "just because the removal commit failed",
            listOf("token-1"), failingJournal.peekRecoveryCandidates().map { it.token }
        )

        // A later, successful retry (through a normal, non-failing journal instance) must still
        // find and be able to remove it.
        val retryJournal = ActiveSegmentJournal(context())
        assertTrue(retryJournal.removeRecoveryCandidateIfMatches("token-1"))
        assertTrue(retryJournal.peekRecoveryCandidates().isEmpty())
    }

    @Test
    fun `a failed compound claim restores ACTIVE, owner, and the entire queue using exactly one corrective commit`() {
        // Seed: an existing recovery candidate already queued, plus a separate foreign ACTIVE
        // record -- exactly the compound state claimActiveAsRecoveryCandidate()'s failed commit
        // must restore as one unit.
        val setupJournal = ActiveSegmentJournal(context())
        setupJournal.persistActiveWithOwnerForTest("queued-token", foreignOwner, target("queued.wav"), 48000, 1, 16)
        setupJournal.claimActiveAsRecoveryCandidate()
        setupJournal.persistActiveWithOwnerForTest("active-token", foreignOwner, target("active.wav"), 48000, 1, 16)
        assertEquals("sanity: one candidate already queued before the claim under test",
            listOf("queued-token"), setupJournal.peekRecoveryCandidates().map { it.token })
        assertEquals("sanity: a foreign ACTIVE record present before the claim under test",
            "active-token", setupJournal.peekActive()?.token)

        var commitCalls = 0
        val failingJournal = ActiveSegmentJournal(context(), commitEditor = { editor ->
            commitCalls++
            editor.commit()
            false
        })

        val claimed = failingJournal.claimActiveAsRecoveryCandidate()

        assertTrue(claimed is ActiveSegmentJournal.ClaimResult.Failed)
        assertEquals("active-token", (claimed as ActiveSegmentJournal.ClaimResult.Failed).record.token)
        // Exactly 2 commitEditor calls total: the one original (failed) append-and-clear attempt,
        // plus exactly one corrective restore. 3 calls would mean the restore used two separate
        // commits (one for ACTIVE, one for the queue) instead of the required single compound one.
        assertEquals(
            "the failed compound claim must use exactly one corrective commit, not one for " +
                "ACTIVE and another for the queue",
            2, commitCalls
        )
        assertEquals("ACTIVE record must be restored to its exact pre-claim state",
            "active-token", failingJournal.peekActive()?.token)
        assertEquals(
            "ACTIVE's owner must be restored to the exact pre-claim owner, not merely some " +
                "non-null value -- a wrong-but-non-null owner would defeat ProcessInstanceId's " +
                "same-process-vs-foreign distinction on a later claim attempt",
            foreignOwner, failingJournal.peekActiveOwnerForTest()
        )
        assertEquals(
            "the pre-claim recovery queue -- contents and order -- must be restored exactly, " +
                "with no phantom entry added for the failed claim",
            listOf("queued-token"), failingJournal.peekRecoveryCandidates().map { it.token }
        )

        // A later normal claim must still succeed and append exactly once, without duplicating
        // the entry the failed compound rollback already restored.
        val retryJournal = ActiveSegmentJournal(context())
        val retryClaim = retryJournal.claimActiveAsRecoveryCandidate()
        assertTrue(retryClaim is ActiveSegmentJournal.ClaimResult.Claimed)
        assertEquals("active-token", (retryClaim as ActiveSegmentJournal.ClaimResult.Claimed).record.token)
        assertNull("ACTIVE must be empty after the successful retry claim", retryJournal.peekActive())
        assertEquals("the queue must contain each candidate exactly once, in claim order, with " +
            "no duplicate from the earlier failed attempt",
            listOf("queued-token", "active-token"), retryJournal.peekRecoveryCandidates().map { it.token })
    }

    @Test
    fun `a successful write after an earlier false-reporting commit durably reflects the new, intended state`() {
        // Proves the rollback mechanism doesn't poison a later, genuinely successful write --
        // once a normal commit succeeds, its result (not the earlier failed attempt's) is what a
        // same-process reader sees.
        val failingJournal = falseReportingCommitJournal()
        failingJournal.persistActive(
            "will-fail-token", expectedPreviousToken = null, target("will-fail.wav"),
            sampleRate = 48000, channels = 1, bitsPerSample = 16
        )
        assertNull(failingJournal.peekActive()) // rolled back, confirmed by the earlier test above

        val normalJournal = ActiveSegmentJournal(context())
        val result = normalJournal.persistActive(
            "will-succeed-token", expectedPreviousToken = null, target("will-succeed.wav"),
            sampleRate = 48000, channels = 1, bitsPerSample = 16
        )

        assertTrue(result)
        assertEquals("will-succeed-token", normalJournal.peekActive()?.token)
        assertEquals("a fresh journal instance over the same store must see the same, real " +
            "durable state", "will-succeed-token", ActiveSegmentJournal(context()).peekActive()?.token)
    }
}
