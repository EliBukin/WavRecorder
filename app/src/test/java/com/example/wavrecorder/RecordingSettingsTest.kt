package com.example.wavrecorder

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RecordingSettingsTest {

    private fun app(): Application = ApplicationProvider.getApplicationContext()

    private fun rawPrefs() = app().getSharedPreferences("wav_recorder_settings", Context.MODE_PRIVATE)

    @Test
    fun `split duration defaults to 60 minutes when nothing has been chosen yet`() {
        assertEquals(RecordingSplitDuration.MINUTES_60, RecordingSettings(app()).splitDuration)
    }

    @Test
    fun `every valid choice persists and is read back by a fresh instance`() {
        RecordingSplitDuration.entries.forEach { choice ->
            RecordingSettings(app()).splitDuration = choice
            // A brand-new instance stands in for the app having been closed and reopened: nothing
            // is cached in memory, the value must come back from SharedPreferences.
            assertEquals(choice, RecordingSettings(app()).splitDuration)
        }
    }

    @Test
    fun `an unsupported stored value reads back as 60 minutes`() {
        rawPrefs().edit().putInt("split_duration_minutes", 17).commit()
        assertEquals(RecordingSplitDuration.MINUTES_60, RecordingSettings(app()).splitDuration)
    }

    @Test
    fun `a stored value of the wrong type reads back as 60 minutes instead of crashing`() {
        rawPrefs().edit().putString("split_duration_minutes", "thirty").commit()
        assertEquals(RecordingSplitDuration.MINUTES_60, RecordingSettings(app()).splitDuration)
    }
}
