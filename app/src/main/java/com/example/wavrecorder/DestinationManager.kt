package com.example.wavrecorder

import android.content.Context
import android.content.Intent
import android.net.Uri
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

    fun deleteRecording(uri: Uri): Boolean {
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

    open fun listRecordings(): List<RecordingItem> {
        val treeUri = getTreeUri()
        val nameToUri: List<Pair<String, Uri>> = if (treeUri != null) {
            DocumentFile.fromTreeUri(context, treeUri)
                ?.listFiles()
                ?.filter { it.isFile && it.name?.endsWith(".wav", ignoreCase = true) == true }
                ?.sortedByDescending { it.lastModified() }
                ?.map { (it.name ?: "recording.wav") to it.uri }
                ?: emptyList()
        } else {
            defaultDir().listFiles { f -> f.isFile && f.name.endsWith(".wav", ignoreCase = true) }
                ?.sortedByDescending { it.lastModified() }
                ?.map { it.name to Uri.fromFile(it) }
                ?: emptyList()
        }

        return nameToUri.map { (name, uri) ->
            val info = WavFileInfo.read(context, uri)
            RecordingItem(
                name = name,
                uri = uri,
                durationSeconds = info?.durationSeconds ?: 0.0,
                sizeBytes = info?.sizeBytes ?: 0L
            )
        }
    }
}
