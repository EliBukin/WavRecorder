package com.example.wavrecorder

import android.Manifest
import android.app.Application
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.AudioDeviceInfoBuilder
import org.robolectric.shadows.AudioProfileBuilder

/**
 * The real [openBestAudioRecord] against a USB microphone that advertises correlated profiles.
 * Robolectric's AudioRecord accepts only 16-bit and float PCM, so the advertised 24-bit profile is
 * genuinely rejected by AudioRecord here and negotiation must fall back to the next advertised
 * format -- exactly what a device whose driver doesn't honor its own 24-bit profile would do.
 *
 * Robolectric's AudioRecord can't route to a device, though: it never reports a routed device, so
 * no USB candidate can be verified. Tests of the USB path wrap each real candidate in [RoutedAsRequested],
 * which stands in for routing alone; the tests without it show what the default, unwrapped path
 * does when the route can't be confirmed.
 */
@RunWith(RobolectricTestRunner::class)
class AudioNegotiationIntegrationTest {

    @get:org.junit.Rule
    val tempFolder = org.junit.rules.TemporaryFolder()

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

    private val expected = PcmFormat.of(48000, PcmEncoding.PCM_16, 2)

    private fun usbDevice(): InputDevice = inputDeviceOf(
        app().getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_INPUTS).single()
    )

    private fun routedOpen(requests: MutableList<InputDevice> = mutableListOf()) = routedOpenBestAudioRecord(requests)

    @Test
    fun `the best advertised format that AudioRecord accepts is chosen, after rejecting the higher ones`() {
        val requests = mutableListOf<InputDevice>()
        // The blocking negotiation itself, called directly (production only ever runs it on the
        // startup worker -- see the tests below, which go through that).
        val config = openBestAudioRecord(StartupScope(), app()) { RoutedAsRequested(it, requests) }
        try {
            assertEquals(expected, config.format)
            val negotiation = config.negotiation!!
            assertEquals(CandidateOrigin.PROFILE, negotiation.origin)
            assertEquals(
                "24-bit at 96 and 48 kHz were tried first, each with UNPROCESSED and then MIC, and rejected",
                4, negotiation.rejectedAttempts
            )
            assertEquals(android.media.MediaRecorder.AudioSource.UNPROCESSED, negotiation.audioSource)
            assertEquals(0, config.bufferSize % expected.bytesPerFrame)
            assertTrue("handed over already recording", config.alreadyStarted)
            // Robolectric never reports the device side, so the MIC variant of the same format is
            // also verified (it could have been reported), and the UNPROCESSED winner reopened: the
            // USB microphone is the only device ever requested.
            assertTrue(requests.isNotEmpty() && requests.all { it == usbDevice() })
            assertEquals(MicrophoneInfo(usbDevice().label, isExternal = true, verified = true), config.source.describeMicrophone())
        } finally {
            config.source.stop()
            config.source.release()
        }
    }

    @Test
    fun `the microphone test and a real recording negotiate the identical format`() {
        // The production startup: negotiation on the shared startup worker, delivered on the main looper.
        val recorder = WavRecorder(openAudioSource = routedOpen())
        val dir = tempFolder.newFolder()
        recorder.start(
            app(), WavRecorder.NextTarget { OutputTarget.FileTarget(java.io.File(dir, "r.wav").apply { createNewFile() }) },
            onSegmentStarted = {}, onAmplitude = {}, onError = {}
        )
        assertTrue("start() returns while the negotiation is still running", recorder.isStarting || recorder.isActive)
        awaitStartupDelivered { recorder.isStarting }
        val recorded = recorder.sessionFormat
        recorder.stop()

        val test = MicTestSession(openAudioSource = routedOpen())
        test.start(app(), onMicrophoneInfo = {}, onLevel = {}, onError = {})
        awaitStartupDelivered { test.starting }
        val tested = test.format
        test.stop()

        assertEquals(expected, recorded)
        assertEquals(recorded, tested)
        // Identical in everything but how long each took, in real time.
        assertEquals(recorder.sessionNegotiation!!.copy(negotiationMs = 0), test.negotiation!!.copy(negotiationMs = 0))
    }

    @Test
    fun `by default, recording and the microphone test run the same route-aware negotiation`() {
        // No stand-in: Robolectric never reports a routed device, so not one USB candidate can be
        // confirmed on the USB microphone. Both must reject every one of them the same way and
        // then negotiate the phone microphone instead, reported as unverified -- never as the USB mic.
        // The default openers, run inline in virtual time: every USB candidate waits out its full
        // route window, which in real time would take seconds.
        val startup = AudioStartup(java.util.concurrent.Executor { it.run() }, java.util.concurrent.Executor { it.run() }, VirtualStartupTiming())
        val recorder = WavRecorder(startup = startup)
        val dir = tempFolder.newFolder()
        var recordedMic: MicrophoneInfo? = null
        recorder.start(
            app(), WavRecorder.NextTarget { OutputTarget.FileTarget(java.io.File(dir, "r.wav").apply { createNewFile() }) },
            onSegmentStarted = {}, onAmplitude = {}, onError = {}, onMicrophoneInfo = { recordedMic = it }
        )
        val recorded = recorder.sessionNegotiation
        recorder.stop()

        val test = MicTestSession(startup = startup)
        var testedMic: MicrophoneInfo? = null
        test.start(app(), onMicrophoneInfo = { testedMic = it }, onLevel = {}, onError = {})
        val tested = test.negotiation
        test.stop()

        // 24/96, 24/48, 16/48 stereo, 16/48 mono, 16/44.1 (both layouts), each with UNPROCESSED and MIC.
        val sdk = android.os.Build.VERSION.SDK_INT
        val usbAttempts = FormatNegotiation.candidates(AudioInputCapabilities.of(
            app().getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_INPUTS).single()
        ), sdk).size * 2
        // No built-in microphone is listed here, so the phone fallback knows nothing about it and
        // also tries the exploratory formats: Robolectric's AudioRecord rejects the 24-bit ones.
        val phone24BitAttempts = FormatNegotiation.candidates(DeviceCapabilities.UNKNOWN, sdk)
            .count { it.format.encoding == PcmEncoding.PCM_24_PACKED } * 2
        assertEquals(usbAttempts + phone24BitAttempts, recorded!!.rejectedAttempts)
        assertEquals(recorded, tested)
        assertEquals(FormatNegotiation.COMPATIBILITY_FALLBACKS[0], recorded.format)
        assertEquals(MicrophoneInfo.UNVERIFIED, recordedMic)
        assertEquals(recordedMic, testedMic)
    }
}
