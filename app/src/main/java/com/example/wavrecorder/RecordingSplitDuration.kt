package com.example.wavrecorder

/**
 * How much audio each WAV file of one continuous recording may hold before [WavRecorder] finalizes
 * it and continues seamlessly in a new `_partNN` file. Only these fixed choices are ever honored:
 * every entry point that turns an untrusted number (a persisted preference, a value handed to
 * [RecordingService.startRecording]) into a duration goes through [fromMinutes], which falls back
 * to [DEFAULT] rather than ever producing some other split length.
 */
enum class RecordingSplitDuration(val minutes: Int) {
    MINUTES_30(30),
    MINUTES_45(45),
    MINUTES_60(60);

    /** The same duration in seconds of audio, as [WavRecorder.start] takes it. */
    val seconds: Long get() = minutes * 60L

    companion object {
        /** The app's original, fixed behavior before this became configurable. */
        val DEFAULT = MINUTES_60

        /** True only for one of the supported choices (30, 45 or 60). */
        fun isValidMinutes(minutes: Int): Boolean = entries.any { it.minutes == minutes }

        /** The matching choice, or [DEFAULT] for any unsupported value -- a corrupted or
         * future-version preference, a bad caller -- so no caller ever has to handle an invalid
         * duration itself. */
        fun fromMinutes(minutes: Int): RecordingSplitDuration =
            entries.firstOrNull { it.minutes == minutes } ?: DEFAULT
    }
}
