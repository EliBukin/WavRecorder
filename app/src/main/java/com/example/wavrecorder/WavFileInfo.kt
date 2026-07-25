package com.example.wavrecorder

import android.content.Context
import android.net.Uri
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Reads just the 44-byte RIFF header to recover duration/size without decoding the file. */
object WavFileInfo {

    data class Info(val durationSeconds: Double, val sizeBytes: Long)

    fun read(context: Context, uri: Uri): Info? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val header = ByteArray(44)
                var totalRead = 0
                while (totalRead < 44) {
                    val n = input.read(header, totalRead, 44 - totalRead)
                    if (n < 0) break
                    totalRead += n
                }
                if (totalRead < 44) return null

                val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                val byteRate = buffer.getInt(28)
                val dataSize = buffer.getInt(40)
                val duration = if (byteRate > 0) dataSize.toDouble() / byteRate else 0.0
                Info(duration, dataSize.toLong() + 44)
            }
        } catch (e: Exception) {
            null
        }
    }
}
