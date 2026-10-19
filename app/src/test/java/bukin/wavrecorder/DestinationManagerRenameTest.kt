package bukin.wavrecorder

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Renaming a recording ([DestinationManager.renameRecording]): it never replaces another file,
 * never renames a file that is being written, deleted or may still need repairing after an
 * interrupted recording, and nothing can start writing to a file while it is being renamed.
 */
@RunWith(RobolectricTestRunner::class)
class DestinationManagerRenameTest {

    @get:Rule
    val isolation = TestIsolation()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = DestinationManager(context)

    @After
    fun tearDown() = RecordingOwnership.resetForTest()

    private fun recording(name: String, bytes: ByteArray = ByteArray(64) { it.toByte() }): File {
        val target = manager.createOutputFile(name) as OutputTarget.FileTarget
        target.file.writeBytes(bytes)
        return target.file
    }

    @Test
    fun `renames a recording in app storage, keeping its audio and its date`() {
        val audio = ByteArray(200) { (it * 3).toByte() }
        val file = recording("recording_20261019_115500_part01.wav", audio)
        file.setLastModified(1_760_000_000_000)

        assertEquals(DestinationManager.RenameResult.RENAMED, manager.renameRecording(Uri.fromFile(file), "Band practice.wav"))

        val renamed = File(file.parentFile, "Band practice.wav")
        assertFalse(file.exists())
        assertArrayEquals(audio, renamed.readBytes())
        val listed = manager.listRecordings().single()
        assertEquals("Band practice.wav", listed.name)
        assertEquals(Uri.fromFile(renamed), listed.uri)
        assertEquals("the list dates it by its file now", 1_760_000_000_000, listed.lastModifiedMillis)
    }

    @Test
    fun `never replaces another recording, whatever the case of its name`() {
        val mine = recording("mine.wav", byteArrayOf(1, 2, 3))
        val other = recording("Taken.wav", byteArrayOf(9, 9))

        for (name in listOf("Taken.wav", "taken.wav", "TAKEN.wav")) {
            assertEquals(name, DestinationManager.RenameResult.NAME_TAKEN, manager.renameRecording(Uri.fromFile(mine), name))
        }
        assertArrayEquals(byteArrayOf(1, 2, 3), mine.readBytes())
        assertArrayEquals(byteArrayOf(9, 9), other.readBytes())
    }

    @Test
    fun `a change only in the case of the name is allowed`() {
        val file = recording("talk.wav", byteArrayOf(4, 5))

        assertEquals(DestinationManager.RenameResult.RENAMED, manager.renameRecording(Uri.fromFile(file), "Talk.wav"))

        assertEquals(listOf("Talk.wav"), manager.listRecordings().map { it.name })
        assertArrayEquals(byteArrayOf(4, 5), File(file.parentFile, "Talk.wav").readBytes())
    }

    @Test
    fun `a recording being written is refused until it is finished`() {
        val file = recording("live.wav")
        val claim = RecordingOwnership.tryClaim(OutputTarget.FileTarget(file), RecordingOwnership.Use.CAPTURING)!!

        assertEquals(DestinationManager.RenameResult.IN_USE, manager.renameRecording(Uri.fromFile(file), "Renamed.wav"))
        assertTrue(file.exists())

        claim.release()
        assertEquals(DestinationManager.RenameResult.RENAMED, manager.renameRecording(Uri.fromFile(file), "Renamed.wav"))
    }

    @Test
    fun `a recording the crash-recovery journal still names keeps its name, active or queued for repair`() {
        val journal = ActiveSegmentJournal(context)
        val queued = recording("queued.wav")
        val active = recording("active.wav")
        val unrelated = recording("unrelated.wav")
        // A previous process's segment, queued for repair...
        assertTrue(journal.persistActiveWithOwnerForTest("old", "another-process", OutputTarget.FileTarget(queued), 48000, 1, 16))
        assertTrue(journal.claimActiveAsRecoveryCandidate() is ActiveSegmentJournal.ClaimResult.Claimed)
        // ...and the segment being written now, whose claim has just been released.
        assertTrue(journal.persistActive("new", null, OutputTarget.FileTarget(active), 48000, 1, 16))

        assertEquals(DestinationManager.RenameResult.IN_USE, manager.renameRecording(Uri.fromFile(queued), "Q.wav"))
        assertEquals(DestinationManager.RenameResult.IN_USE, manager.renameRecording(Uri.fromFile(active), "A.wav"))
        assertTrue(queued.exists() && active.exists())
        assertEquals(DestinationManager.RenameResult.RENAMED, manager.renameRecording(Uri.fromFile(unrelated), "U.wav"))
    }

    @Test
    fun `nothing can claim the file, or the name it gets, while it is being renamed`() {
        val file = recording("before.wav")
        val after = File(file.parentFile, "After.wav")
        var claimOld: RecordingOwnership.Claim? = null
        var claimNew: RecordingOwnership.Claim? = null
        val probing = object : DestinationManager(context) {
            override fun renameInStorage(uri: Uri, newFileName: String): RenameResult {
                claimOld = RecordingOwnership.tryClaim(OutputTarget.FileTarget(file), RecordingOwnership.Use.CAPTURING)
                claimNew = RecordingOwnership.tryClaim(OutputTarget.FileTarget(after), RecordingOwnership.Use.CAPTURING)
                return super.renameInStorage(uri, newFileName)
            }
        }

        assertEquals(DestinationManager.RenameResult.RENAMED, probing.renameRecording(Uri.fromFile(file), "After.wav"))

        assertNull("the file being renamed can't be claimed", claimOld)
        assertNull("the name it is renamed to can't be claimed", claimNew)
        val afterwards = RecordingOwnership.tryClaim(OutputTarget.FileTarget(after), RecordingOwnership.Use.CAPTURING)
        assertNotNull("once renamed, the file is free again", afterwards)
        afterwards!!.release()
    }

