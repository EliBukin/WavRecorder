package com.example.wavrecorder

import org.junit.Assert.assertEquals
import org.junit.Test

class ElapsedTimeFormatterTest {

    @Test
    fun `under an hour it reads as minutes and seconds`() {
        assertEquals("00:00", ElapsedTimeFormatter.format(0))
        assertEquals("00:00", ElapsedTimeFormatter.format(999))
        assertEquals("00:01", ElapsedTimeFormatter.format(1_000))
        assertEquals("12:34", ElapsedTimeFormatter.format(754_000))
        assertEquals("59:59", ElapsedTimeFormatter.format(3_599_999))
    }

    @Test
    fun `from an hour it adds hours`() {
        assertEquals("1:00:00", ElapsedTimeFormatter.format(3_600_000))
        assertEquals("1:02:05", ElapsedTimeFormatter.format(3_725_000))
        assertEquals("12:00:01", ElapsedTimeFormatter.format(43_201_000))
    }

    @Test
    fun `a clock that moved backwards never shows negative time`() {
        assertEquals("00:00", ElapsedTimeFormatter.format(-5_000))
    }
}
