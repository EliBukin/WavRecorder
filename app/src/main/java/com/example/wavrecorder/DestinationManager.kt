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
 */
class DestinationManager(private val context: Context) {

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

    fun createOutputFile(fileName: String): OutputTarget {
        val treeUri = getTreeUri()
        return if (treeUri != null) {
            val dir = DocumentFile.fromTreeUri(context, treeUri)
                ?: throw IllegalStateException("Selected folder is no longer accessible")
            val doc = dir.createFile("audio/x-wav", fileName)
                ?: throw IllegalStateException("Failed to create file in the selected folder")
            OutputTarget.SafTarget(doc.uri, fileName)
        } else {
            val dir = defaultDir().apply { mkdirs() }
            OutputTarget.FileTarget(File(dir, fileName))
        }
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

    fun listRecordings(): List<RecordingItem> {
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
