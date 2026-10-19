package bukin.wavrecorder

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.FileNotFoundException

/**
 * Share and Save a copy ([RecordingSharing]): the receiving app can read exactly the shared
 * recordings, through this app's own provider for app storage; a copy is complete or removed; the
 * recording itself is never changed.
 */
@RunWith(RobolectricTestRunner::class)
class RecordingSharingTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun resetFileProvider() = FileProviderTestSupport.reset()

    private fun recording(name: String, bytes: ByteArray = ByteArray(1000) { it.toByte() }): File {
        val target = DestinationManager(context).createOutputFile(name) as OutputTarget.FileTarget
        target.file.writeBytes(bytes)
        return target.file
    }

    private fun item(file: File) = RecordingItem(file.name, Uri.fromFile(file), 1.0, file.length())

    @Test
    fun `a recording in app storage is handed out through the app's file provider, readable as it is`() {
        val audio = ByteArray(3000) { (it % 251).toByte() }
        val file = recording("recording_20261019_115500_part01.wav", audio)

        val uri = RecordingSharing.shareableUri(context, Uri.fromFile(file))

        assertEquals("content", uri.scheme)
        assertEquals("bukin.wavrecorder.fileprovider", uri.authority)
        assertArrayEquals(audio, context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
        assertEquals(RecordingSharing.MIME_TYPE, context.contentResolver.getType(uri))
    }

    @Test
    fun `a recording in a picked folder is handed out by its own document URI`() {
        val uri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AMusic/document/primary%3AMusic%2Fa.wav")
        assertEquals(uri, RecordingSharing.shareableUri(context, uri))
    }

    @Test
    fun `one recording is shared with ACTION_SEND, granting read access to it only`() {
        val file = recording("talk.wav")

        val chooser = RecordingSharing.chooserIntent(context, listOf(item(file)), "Share recording")

        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertEquals("Share recording", chooser.getCharSequenceExtra(Intent.EXTRA_TITLE).toString())
        @Suppress("DEPRECATION")
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        val shared = RecordingSharing.shareableUri(context, Uri.fromFile(file))
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("audio/x-wav", send.type)
        @Suppress("DEPRECATION")
        assertEquals(shared, send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        assertEquals(1, send.clipData!!.itemCount)
        assertEquals(shared, send.clipData!!.getItemAt(0).uri)
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals("read only", 0, send.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }

    @Test
    fun `several recordings are shared at once with ACTION_SEND_MULTIPLE`() {
        val files = listOf(recording("a.wav"), recording("b.wav"), recording("c.wav"))

        val chooser = RecordingSharing.chooserIntent(context, files.map(::item), "Share 3 recordings")

        @Suppress("DEPRECATION")
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        val expected = files.map { RecordingSharing.shareableUri(context, Uri.fromFile(it)) }
        assertEquals(Intent.ACTION_SEND_MULTIPLE, send.action)
        @Suppress("DEPRECATION")
        assertEquals(expected, send.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM))
        assertEquals(expected, (0 until send.clipData!!.itemCount).map { send.clipData!!.getItemAt(it).uri })
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    @Test
    fun `a copy is the whole recording, and the recording is untouched`() {
        val audio = ByteArray(700_000) { (it % 253).toByte() } // more than one copy buffer
        val file = recording("long.wav", audio)
        val destination = File(context.cacheDir, "copy.wav").apply { createNewFile() }

        assertTrue(RecordingSharing.copyRecording(context, Uri.fromFile(file), Uri.fromFile(destination)))

        assertArrayEquals(audio, destination.readBytes())
        assertArrayEquals(audio, file.readBytes())
    }

    // ---- The destination the save dialog created, served by its documents provider ----

    /** A save-dialog destination: one document, [backing], that can be made to fail writing, and
     * which records being deleted. */
    class FakeDestinationProvider : ContentProvider() {
        companion object {
            lateinit var backing: File
            var failWrites = false
            val deleted = mutableListOf<Uri>()
        }

        override fun onCreate() = true

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            if (!backing.exists()) throw FileNotFoundException(uri.toString())
            // Writing to a read-only descriptor fails part-way, like a full or removed card.
            val actualMode = if (failWrites) ParcelFileDescriptor.MODE_READ_ONLY else ParcelFileDescriptor.parseMode(mode)
            return ParcelFileDescriptor.open(backing, actualMode)
        }

        override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
            @Suppress("DEPRECATION")
            if (method == "android:deleteDocument") deleted += extras!!.getParcelable<Uri>("uri")!!
            return Bundle()
        }

        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                           selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    }

    private fun destination(existing: ByteArray = ByteArray(0)): Uri {
        val authority = "bukin.wavrecorder.test.destination"
        Robolectric.buildContentProvider(FakeDestinationProvider::class.java).create(authority)
        FakeDestinationProvider.backing = File(context.cacheDir, "destination.wav").apply { writeBytes(existing) }
        FakeDestinationProvider.failWrites = false
        FakeDestinationProvider.deleted.clear()
        return Uri.parse("content://$authority/document/destination")
    }

    @Test
    fun `a copy into a picked place goes through its provider`() {
        val audio = ByteArray(5000) { (it % 97).toByte() }
        val target = destination()

        assertTrue(RecordingSharing.copyRecording(context, Uri.fromFile(recording("x.wav", audio)), target))

        assertArrayEquals(audio, FakeDestinationProvider.backing.readBytes())
        assertTrue(FakeDestinationProvider.deleted.isEmpty())
    }

    @Test
    fun `a copy that fails part-way is removed, and the recording is untouched`() {
        val audio = ByteArray(5000) { 3 }
        val file = recording("x.wav", audio)
        val target = destination()
        FakeDestinationProvider.failWrites = true

        assertFalse(RecordingSharing.copyRecording(context, Uri.fromFile(file), target))

        assertEquals(listOf(target), FakeDestinationProvider.deleted)
        assertArrayEquals(audio, file.readBytes())
    }

    @Test
    fun `a recording that can't be read leaves the chosen destination exactly as it was`() {
        // The user may have chosen an existing file to replace: nothing was written, so it stays.
        val existing = byteArrayOf(1, 2, 3, 4)
        val target = destination(existing)
        val missing = File(context.cacheDir, "missing.wav")

        assertFalse(RecordingSharing.copyRecording(context, Uri.fromFile(missing), target))

        assertTrue(FakeDestinationProvider.deleted.isEmpty())
        assertArrayEquals(existing, FakeDestinationProvider.backing.readBytes())
    }
}
