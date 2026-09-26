package com.example.wavrecorder

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.view.View
import android.widget.TextView
import androidx.fragment.app.testing.launchFragmentInContainer
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.AudioDeviceInfoBuilder
import org.robolectric.shadows.AudioProfileBuilder

/**
 * The Record screen's format line for a microphone test negotiated by the real
 * [openBestAudioRecord] (see [AudioNegotiationIntegrationTest] for the negotiation itself). Only
 * routing is stood in for ([RoutedAsRequested]), since Robolectric's AudioRecord can't route.
 */
@RunWith(RobolectricTestRunner::class)
class RecordFragmentNegotiatedFormatTest {

    private fun app(): Application = ApplicationProvider.getApplicationContext()

    private fun profile(encoding: Int, rates: IntArray, masks: IntArray) = AudioProfileBuilder.newBuilder()
        .setFormat(encoding).setSamplingRates(rates).setChannelMasks(masks)
        .setChannelIndexMasks(IntArray(0)).setEncapsulationType(0).build()

    @Before
    fun setUp() {
        shadowOf(app()).grantPermissions(Manifest.permission.RECORD_AUDIO)
        shadowOf(app()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val usb = AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_USB_DEVICE).setProfiles(listOf(
            profile(AudioFormat.ENCODING_PCM_24BIT_PACKED, intArrayOf(48000, 96000), intArrayOf(AudioFormat.CHANNEL_IN_STEREO)),
            profile(AudioFormat.ENCODING_PCM_16BIT, intArrayOf(44100, 48000), intArrayOf(AudioFormat.CHANNEL_IN_MONO, AudioFormat.CHANNEL_IN_STEREO))
        )).build()
        shadowOf(app().getSystemService(AudioManager::class.java)).setInputDevices(listOf(usb))
    }

    private fun usbDevice(): InputDevice = inputDeviceOf(
        app().getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_INPUTS).single()
    )

    private fun routedOpen(requests: MutableList<InputDevice> = mutableListOf()) = routedOpenBestAudioRecord(requests)

    @Test
    fun `the Record screen shows the format the microphone test actually opened`() {
        val service = Robolectric.buildService(RecordingService::class.java).create().get()
        shadowOf(app()).setComponentNameAndServiceForBindService(ComponentName(app(), RecordingService::class.java), service.LocalBinder())
        val scenario = launchFragmentInContainer<RecordFragment>(themeResId = R.style.Theme_WavRecorder)
        scenario.onFragment { fragment ->
            // The real negotiation; only routing is stood in for, since Robolectric's AudioRecord
            // can't route (the app would otherwise -- correctly -- stop the test as a route
            // mismatch for the expected USB microphone).
            fragment.micTestSession = MicTestSession(openAudioSource = routedOpen())
            fragment.requireView().findViewById<View>(R.id.testMicButton).performClick()
        }
        lateinit var session: MicTestSession
        scenario.onFragment { session = it.micTestSession }
        awaitStartupDelivered { session.starting }

        scenario.onFragment { fragment ->
            val line = fragment.requireView().findViewById<TextView>(R.id.audioFormatText)
            assertEquals(View.VISIBLE, line.visibility)
            assertEquals("${usbDevice().label} \u2022 48\u00A0kHz \u2022 16-bit \u2022 Stereo", line.text.toString())
            assertTrue(fragment.micTestSession.active)
            fragment.micTestSession.stop()
        }
    }
}
