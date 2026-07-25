package com.example.wavrecorder

import android.net.Uri
import java.io.File

/** Where a recording is being written. Abstracts over a plain File and a SAF-picked folder. */
sealed class OutputTarget(val displayPath: String) {
    class FileTarget(val file: File) : OutputTarget(file.absolutePath)
    class SafTarget(val uri: Uri, val name: String) : OutputTarget(name)
}

data class RecordingItem(
    val name: String,
    val uri: Uri,
    val durationSeconds: Double,
    val sizeBytes: Long
)
