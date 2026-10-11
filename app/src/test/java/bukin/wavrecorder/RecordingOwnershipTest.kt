package bukin.wavrecorder

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Deletion safety at the storage boundary (audit finding F1): a recording being captured,
 * finalized or repaired can't be deleted, whichever path the delete comes from -- for
 * app-storage and document-provider files alike -- while finished recordings stay deletable. And
 * none of this waits on storage (follow-up review R2): a slow delete or file creation holds up
 * neither the Library's lookups nor any other file.
 */
@RunWith(RobolectricTestRunner::class)
class RecordingOwnershipTest {

    @get:Rule
    val isolation = TestIsolation()

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() = RecordingOwnership.resetForTest()

    /** Records which files storage was asked to delete; can be made to stall. */
    private inner class Deletes(private val stall: CountDownLatch? = null) : DestinationManager(context()) {
        val entered = CountDownLatch(1)
        val removed = java.util.Collections.synchronizedList(mutableListOf<Uri>())
        override fun removeFromStorage(uri: Uri): Boolean {
            entered.countDown()
            stall?.await(5, TimeUnit.SECONDS)
            removed += uri
            return if (uri.scheme == "file") File(uri.path!!).delete() else true
        }
    }

    private fun claimNew(file: File) =
        RecordingOwnership.createClaimed(RecordingOwnership.Use.CAPTURING, { it.file.exists() }) { OutputTarget.FileTarget(file) }

