package com.example.wavrecorder

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * [WavRecorder] and [MicTestSession] on top of route-aware negotiation: they take over the
 * candidate negotiation accepted -- already recording on its verified route -- without starting it
 * again, and no output file exists until that candidate has been started and verified.
 */
@RunWith(RobolectricTestRunner::class)
class RouteAwareCaptureTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun context() = ApplicationProvider.getApplicationContext<Context>()

    private val STEREO = PcmFormat.CHANNEL_IN_STEREO
    private val hiRes = PcmFormat.of(96000, PcmEncoding.PCM_24_PACKED, 2)
    private val usbPhase = CapturePhase(
        RouteTarget.External(USB_MIC),
        DeviceCapabilities(profiles = listOf(
            CapabilityProfile(PcmEncoding.PCM_24_PACKED.androidEncoding, intArrayOf(96000), intArrayOf(STEREO), IntArray(0)),
            CapabilityProfile(PcmEncoding.PCM_16.androidEncoding, intArrayOf(48000), intArrayOf(STEREO), IntArray(0))
        ))
    )

    /** 24/96 UNPROCESSED routes to the phone (rejected after starting), 24/96 MIC is refused the
     * device, 16/48 UNPROCESSED fails to start; 16/48 stereo MIC is accepted. */
    private fun mixedUsbFactory() = FakeCandidateFactory { attempt ->
        val ok = FakeCaptureCandidate.Behavior()
        val unprocessed = attempt.audioSource == NegotiatedAudio.AUDIO_SOURCE_UNPROCESSED
        when {
            attempt.candidate.format == hiRes && unprocessed -> ok.copy(route = { PHONE_MIC })
            attempt.candidate.format == hiRes -> ok.copy(grantDevice = false)
            unprocessed -> ok.copy(startThrows = true)
            else -> ok
        }
    }

    private fun open(factory: FakeCandidateFactory) =
        openNegotiatedCapture(listOf(usbPhase), 34, true, factory, removalWatcher = null, scope = StartupScope(VirtualStartupTiming()))

    @Test
    fun `no file exists until the accepted candidate is started and verified, and none for rejected ones`() {
        val factory = mixedUsbFactory()
        val dir = tempFolder.newFolder()
        val targetsCreated = AtomicInteger()
        var stateWhenFileCreated: List<String>? = null
        val audio = ByteArray(4 * 25) { it.toByte() } // 25 whole 16-bit stereo frames
        val delivered = CountDownLatch(1)

        val recorder = WavRecorder(startup = ImmediateStartup, openAudioSource = {
            open(factory).also { factory.opened.last().scriptedReads += audio }
        })
        recorder.start(
            context(),
            WavRecorder.NextTarget {
                targetsCreated.incrementAndGet()
                stateWhenFileCreated = factory.opened.map { "${it.name} starts=${it.starts} stops=${it.stops} releases=${it.releases} routeQueries=${it.routeQueries > 0}" }
                OutputTarget.FileTarget(File(dir, "take.wav").apply { createNewFile() })
            },
            onSegmentStarted = {}, onAmplitude = { delivered.countDown() }, onError = {}
        )
        assertTrue(recorder.isActive)
        val deadline = System.currentTimeMillis() + 3000
        while (factory.opened.last().scriptedReads.isNotEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(5)
        Thread.sleep(50)
        shadowOf(Looper.getMainLooper()).idle()
        recorder.stop()

        assertEquals("exactly one file, for the accepted candidate", 1, targetsCreated.get())
        assertEquals(
            listOf(
                "PCM_24_PACKED/96000/2ch/UNPROCESSED starts=1 stops=1 releases=1 routeQueries=true",
                "PCM_24_PACKED/96000/2ch/MIC starts=0 stops=0 releases=1 routeQueries=false",
                "PCM_16/48000/2ch/UNPROCESSED starts=1 stops=1 releases=1 routeQueries=false",
                "PCM_16/48000/2ch/MIC starts=1 stops=0 releases=0 routeQueries=true"
            ),
            stateWhenFileCreated
        )
        val accepted = factory.opened.last()
        assertEquals("the accepted candidate is never started a second time", 1, accepted.starts)
        assertEquals(1, accepted.releases)
        val file = File(dir, "take.wav")
        val parsed = file.inputStream().use { WavRiffParser.parse(it) }!!
        assertEquals(PcmFormat.of(48000, PcmEncoding.PCM_16, 2), parsed.pcmFormat)
        assertArrayEquals(audio, file.readBytes().copyOfRange(parsed.dataOffset.toInt(), (parsed.dataOffset + parsed.dataSize).toInt()))
    }

    @Test
    fun `when every candidate fails, no file is ever created and the failure is reported`() {
        val factory = FakeCandidateFactory { FakeCaptureCandidate.Behavior(route = { PHONE_MIC }) }
        val dir = tempFolder.newFolder()
        var error: Exception? = null
        val recorder = WavRecorder(startup = ImmediateStartup, openAudioSource = { open(factory) })

        recorder.start(
            context(),
            WavRecorder.NextTarget { throw AssertionError("no output file may be created") },
            onSegmentStarted = {}, onAmplitude = {}, onError = { error = it }
        )

        assertTrue(error is FormatNegotiationException)
        assertTrue(!recorder.isActive)
        assertTrue(dir.listFiles()!!.isEmpty())
        assertEquals(8, factory.opened.size)
        assertTrue("every candidate was stopped and released", factory.opened.all { it.stops == 1 && it.releases == 1 })
    }

    @Test
    fun `the microphone test takes over the accepted candidate without starting it again`() {
        val factory = mixedUsbFactory()
        val levels = CountDownLatch(1)
        var info: MicrophoneInfo? = null
        val session = MicTestSession(startup = ImmediateStartup,
            openAudioSource = { open(factory).also { factory.opened.last().scriptedReads += ByteArray(400) { 0x7F } } },
            levelUpdateIntervalMs = 0
        )

        session.start(context(), onMicrophoneInfo = { info = it }, onLevel = { levels.countDown() }, onError = {})
        val deadline = System.currentTimeMillis() + 3000
        while (levels.count > 0 && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }

        assertEquals(0L, levels.count)
        assertTrue(session.active)
        assertEquals(MicrophoneInfo("USB Mic", isExternal = true, verified = true), info)
        assertEquals(PcmFormat.of(48000, PcmEncoding.PCM_16, 2), session.format)
        val accepted = factory.opened.last()
        session.stop()
        assertEquals(1, accepted.starts)
        assertEquals(1, accepted.releases)
        assertTrue(factory.opened.dropLast(1).all { it.releases == 1 })
    }

    @Test
    fun `the WAV header describes the client bytes written, even when Android converts the capture`() {
        // The device captures 24-bit/48 kHz; its exact format can't be opened, so the best
        // converted client format -- 32-bit/96 kHz stereo -- is recorded. The file must say 32-bit
        // 96 kHz stereo: that is what the bytes are.
        val deviceSide = DeviceSideFormat(48000, PcmEncoding.PCM_24_PACKED.androidEncoding, 2)
        val client = PcmFormat.of(96000, PcmEncoding.PCM_32, 2)
        val phase = CapturePhase(RouteTarget.External(USB_MIC), DeviceCapabilities(profiles = listOf(
            CapabilityProfile(PcmEncoding.PCM_32.androidEncoding, intArrayOf(96000), intArrayOf(STEREO), IntArray(0))
        )))
        val factory = FakeCandidateFactory { attempt ->
            if (attempt.candidate.origin == CandidateOrigin.DEVICE_REPORTED) null
            else FakeCaptureCandidate.Behavior(deviceFormat = deviceSide)
        }
        val audio = ByteArray(8 * 20) { (it * 7).toByte() } // 20 frames of 32-bit stereo
        val dir = tempFolder.newFolder()
        var negotiated: NegotiatedAudio? = null
        val recorder = WavRecorder(startup = ImmediateStartup, openAudioSource = {
            openNegotiatedCapture(listOf(phase), 34, true, factory, null, StartupScope(VirtualStartupTiming())).also {
                negotiated = it.negotiation
                factory.opened.last().scriptedReads += audio
            }
        })
        recorder.start(context(), WavRecorder.NextTarget { OutputTarget.FileTarget(File(dir, "take.wav").apply { createNewFile() }) },
            onSegmentStarted = {}, onAmplitude = {}, onError = {})
        val deadline = System.currentTimeMillis() + 3000
        while (factory.opened.last().scriptedReads.isNotEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(5)
        Thread.sleep(50)
        recorder.stop()

        assertEquals(CaptureMode.CONVERTED, negotiated!!.captureMode)
        assertEquals(deviceSide, recorder.sessionDeviceFormat)
        val file = File(dir, "take.wav")
        val parsed = file.inputStream().use { WavRiffParser.parse(it) }!!
        assertEquals("the header is the client format, never the device side", client, parsed.pcmFormat)
        assertArrayEquals(audio, file.readBytes().copyOfRange(parsed.dataOffset.toInt(), (parsed.dataOffset + parsed.dataSize).toInt()))
    }

    /** Counts reads; always returns 0 (no data). */
    private class EmptySource : AudioSource {
        val reads = AtomicInteger()
        override fun startRecording() {}
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int { reads.incrementAndGet(); return 0 }
        override fun stop() {}
        override fun release() {}
    }

    @Test
    fun `empty reads are paced rather than retried in a tight loop`() {
        val recordingSource = EmptySource()
        val recorder = WavRecorder(startup = ImmediateStartup, openAudioSource = { WavRecorder.RecorderConfig(recordingSource, 48000, 4096) })
        recorder.start(context(), WavRecorder.NextTarget { OutputTarget.FileTarget(tempFolder.newFile()) }, {}, {}, {})
        val testSource = EmptySource()
        val session = MicTestSession(startup = ImmediateStartup, openAudioSource = { WavRecorder.RecorderConfig(testSource, 48000, 4096) })
        session.start(context(), {}, {}, {})

        Thread.sleep(300)
        recorder.stop()
        session.stop()

        // At the 10 ms ceiling, ~300 ms allows about 30-35 reads; an unpaced loop does millions.
        for (reads in listOf(recordingSource.reads.get(), testSource.reads.get())) {
            assertTrue("$reads reads in 300 ms", reads in 5..120)
        }
    }
}
