package com.example.wavrecorder

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/** Reads a WAV file's RIFF chunks to recover duration/size without decoding the audio. */
object WavFileInfo {

    data class Info(val durationSeconds: Double, val sizeBytes: Long)

    fun read(context: Context, uri: Uri): Info? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val format = WavRiffParser.parse(input) ?: return null

                // The header can declare more audio than the file actually holds (damaged or
                // cut-off write); duration computed from that declared size would then be wrong
                // in a way that looks perfectly valid, so verify it before trusting it. This is
                // called once per file in the library list, so it must stay cheap: check
                // completeness arithmetically against a file-size query first, and only fall
                // back to actually streaming through the whole audio payload (slow for long
                // recordings) when the provider can't report a size at all.
                val actualFileSize = queryActualSize(context, uri)
                val complete = WavRiffParser.isDataComplete(actualFileSize, format)
                    ?: (WavRiffParser.countAvailableBytes(input, format.dataSize) >= format.dataSize)
                if (!complete) return null

                val duration = if (format.byteRate > 0) format.dataSize.toDouble() / format.byteRate else 0.0
                val sizeBytes = actualFileSize ?: (format.dataOffset + format.dataSize)
                Info(duration, sizeBytes)
            }
        } catch (e: Exception) {
            null
        }
    }

    /** The RIFF/data chunk sizes only describe the audio payload; a file may carry extra chunks
     * (metadata, padding) beyond it, so the true file size has to come from the file system /
     * content provider rather than being derived from the parsed header. */
    internal fun queryActualSize(context: Context, uri: Uri): Long? {
        return try {
            if (uri.scheme == "file") {
                uri.path?.let { File(it).length() }
            } else {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                    val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) cursor.getLong(index) else null
                }
            }
        } catch (e: Exception) {
            null
        }
    }
}