    /** Runs [action] on another thread; true if it finished within [timeoutMs]. */
    private fun completesPromptly(timeoutMs: Long = 500, action: () -> Unit): Boolean {
        val done = CountDownLatch(1)
        Thread { action(); done.countDown() }.start()
        return done.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    // ---- Protection -------------------------------------------------------------------------------

    @Test
    fun `a file being captured or finalized is refused, and deletable once released`() {
        val manager = Deletes()
        val file = tempFolder.newFile("live.wav")
        val (target, claim) = claimNew(file)
        val uri = Uri.fromFile(file)

        assertEquals(DestinationManager.DeleteResult.IN_USE, manager.deleteRecording(uri))
        claim.markFinalizing()
        assertEquals(RecordingOwnership.Use.FINALIZING, RecordingOwnership.useOf(uri))
        assertEquals(DestinationManager.DeleteResult.IN_USE, manager.deleteRecording(uri))
        assertTrue("storage was never touched", manager.removed.isEmpty())
        assertTrue(file.exists())

        claim.release()
        assertNull(RecordingOwnership.useOf(uri))
        assertEquals(DestinationManager.DeleteResult.DELETED, manager.deleteRecording(uri))
        assertFalse(file.exists())
        assertEquals(RecordingOwnership.keyOf(target), RecordingOwnership.keyOf(uri))
    }

    @Test
    fun `a document-provider file is protected the same way, by its document URI`() {
        val manager = Deletes()
        val uri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ARec/document/primary%3ARec%2Fr.wav")
        val (_, claim) = RecordingOwnership.createClaimed(RecordingOwnership.Use.CAPTURING, { true }) { OutputTarget.SafTarget(uri, "r.wav") }

        assertEquals(DestinationManager.DeleteResult.IN_USE, manager.deleteRecording(Uri.parse(uri.toString())))
        assertTrue(manager.removed.isEmpty())
        claim.release()
        assertEquals(DestinationManager.DeleteResult.DELETED, manager.deleteRecording(uri))
        assertEquals(listOf(uri), manager.removed)
    }

    @Test
    fun `the same file reached through an equivalent path is recognized`() {
        val file = tempFolder.newFile("same.wav")
        val (_, claim) = claimNew(file)
        assertEquals(RecordingOwnership.Use.CAPTURING, RecordingOwnership.useOf(Uri.fromFile(File(file.path))))
        claim.release()
    }

    @Test
    fun `a file being repaired by recovery can't be deleted until the repair is done`() {
        val file = tempFolder.newFile("recovering.wav")
        val target = OutputTarget.FileTarget(file)
        val journal = ActiveSegmentJournal(context())
        // A record left behind by a previous process, claimed for recovery at startup.
        assertTrue(journal.persistActiveWithOwnerForTest("token", "a-previous-process", target, PcmFormat.pcm16Mono(48000)))
        assertTrue(journal.claimActiveAsRecoveryCandidate() is ActiveSegmentJournal.ClaimResult.Claimed)

        val repairing = CountDownLatch(1)
        val finishRepair = CountDownLatch(1)
        val manager = Deletes()
        val recovery = WavRecoveryManager(openWritable = { _, _ ->
            repairing.countDown()
            finishRepair.await(5, TimeUnit.SECONDS)
            null // then fail to open: the point here is only the claim while it runs
        })
        val worker = Thread { recovery.recoverIfNeeded(context()) }.apply { start() }
        assertTrue(repairing.await(3, TimeUnit.SECONDS))

        assertEquals(DestinationManager.DeleteResult.IN_USE, manager.deleteRecording(Uri.fromFile(file)))
        finishRepair.countDown()
        worker.join(3000)
        assertEquals(DestinationManager.DeleteResult.DELETED, manager.deleteRecording(Uri.fromFile(file)))
    }

    @Test
    fun `a storage failure is reported as failed, not as in use`() {
        val manager = object : DestinationManager(context()) {
            override fun removeFromStorage(uri: Uri): Boolean = false
        }
        assertEquals(DestinationManager.DeleteResult.FAILED, manager.deleteRecording(Uri.fromFile(tempFolder.newFile("x.wav"))))
    }

    // ---- Creation racing a delete: never write into a deleted file --------------------------------

    @Test
    fun `a new file whose delete is under way isn't claimed - another file is created instead`() {
        val stall = CountDownLatch(1)
        val manager = Deletes(stall)
        val first = tempFolder.newFile("new-1.wav")
        val second = tempFolder.newFile("new-2.wav")
        // A delete of the first file (say, a stale Library selection) is in progress...
        val deleting = Thread { manager.deleteRecording(Uri.fromFile(first)) }.apply { start() }
        assertTrue(manager.entered.await(3, TimeUnit.SECONDS))

        // ...when the writer creates its next file under that name: it doesn't claim it.
        var attempt = 0
        val (target, claim) = RecordingOwnership.createClaimed(RecordingOwnership.Use.CAPTURING, { it.file.exists() }) {
            OutputTarget.FileTarget(if (++attempt == 1) first else second)
        }
        assertEquals(second.absolutePath, target.file.absolutePath)
        assertEquals(2, attempt)

        stall.countDown()
        deleting.join(3000)
        claim.release()
    }

    @Test
    fun `a new file deleted before it could be claimed is never written into - another file is created`() {
        val manager = Deletes()
        val first = tempFolder.newFile("gone-1.wav")
        val second = tempFolder.newFile("gone-2.wav")
        var checks = 0
        val (target, claim) = RecordingOwnership.createClaimed(RecordingOwnership.Use.CAPTURING, { created ->
            val exists = created.file.exists()
            // The delete wins the race outright: it runs to completion right after the new file
            // was seen, before it could be claimed.
            if (++checks == 1) assertEquals(DestinationManager.DeleteResult.DELETED, manager.deleteRecording(Uri.fromFile(first)))
            exists
        }) {
            OutputTarget.FileTarget(if (checks == 0) first else second)
        }
        assertNotEquals(first.absolutePath, target.file.absolutePath)
        assertFalse(first.exists())
        assertEquals(RecordingOwnership.Use.CAPTURING, RecordingOwnership.useOf(Uri.fromFile(second)))
        claim.release()
    }

    @Test
    fun `a target that is only created when first opened is claimed as it is`() {
        val notYet = File(tempFolder.root, "created-on-open.wav")
        val (target, claim) = claimNew(notYet)
        assertEquals(notYet.absolutePath, target.file.absolutePath)
        assertEquals(RecordingOwnership.Use.CAPTURING, RecordingOwnership.useOf(Uri.fromFile(notYet)))
        claim.release()
    }

    @Test
    fun `a document deleted before it could even be checked fails the recording with an error - nothing is written`() {
        // The one window the creation check can't close: a delete that removes a new document
        // before its first existence check makes it look like one only created when first opened.
        // Opening it then fails, so the recording ends with an error rather than writing anywhere.
        val gone = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ARec/document/primary%3ARec%2Fgone.wav")
        val errors = java.util.Collections.synchronizedList(mutableListOf<Exception>())
        val opened = CountDownLatch(1)
        val recorder = WavRecorder(startup = ImmediateStartup,
            openAudioSource = { WavRecorder.RecorderConfig(FakeAudioSource(), 48000, 64) },
            wrapChannel = { opened.countDown(); error("a deleted document must never be written to") })
        recorder.start(context(), WavRecorder.NextTarget { OutputTarget.SafTarget(gone, "gone.wav") },
            onSegmentStarted = {}, onAmplitude = {}, onError = { errors += it })

        val deadline = System.currentTimeMillis() + 3000
        while (errors.isEmpty() && System.currentTimeMillis() < deadline) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(2)
        }
        assertEquals("the recording fails, explicitly", 1, errors.size)
        assertEquals("nothing was ever opened for writing", 1L, opened.count)
        assertNull("and the document isn't left claimed", RecordingOwnership.useOf(gone))
        assertNull(recorder.lastSavedTarget)
        recorder.stop()
    }

