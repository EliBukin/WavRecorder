package bukin.wavrecorder

import android.content.ClipData
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException

/**
 * Sending recordings to other apps (Share) and copying one to a place the user picks (Save a
 * copy). Both only ever read a recording; neither changes or removes it.
 *
 * A recording in app storage is a plain file other apps can't open, so it is handed out through
 * this app's [FileProvider] as a content URI, readable only by the app the user shares it with. A
 * recording in a folder the user picked already has a document URI, handed out as it is.
 */
internal object RecordingSharing {

    /** What [android.webkit.MimeTypeMap] -- and so [FileProvider] -- calls a `.wav` file; also the
     * type new recordings are created with in a picked folder. */
    const val MIME_TYPE = "audio/x-wav"

    fun authority(context: Context): String = "${context.packageName}.fileprovider"

    /** A URI another app can be granted read access to, for the recording at [uri]. */
    fun shareableUri(context: Context, uri: Uri): Uri {
        val path = uri.path
        return if (uri.scheme == ContentResolver.SCHEME_FILE && path != null) {
            FileProvider.getUriForFile(context, authority(context), File(path))
        } else {
            uri
        }
    }

    /**
     * The system share sheet for [items]: one recording, or several at once. The receiving app is
     * granted read access to exactly these files, and nothing else.
     */
    fun chooserIntent(context: Context, items: List<RecordingItem>, title: CharSequence): Intent {
        require(items.isNotEmpty()) { "Nothing to share" }
        val uris = items.map { shareableUri(context, it.uri) }
        val send = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        send.type = MIME_TYPE
        // The grant travels with the ClipData; Intent.createChooser carries both over to the
        // chooser, which passes them on to whichever app the user picks.
        val clip = ClipData.newUri(context.contentResolver, items[0].name, uris[0])
        for (i in 1 until uris.size) clip.addItem(ClipData.Item(uris[i]))
        send.clipData = clip
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, title)
    }

    /**
     * Copies the recording at [source] into [destination], a document the user just created
     * through the system's save dialog. A copy that fails part-way is removed again, so no
     * truncated file that looks like a recording is left behind -- but only once writing to it
     * has begun: if the recording can't even be opened, [destination] is left exactly as it was
     * (the user may have picked an existing file to replace). [source] is never touched. Does
     * storage I/O: callers run it off the main thread. Returns whether the full copy was written.
     */
    fun copyRecording(context: Context, source: Uri, destination: Uri): Boolean {
        val resolver = context.contentResolver
        var writing = false
        val copied = try {
            val input = resolver.openInputStream(source) ?: throw IOException("Couldn't open the recording")
            input.use { from ->
                val output = resolver.openOutputStream(destination, "w") ?: throw IOException("Couldn't open the copy")
                writing = true
                output.use { to -> from.copyTo(to, BUFFER_SIZE) }
            }
            true
        } catch (e: Exception) {
            false
        }
        if (!copied && writing) {
            try {
                DocumentsContract.deleteDocument(resolver, destination)
            } catch (_: Exception) {
            }
        }
        return copied
    }

    private const val BUFFER_SIZE = 256 * 1024
}
