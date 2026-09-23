package com.example.wavrecorder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingSplitDurationTest {

    @Test
    fun `the default is 60 minutes, preserving the original fixed behavior`() {
        assertEquals(RecordingSplitDuration.MINUTES_60, RecordingSplitDuration.DEFAULT)
        assertEquals(60, RecordingSplitDuration.DEFAULT.minutes)
        assertEquals(3600L, RecordingSplitDuration.DEFAULT.seconds)
    }

    @Test
    fun `exactly the three supported choices are offered`() {
        assertEquals(listOf(30, 45, 60), RecordingSplitDuration.entries.map { it.minutes })
    }

    @Test
    fun `each valid choice maps to itself and to the right number of seconds`() {
        assertEquals(RecordingSplitDuration.MINUTES_30, RecordingSplitDuration.fromMinutes(30))
        assertEquals(RecordingSplitDuration.MINUTES_45, RecordingSplitDuration.fromMinutes(45))
        assertEquals(RecordingSplitDuration.MINUTES_60, RecordingSplitDuration.fromMinutes(60))
        assertEquals(1800L, RecordingSplitDuration.MINUTES_30.seconds)
        assertEquals(2700L, RecordingSplitDuration.MINUTES_45.seconds)
        assertEquals(3600L, RecordingSplitDuration.MINUTES_60.seconds)
        listOf(30, 45, 60).forEach { assertTrue("$it should be valid", RecordingSplitDuration.isValidMinutes(it)) }
    }

    @Test
    fun `any unsupported value falls back to 60 minutes`() {
        listOf(0, -1, -60, 1, 15, 29, 31, 44, 46, 59, 61, 90, 120, Int.MIN_VALUE, Int.MAX_VALUE).forEach {
            assertFalse("$it must not be valid", RecordingSplitDuration.isValidMinutes(it))
            assertEquals("$it must fall back to the default",
                RecordingSplitDuration.MINUTES_60, RecordingSplitDuration.fromMinutes(it))
        }
    }
}