    @Test
    fun `a recording being deleted can't be renamed`() {
        val file = recording("going.wav")
        var result: DestinationManager.RenameResult? = null

        RecordingOwnership.deleteUnlessInUse(Uri.fromFile(file)) {
            result = manager.renameRecording(Uri.fromFile(file), "Kept.wav")
        }

        assertEquals(DestinationManager.RenameResult.IN_USE, result)
        assertFalse(File(file.parentFile, "Kept.wav").exists())
    }

    @Test
    fun `only a plain recording name is accepted at the storage boundary`() {
        val file = recording("plain.wav", byteArrayOf(7))
        val dir = file.parentFile!!
        val before = dir.list()!!.sorted()

        for (name in listOf("../escape.wav", "sub/dir.wav", "no-extension", ".wav", "recording_20261019_120000_part01.wav", "")) {
            assertEquals("'$name'", DestinationManager.RenameResult.FAILED, manager.renameRecording(Uri.fromFile(file), name))
        }

        assertEquals(before, dir.list()!!.sorted())
        assertFalse(File(dir.parentFile, "escape.wav").exists())
        assertArrayEquals(byteArrayOf(7), file.readBytes())
    }

    @Test
    fun `a recording that is gone is reported as a failure, not thrown`() {
        val file = recording("gone.wav")
        assertTrue(file.delete())

        assertEquals(DestinationManager.RenameResult.FAILED, manager.renameRecording(Uri.fromFile(file), "Back.wav"))
        assertFalse(File(file.parentFile, "Back.wav").exists())
    }

    // ---- A folder the user picked: renamed through its documents provider ----

    /** Answers the queries and the rename call [DestinationManager] makes for a picked folder's
     * documents: a folder `root` holding [names] (document ID to display name). */
    class FakeDocumentsProvider : ContentProvider() {
        companion object {
            val names = linkedMapOf<String, String>()
            var supportsRename = true
            val renameCalls = mutableListOf<Pair<String, String>>()
        }

        override fun onCreate() = true

        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                           selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            val columns = projection ?: arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val cursor = MatrixCursor(columns)
            val rows = if (uri.lastPathSegment == "children") names.keys.toList() else listOf(DocumentsContract.getDocumentId(uri))
            for (id in rows) {
                cursor.addRow(columns.map<String, Any?> { column ->
                    when (column) {
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID -> id
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME -> names[id]
                        DocumentsContract.Document.COLUMN_FLAGS ->
                            if (supportsRename) DocumentsContract.Document.FLAG_SUPPORTS_RENAME else 0
                        else -> null
                    }
                }.toTypedArray())
            }
            return cursor
        }

        override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
            if (method != "android:renameDocument" || extras == null) return null
            @Suppress("DEPRECATION")
            val uri = extras.getParcelable<Uri>("uri")!!
            val id = DocumentsContract.getDocumentId(uri)
            val name = extras.getString(DocumentsContract.Document.COLUMN_DISPLAY_NAME)!!
            renameCalls += id to name
            names[id] = name
            return Bundle()
        }

        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    }

    private val authority = "bukin.wavrecorder.test.documents"

    private fun pickedFolderDocument(vararg files: Pair<String, String>): Uri {
        Robolectric.buildContentProvider(FakeDocumentsProvider::class.java).create(authority)
        FakeDocumentsProvider.names.clear()
        FakeDocumentsProvider.renameCalls.clear()
        FakeDocumentsProvider.supportsRename = true
        files.forEach { (id, name) -> FakeDocumentsProvider.names[id] = name }
        val tree = DocumentsContract.buildTreeDocumentUri(authority, "root")
        return DocumentsContract.buildDocumentUriUsingTree(tree, files.first().first)
    }

    @Test
    fun `renames a recording in a picked folder through its provider`() {
        val uri = pickedFolderDocument("root/1" to "recording_20261019_115500_part01.wav", "root/2" to "other.wav")

        assertEquals(DestinationManager.RenameResult.RENAMED, manager.renameRecording(uri, "Band practice.wav"))

        assertEquals(listOf("root/1" to "Band practice.wav"), FakeDocumentsProvider.renameCalls)
    }

    @Test
    fun `a name another file in the picked folder has is refused, whatever its case`() {
        val uri = pickedFolderDocument("root/1" to "mine.wav", "root/2" to "Taken.wav")

        assertEquals(DestinationManager.RenameResult.NAME_TAKEN, manager.renameRecording(uri, "taken.wav"))

        assertTrue("nothing was renamed", FakeDocumentsProvider.renameCalls.isEmpty())
    }

    @Test
    fun `a picked folder whose provider can't rename is reported so`() {
        val uri = pickedFolderDocument("root/1" to "mine.wav")
        FakeDocumentsProvider.supportsRename = false

        assertEquals(DestinationManager.RenameResult.NOT_SUPPORTED, manager.renameRecording(uri, "New.wav"))

        assertTrue(FakeDocumentsProvider.renameCalls.isEmpty())
    }

    @Test
    fun `a picked-folder recording being written is refused too`() {
        val uri = pickedFolderDocument("root/1" to "live.wav")
        val claim = RecordingOwnership.tryClaim(OutputTarget.SafTarget(uri, "live.wav"), RecordingOwnership.Use.CAPTURING)!!

        assertEquals(DestinationManager.RenameResult.IN_USE, manager.renameRecording(uri, "New.wav"))

        assertTrue(FakeDocumentsProvider.renameCalls.isEmpty())
        claim.release()
    }
}