    // ---- No storage I/O under the lock (R2) ---------------------------------------------------------

    @Test
    fun `a slow delete holds up neither the Library's lookups nor any other file`() {
        val stall = CountDownLatch(1)
        val manager = Deletes(stall)
        val deletingFile = tempFolder.newFile("slow-delete.wav")
        val other = tempFolder.newFile("other.wav")
        val deleting = Thread { manager.deleteRecording(Uri.fromFile(deletingFile)) }.apply { start() }
        assertTrue(manager.entered.await(3, TimeUnit.SECONDS))
        try {
            assertTrue("the adapter's row lookup", completesPromptly { RecordingOwnership.useOf(Uri.fromFile(other)) })
            assertTrue("claiming and releasing another file", completesPromptly {
                RecordingOwnership.tryClaim(OutputTarget.FileTarget(other), RecordingOwnership.Use.RECOVERING)!!.release()
            })
            assertTrue("creating and claiming a new file", completesPromptly {
                claimNew(tempFolder.newFile("fresh.wav")).second.release()
            })
            assertTrue("deleting another file", completesPromptly {
                Deletes().deleteRecording(Uri.fromFile(tempFolder.newFile("unrelated.wav")))
            })
            assertTrue("the one being deleted can't be claimed meanwhile",
                RecordingOwnership.tryClaim(OutputTarget.FileTarget(deletingFile), RecordingOwnership.Use.CAPTURING) == null)
        } finally {
            stall.countDown()
            deleting.join(3000)
        }
    }

    @Test
    fun `a slow file creation holds up neither lookups nor deletes`() {
        val creating = CountDownLatch(1)
        val finishCreating = CountDownLatch(1)
        val newFile = tempFolder.newFile("slow-create.wav")
        val creator = Thread {
            RecordingOwnership.createClaimed(RecordingOwnership.Use.CAPTURING, { it.file.exists() }) {
                creating.countDown()
                finishCreating.await(5, TimeUnit.SECONDS) // a slow provider creating the next part
                OutputTarget.FileTarget(newFile)
            }.second.release()
        }.apply { start() }
        assertTrue(creating.await(3, TimeUnit.SECONDS))
        try {
            assertTrue(completesPromptly { RecordingOwnership.useOf(Uri.fromFile(newFile)) })
            assertTrue(completesPromptly { Deletes().deleteRecording(Uri.fromFile(tempFolder.newFile("finished.wav"))) })
        } finally {
            finishCreating.countDown()
            creator.join(3000)
        }
    }
}
