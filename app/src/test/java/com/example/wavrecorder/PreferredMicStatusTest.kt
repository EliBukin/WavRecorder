package com.example.wavrecorder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers [choosePreferredMicStatus] directly against [InputDeviceSnapshot] fakes rather than real
 * [android.media.AudioDeviceInfo] instances -- Robolectric's shadow builder for that class only
 * supports setting `type`, not `productName`, so a scenario with both an Insta360 device and a
 * generic external one attached at once (the actual case this preference logic exists for) can't
 * be constructed against the real framework class in a JVM test. No Robolectric needed here at
 * all: this decision is plain, Android-free logic.
 */
class PreferredMicStatusTest {

    @Test
    fun `no devices at all reports NoneDetected`() {
        assertEquals(PreferredMicStatus.NoneDetected, choosePreferredMicStatus(emptyList()))
    }

    @Test
    fun `a built-in mic alone (no external input) reports NoneDetected`() {
        val builtIn = InputDeviceSnapshot(label = "Phone microphone", isExternal = false, isInsta360 = false)
        assertEquals(PreferredMicStatus.NoneDetected, choosePreferredMicStatus(listOf(builtIn)))
    }

    @Test
    fun `a generic USB audio input reports ExternalConnected with its own reported name, not a made-up generic phrase`() {
        // Stands in for the real Insta360 USB-C receiver reporting itself as a generic
        // "USB Audio Device" rather than anything containing "Insta360" -- Android doesn't
        // guarantee the product name identifies the brand, so the exact reported name (not a
        // fixed "external mic connected" phrase) is what lets the user recognize their hardware.
        val usbAudioDevice = InputDeviceSnapshot(label = "USB Audio Device", isExternal = true, isInsta360 = false)
        val result = choosePreferredMicStatus(listOf(usbAudioDevice))
        assertEquals(PreferredMicStatus.ExternalConnected("USB Audio Device", isInsta360 = false), result)
    }

    @Test
    fun `an Insta360-named device reports ExternalConnected with isInsta360 true`() {
        val insta360 = InputDeviceSnapshot(label = "Insta360 Mic Air", isExternal = true, isInsta360 = true)
        val result = choosePreferredMicStatus(listOf(insta360))
        assertEquals(PreferredMicStatus.ExternalConnected("Insta360 Mic Air", isInsta360 = true), result)
    }

    @Test
    fun `an Insta360-named device is preferred over a generic external device attached at the same time`() {
        val genericUsb = InputDeviceSnapshot(label = "USB Audio Device", isExternal = true, isInsta360 = false)
        val insta360 = InputDeviceSnapshot(label = "Insta360 Mic Air", isExternal = true, isInsta360 = true)

        // Order shouldn't matter -- Insta360 must win whether it's listed first or last.
        val expected = PreferredMicStatus.ExternalConnected("Insta360 Mic Air", isInsta360 = true)
        assertEquals(expected, choosePreferredMicStatus(listOf(genericUsb, insta360)))
        assertEquals(expected, choosePreferredMicStatus(listOf(insta360, genericUsb)))
    }

    @Test
    fun `the built-in mic being present alongside an external device does not affect the preference`() {
        val builtIn = InputDeviceSnapshot(label = "Phone microphone", isExternal = false, isInsta360 = false)
        val insta360 = InputDeviceSnapshot(label = "Insta360 Mic Air", isExternal = true, isInsta360 = true)
        assertEquals(PreferredMicStatus.ExternalConnected("Insta360 Mic Air", isInsta360 = true),
            choosePreferredMicStatus(listOf(builtIn, insta360)))
    }

    @Test
    fun `ExternalConnected reports false for isInsta360 on a generic device even when it is the only candidate`() {
        val usbHeadset = InputDeviceSnapshot(label = "USB headset microphone", isExternal = true, isInsta360 = false)
        val result = choosePreferredMicStatus(listOf(usbHeadset)) as PreferredMicStatus.ExternalConnected
        assertFalse(result.isInsta360)
        assertTrue(result.label.isNotEmpty())
    }
}
