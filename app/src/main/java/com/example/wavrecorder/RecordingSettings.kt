package com.example.wavrecorder

import android.content.Context

/**
 * User-chosen recording preferences that must survive the app being closed and reopened. Only
 * [RecordFragment] reads and writes this: [RecordingService] never consults it directly, but is
 * handed the value captured at the moment Record was pressed (see
 * [RecordingService.startRecording]) -- so changing the setting mid-recording can only ever affect
 * the *next* session, never the one already running.
 */
internal class RecordingSettings(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "wav_recorder_settings"
        private const val KEY_SPLIT_MINUTES = "split_duration_minutes"
    }

    /** Defaults to [RecordingSplitDuration.DEFAULT] until the user picks something else; a stored
     * value that isn't a supported choice (or isn't even an Int) also reads back as the default. */
    var splitDuration: RecordingSplitDuration
        get() {
            val stored = try {
                prefs.getInt(KEY_SPLIT_MINUTES, RecordingSplitDuration.DEFAULT.minutes)
            } catch (_: ClassCastException) {
                RecordingSplitDuration.DEFAULT.minutes
            }
            return RecordingSplitDuration.fromMinutes(stored)
        }
        set(value) {
            prefs.edit().putInt(KEY_SPLIT_MINUTES, value.minutes).apply()
        }
}
