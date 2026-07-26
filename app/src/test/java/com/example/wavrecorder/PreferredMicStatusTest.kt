package com.example.wavrecorder

import org.junit.Assert.assertEquals
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
    fun `a single external, non-Insta360 device reports ExternalConnected`() {
        val usbMic = InputDeviceSnapshot(label = "USB microphone", isExternal = true, isInsta360 = false)
        assertEquals(PreferredMicStatus.ExternalConnected, choosePreferredMicStatus(listOf(usbMic)))
    }

    @Test
    fun `an Insta360 device reports Insta360Connected with its own label`() {
        val insta360 = InputDeviceSnapshot(label = "Insta360 Mic Air", isExternal = true, isInsta360 = true)
        val result = choosePreferredMicStatus(listOf(insta360))
        assertEquals(PreferredMicStatus.Insta360Connected("Insta360 Mic Air"), result)
    }

    @Test
    fun `an Insta360 device is preferred over another external device attached at the same time`() {
        val genericUsb = InputDeviceSnapshot(label = "USB microphone", isExternal = true, isInsta360 = false)
        val insta360 = InputDeviceSnapshot(label = "Insta360 Mic Air", isExternal = true, isInsta360 = true)

        // Order shouldn't matter -- Insta360 must win whether it's listed first or last.
        assertEquals(PreferredMicStatus.Insta360Connected("Insta360 Mic Air"),
            choosePreferredMicStatus(listOf(genericUsb, insta360)))
        assertEquals(PreferredMicStatus.Insta360Connected("Insta360 Mic Air"),
            choosePreferredMicStatus(listOf(insta360, genericUsb)))
    }

    @Test
    fun `the built-in mic being present alongside an external device does not affect the preference`() {
        val builtIn = InputDeviceSnapshot(label = "Phone microphone", isExternal = false, isInsta360 = false)
        val insta360 = InputDeviceSnapshot(label = "Insta360 Mic Air", isExternal = true, isInsta360 = true)
        assertEquals(PreferredMicStatus.Insta360Connected("Insta360 Mic Air"),
            choosePreferredMicStatus(listOf(builtIn, insta360)))
    }
}
