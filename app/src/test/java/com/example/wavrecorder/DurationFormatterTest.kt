package com.example.wavrecorder

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Covers the "last recording" duration text shown on the Record screen, including the plural
 * boundaries (1 vs 2+ of a unit) and the two-largest-units composition rule. */
@RunWith(RobolectricTestRunner::class)
class DurationFormatterTest {

    private fun context() = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Test
    fun `whole seconds under a minute`() {
        assertEquals("13 seconds", DurationFormatter.format(context(), 13))
        assertEquals("1 second", DurationFormatter.format(context(), 1))
        assertEquals("0 seconds", DurationFormatter.format(context(), 0))
    }

    @Test
    fun `minutes and seconds combine when both are non-zero`() {
        assertEquals("3 minutes 12 seconds", DurationFormatter.format(context(), 3 * 60 + 12))
        assertEquals("1 minute 1 second", DurationFormatter.format(context(), 61))
    }

    @Test
    fun `an exact number of minutes omits the zero-seconds remainder`() {
        assertEquals("2 minutes", DurationFormatter.format(context(), 120))
    }

    @Test
    fun `hours and minutes combine, dropping seconds entirely once there is at least an hour`() {
        assertEquals("1 hour 5 minutes", DurationFormatter.format(context(), 3600 + 5 * 60 + 45))
        assertEquals("2 hours", DurationFormatter.format(context(), 2 * 3600))
    }
}
