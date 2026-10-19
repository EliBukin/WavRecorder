package bukin.wavrecorder

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * Resolves where recordings get written and read from: either the app's own
 * external-files directory (no permission needed) or a folder the user picked
 * via the Storage Access Framework, persisted across app restarts.
 *
 * Open (and [listRecordings] open) solely so tests can substitute a subclass that fails on
 * command -- e.g. simulating a revoked SAF permission -- without needing to reproduce that exact
 * OS-level condition, which Robolectric can't reliably do.
 */
open class DestinationManager(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "wav_recorder_prefs"
        private const val KEY_TREE_URI = "destination_tree_uri"

        /** Renames run one at a time, process-wide: a name checked as free stays free until the
         * rename that checked it is done. */
        private val RENAME_LOCK = Any()
    }

    private fun getTreeUri(): Uri? = prefs.getString(KEY_TREE_URI, null)?.let(Uri::parse)

    fun setTreeUri(uri: Uri) {
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        prefs.edit().putString(KEY_TREE_URI, uri.toString()).apply()
    }

    fun resetToAppStorage() {
        prefs.edit().remove(KEY_TREE_URI).apply()
    }

    private fun defaultDir(): File = context.getExternalFilesDir(null) ?: context.filesDir

    fun displayName(): String {
        val treeUri = getTreeUri()
        return if (treeUri != null) {
            DocumentFile.fromTreeUri(context, treeUri)?.name ?: treeUri.toString()
        } else {
            "App storage"
        }
    }

    open fun createOutputFile(fileName: String): OutputTarget {
        val treeUri = getTreeUri()
        return if (treeUri != null) {
            val dir = DocumentFile.fromTreeUri(context, treeUri)
                ?: throw IllegalStateException("Selected folder is no longer accessible")
            val uniqueName = uniqueSafFileName(dir, fileName)
            val doc = dir.createFile("audio/x-wav", uniqueName)
                ?: throw IllegalStateException("Failed to create file in the selected folder")
            OutputTarget.SafTarget(doc.uri, uniqueName)
        } else {
            val dir = defaultDir().apply { mkdirs() }
            OutputTarget.FileTarget(uniqueLocalFile(dir, fileName))
        }
    }

    /**
     * The caller derives [fileName] from a timestamp with only second-level precision, so two
     * recording sessions started within the same second land on the identical name. Without this,
     * the second session's `RandomAccessFile(file, "rw"); setLength(0)` would silently truncate
     * the first session's already-finished WAV file. [File.createNewFile] atomically creates the
     * file only if it doesn't already exist — closing the check-then-create race a plain
     * `File.exists()` guard would leave open — so on collision this retries with a numeric suffix
     * until it lands on a genuinely new file.
     */
    private fun uniqueLocalFile(dir: File, fileName: String): File {
        var candidate = File(dir, fileName)
        var attempt = 1
        while (!candidate.createNewFile()) {
            candidate = File(dir, disambiguate(fileName, attempt))
            attempt++
        }
        return candidate
    }

    /**
     * Same collision as [uniqueLocalFile], for a SAF-picked destination. There's no atomic
     * create-if-absent over [DocumentFile], but a plain existence check is enough in practice:
     * this app is the only writer ever generating these timestamp-derived names.
     */
    private fun uniqueSafFileName(dir: DocumentFile, fileName: String): String {
        var candidate = fileName
        var attempt = 1
        while (dir.findFile(candidate) != null) {
            candidate = disambiguate(fileName, attempt)
            attempt++
        }
        return candidate
    }

    private fun disambiguate(fileName: String, attempt: Int): String {
        val dot = fileName.lastIndexOf('.')
        return if (dot >= 0) "${fileName.substring(0, dot)}_$attempt${fileName.substring(dot)}"
        else "${fileName}_$attempt"
    }

    /** How a [deleteRecording] call ended. */
    enum class DeleteResult {
        DELETED,
        /** Left alone: it is being recorded, finalized or repaired right now (see [RecordingOwnership]). */
        IN_USE,
        FAILED
    }

    /**
     * Deletes the recording at [uri] -- unless it is in use: being captured, finalized or
     * repaired. The one delete path every caller goes through; the in-use check and the deletion
     * happen atomically with respect to a writer claiming the file ([RecordingOwnership]), so a
     * live recording can't be deleted from under its writer, whatever the caller's view of it.
     */
    fun deleteRecording(uri: Uri): DeleteResult {
        val deleted = RecordingOwnership.deleteUnlessInUse(uri) { removeFromStorage(uri) } ?: return DeleteResult.IN_USE
        return if (deleted) DeleteResult.DELETED else DeleteResult.FAILED
    }

    /** The storage deletion itself, called only by [deleteRecording] once the file is known not to
     * be in use. Open so tests can substitute a failing or recording one. */
    protected open fun removeFromStorage(uri: Uri): Boolean {
        return try {
            if (uri.scheme == "file") {
                val path = uri.path ?: return false
                File(path).delete()
            } else {
                DocumentFile.fromSingleUri(context, uri)?.delete() ?: false
            }
        } catch (e: Exception) {
            false
        }
    }

    /** How a [renameRecording] call ended. */
    enum class RenameResult {
        RENAMED,
        /** Left alone: it is being recorded, finalized, repaired or deleted right now, or it may
         * still need repairing after an interrupted recording (see [ActiveSegmentJournal]). */
        IN_USE,
        /** Another file in the folder already has that name (compared ignoring case). Nothing changed. */
        NAME_TAKEN,
        /** The selected folder's storage provider can't rename files. */
        NOT_SUPPORTED,
        FAILED
    }

    /**
     * Renames the recording at [uri] to [newFileName] (with its extension, as
     * [RecordingRenameRules] produces it) -- unless it is in use, or the crash-recovery journal
     * still names it: a file that may need repairing must keep the name the journal knows it by.
     * Like [deleteRecording], the in-use check holds for the whole rename ([RecordingOwnership]).
     * Never replaces another file: a name already taken is refused. Does storage I/O, so callers
     * run it off the main thread.
     */
    fun renameRecording(uri: Uri, newFileName: String): RenameResult {
        // Only a name the rules accept as-is: never a path, or a name without the extension.
        if (RecordingRenameRules.validate(newFileName, currentFileName = "") != RecordingRenameRules.Result.Valid(newFileName)) {
            return RenameResult.FAILED
        }
        val path = uri.path
        val toUri = if (uri.scheme == "file" && path != null) Uri.fromFile(File(File(path).parentFile, newFileName)) else null
        return synchronized(RENAME_LOCK) {
            RecordingOwnership.renameUnlessInUse(uri, toUri) {
                if (RecordingOwnership.keyOf(uri) in journaledKeys()) RenameResult.IN_USE
                else renameInStorage(uri, newFileName)
            } ?: RenameResult.IN_USE
        }
    }

    /** The files the crash-recovery journal still names, keyed as [RecordingOwnership] keys them.
     * Open so tests can name files without writing a journal. */
    protected open fun journaledKeys(): Set<String> =
        ActiveSegmentJournal(context).journaledTargets().mapTo(HashSet()) { RecordingOwnership.keyOf(it) }

    /** The storage rename itself, called only by [renameRecording] once the file is known to be
     * free to rename. Open so tests can substitute a failing one. */
    protected open fun renameInStorage(uri: Uri, newFileName: String): RenameResult = try {
        if (uri.scheme == "file") renameLocalFile(uri, newFileName) else renameDocument(uri, newFileName)
    } catch (e: Exception) {
        RenameResult.FAILED
    }

    private fun renameLocalFile(uri: Uri, newFileName: String): RenameResult {
        val source = File(uri.path ?: return RenameResult.FAILED)
        val dir = source.parentFile ?: return RenameResult.FAILED
        if (!source.isFile) return RenameResult.FAILED
        // Compared ignoring case: shared storage is case-insensitive on many devices, so a name
        // differing only in case can be the same file -- which File.renameTo would replace.
        val names = dir.list() ?: return RenameResult.FAILED
        if (names.any { it != source.name && it.equals(newFileName, ignoreCase = true) }) return RenameResult.NAME_TAKEN
        val target = File(dir, newFileName)
        return if (source.renameTo(target) && target.isFile) RenameResult.RENAMED else RenameResult.FAILED
    }

    private fun renameDocument(uri: Uri, newFileName: String): RenameResult {
        val resolver = context.contentResolver
        val flags = resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_FLAGS), null, null, null)?.use {
            if (it.moveToFirst()) it.getInt(0) else null
        } ?: return RenameResult.FAILED
        if (flags and DocumentsContract.Document.FLAG_SUPPORTS_RENAME == 0) return RenameResult.NOT_SUPPORTED
        // Recordings sit directly in the picked folder: the root of the tree this document is in.
        val ownId = DocumentsContract.getDocumentId(uri)
        val siblings = DocumentsContract.buildChildDocumentsUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
        val projection = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        val taken = resolver.query(siblings, projection, null, null, null)?.use { cursor ->
            var found = false
            while (!found && cursor.moveToNext()) {
                found = cursor.getString(0) != ownId && cursor.getString(1)?.equals(newFileName, ignoreCase = true) == true
            }
            found
        } ?: return RenameResult.FAILED
        if (taken) return RenameResult.NAME_TAKEN
        return if (DocumentsContract.renameDocument(resolver, uri, newFileName) != null) RenameResult.RENAMED else RenameResult.FAILED
    }

    open fun listRecordings(): List<RecordingItem> {
        val treeUri = getTreeUri()
        // Each file's last-modified time is read once: sorting by a DocumentFile's lastModified()
        // directly would query the provider again for every comparison.
        val files: List<Triple<String, Uri, Long>> = if (treeUri != null) {
            DocumentFile.fromTreeUri(context, treeUri)
                ?.listFiles()
                ?.filter { it.isFile && it.name?.endsWith(".wav", ignoreCase = true) == true }
                ?.map { Triple(it.name ?: "recording.wav", it.uri, it.lastModified()) }
                ?: emptyList()
        } else {
            defaultDir().listFiles { f -> f.isFile && f.name.endsWith(".wav", ignoreCase = true) }
                ?.map { Triple(it.name, Uri.fromFile(it), it.lastModified()) }
                ?: emptyList()
        }

        return files.sortedByDescending { it.third }.map { (name, uri, lastModified) ->
            // Checked once per file (a per-file content-provider round trip is already the
            // dominant cost here, so this is cheap relative to it): without this, a cancelled
            // caller running this inside kotlinx.coroutines.runInterruptible would still scan
            // every remaining file in a large folder to completion regardless of cancellation.
            if (Thread.interrupted()) throw InterruptedException("Recording list scan interrupted")
            val info = WavFileInfo.read(context, uri)
            RecordingItem(
                name = name,
                uri = uri,
                durationSeconds = info?.durationSeconds ?: 0.0,
                sizeBytes = info?.sizeBytes ?: 0L,
                lastModifiedMillis = lastModified
            )
        }
    }
}
