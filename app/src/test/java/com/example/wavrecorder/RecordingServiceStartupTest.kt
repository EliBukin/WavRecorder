package com.example.wavrecorder

import android.app.Application
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * [RecordingService] while a session is STARTING -- its microphone still being negotiated on the
 * startup worker: foreground at once, cancellable, never revived by a stale result, and never
 * leaving a file behind for a start that didn't become a recording.
 */
@RunWith(RobolectricTestRunner::class)
class RecordingServiceStartupTest {

    private fun app(): Application = ApplicationProvider.getApplicationContext()

    private val worker = ManualExecutor()
    private val deliver = ManualExecutor()

    private fun settle() {
        while (worker.pending > 0 || deliver.pending > 0) {
            worker.runAll()
            deliver.runAll()
        }
    }

    private class Source(private val info: MicrophoneInfo) : AudioSource {
        var starts = 0
        var releases = 0
        override fun startRecording() { starts++ }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = 0
        override fun stop() {}
        override fun release() { releases++ }
        override fun describeMicrophone() = info
    }

    private val usbMic = MicrophoneInfo("USB Mic", isExternal = true, verified = true)
    private val phoneMic = MicrophoneInfo("Phone microphone", isExternal = false, verified = true)

    private class Events : RecordingService.Listener {
        val log = mutableListOf<String>()
        var error: Exception? = null
        override fun onAmplitude(amplitude: Float) {}
        override fun onSegmentStarted(target: OutputTarget, partNumber: Int) { log += "segment" }
        override fun onError(e: Exception) { log += "error"; error = e }
        override fun onStopping() { log += "stopping" }
        override fun onStopped(lastTarget: OutputTarget?) { log += "stopped:${lastTarget != null}" }
        override fun onMicrophoneInfo(info: MicrophoneInfo) { log += "mic:${info.label}" }
        override fun onMicrophoneReleasePending() { log += "release pending" }
        override fun onMicrophoneReleased() { log += "released" }
    }

    private fun awaitFinalizationAndIdle(service: RecordingService) {
        val deadline = System.currentTimeMillis() + 3000
        while (service.isFinalizing && System.currentTimeMillis() < deadline) Thread.sleep(5)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun serviceWith(vararg sources: Source): Pair<RecordingService, () -> Int> {
        val service = Robolectric.buildService(RecordingService::class.java).create().get()
        var opens = 0
        service.recorder = WavRecorder(
            startup = AudioStartup(worker, deliver),
            openAudioSource = { WavRecorder.RecorderConfig(sources[opens++], 48000, 4096) }
        )
        return service to { opens }
    }

    private fun recordingCount(): Int = DestinationManager(app()).listRecordings().size

    @Test
    fun `startRecording goes foreground at once and is STARTING until the microphone is ready`() {
        val source = Source(usbMic)
        val (service, _) = serviceWith(source)

        service.startRecording(1L)

        assertEquals(ServiceState.STARTING, service.state)
        assertTrue(service.isStarting)
        assertFalse(service.isRecording)
        assertNotNull("foreground before the negotiation has even run", shadowOf(service).lastForegroundNotification)
        settle()
        assertEquals(ServiceState.RECORDING, service.state)
        assertEquals(1, source.starts)
        service.stopRecording()
        awaitFinalizationAndIdle(service)
    }

    @Test
    fun `stopping while STARTING cancels the start - nothing recorded, the microphone released, the service stopped`() {
        val source = Source(usbMic)
        val (service, opens) = serviceWith(source)
        val events = Events()
        service.listener = events
        val before = recordingCount()

        service.startRecording(1L)
        worker.runAll()              // the microphone is open, on its way to the service
        service.stopRecording()
        awaitFinalizationAndIdle(service)
        settle()

        assertEquals(listOf("stopping", "stopped:false"), events.log)
        assertEquals(ServiceState.IDLE, service.state)
        assertEquals(1, opens())
        assertEquals(1, source.releases)
        assertEquals(before, recordingCount())
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun `a service destroyed while STARTING can never have that start become active afterwards`() {
        val source = Source(usbMic)
        val controller = Robolectric.buildService(RecordingService::class.java).create()
        val service = controller.get()
        service.recorder = WavRecorder(
            startup = AudioStartup(worker, deliver),
            openAudioSource = { WavRecorder.RecorderConfig(source, 48000, 4096) }
        )
        val events = Events()
        service.listener = events

        service.startRecording(1L)
        worker.runAll()
        controller.destroy()
        settle()
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(service.recorder.isActive)
        assertFalse(service.recorder.isStarting)
        assertEquals(1, source.releases)
        assertFalse("never recorded", events.log.contains("segment"))
        assertFalse(events.log.any { it.startsWith("mic:") })
    }

    @Test
    fun `destroying the service while recording releases the microphone exactly once, on the audio worker`() {
        val source = Source(usbMic)
        val controller = Robolectric.buildService(RecordingService::class.java).create()
        val service = controller.get()
        service.recorder = WavRecorder(
            startup = AudioStartup(worker, deliver),
            openAudioSource = { WavRecorder.RecorderConfig(source, 48000, 4096) }
        )
        service.startRecording(1L)
        settle()
        assertTrue(service.isRecording)

        controller.destroy()
        assertEquals("onDestroy() only hands it over; it doesn't release it on the main thread", 0, source.releases)
        settle()
        assertEquals(1, source.releases)
        settle()
        assertEquals(1, source.releases)
    }

    @Test
    fun `a normal stop releases the microphone exactly once, on the audio worker`() {
        val source = Source(usbMic)
        val (service, _) = serviceWith(source)
        service.startRecording(1L)
        settle()

        service.stopRecording()
        assertEquals(0, source.releases)
        settle()
        awaitFinalizationAndIdle(service)

        assertEquals(1, source.releases)
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun `a recording saved while Android is still releasing the microphone is reported saved, and the release separately`() {
        // Delivers a little audio, so there is a file to save (an empty segment is deleted).
        val source = object : AudioSource {
            var releases = 0
            private var reads = 0
            override fun startRecording() {}
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = if (++reads <= 5) length else 0
            override fun stop() {}
            override fun release() { releases++ }
            override fun describeMicrophone() = usbMic
        }
        val service = Robolectric.buildService(RecordingService::class.java).create().get()
        service.recorder = WavRecorder(
            threadJoinTimeoutMs = 300,
            startup = AudioStartup(worker, deliver),
            openAudioSource = { WavRecorder.RecorderConfig(source, 48000, 4096) }
        )
        val events = Events()
        service.listener = events
        service.startRecording(1L)
        settle()
        val deadline = System.currentTimeMillis() + 3000
        while (!events.log.contains("segment") && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }

        // The stop hands the microphone to the audio worker, which doesn't get to it (a stalled
        // release): the recording thread still finishes the file.
        service.stopRecording()
        awaitFinalizationAndIdle(service)

        assertEquals(listOf("stopping", "stopped:true", "release pending"), events.log.takeLast(3))
        assertTrue(service.microphoneReleasePending)
        assertEquals(0, source.releases)
        val file = (service.lastTarget as OutputTarget.FileTarget).file
        val bytes = file.readBytes()
        assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
        assertEquals("a finalized header: the RIFF size matches the file", bytes.size - 8L,
            java.nio.ByteBuffer.wrap(bytes, 4, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int.toLong())

        settle()                                    // the release finally runs
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("released", events.log.last())
        assertFalse(service.microphoneReleasePending)
        assertEquals(1, source.releases)
        assertEquals("the file's outcome was reported once, and stands", 1, events.log.count { it.startsWith("stopped") })
    }

    @Test
    fun `a second start or a late ACTION_START while STARTING changes nothing`() {
        val (service, opens) = serviceWith(Source(usbMic), Source(usbMic))

        service.startRecording(5L)
        service.startRecording(6L)
        service.onStartCommand(Intent(app(), RecordingService::class.java)
            .setAction(RecordingService.ACTION_START).putExtra(RecordingService.EXTRA_REQUEST_ID, 7L), 0, 1)

        assertEquals(5L, service.currentRequestId)
        assertFalse(service.startRequestPending)
        assertEquals(ServiceState.STARTING, service.state)
        settle()
        assertEquals("one negotiation", 1, opens())
        assertTrue(service.isRecording)
        service.stopRecording()
        awaitFinalizationAndIdle(service)
    }

    @Test
    fun `a negotiation that fails while STARTING ends the session with that error and no file`() {
        val service = Robolectric.buildService(RecordingService::class.java).create().get()
        val failure = FormatNegotiationException("no usable microphone format", null)
        service.recorder = WavRecorder(startup = AudioStartup(worker, deliver), openAudioSource = { throw failure })
        val events = Events()
        service.listener = events
        val before = recordingCount()

        service.startRecording(1L)
        settle()
        awaitFinalizationAndIdle(service)

        assertEquals(listOf("stopping", "error"), events.log)
        assertEquals(failure, events.error)
        assertEquals(before, recordingCount())
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun `an external microphone required but not verified refuses the session before it records anything`() {
        val source = Source(phoneMic)
        val (service, _) = serviceWith(source)
        val events = Events()
        service.listener = events
        val before = recordingCount()

        service.startRecording(1L, requireExternalMic = true)
        settle()
        assertFalse("never recording, not even briefly", service.isRecording)
        awaitFinalizationAndIdle(service)

        assertEquals(listOf("mic:Phone microphone", "stopping", "error"), events.log)
        assertTrue(events.error is ExternalMicrophoneNotInUseException)
        assertEquals(1, source.releases)
        assertEquals(before, recordingCount())
    }

    @Test
    fun `the external-microphone requirement holds with no screen attached, and is reported when it returns`() {
        val (service, _) = serviceWith(Source(phoneMic))

        service.startRecording(1L, requireExternalMic = true)
        settle()
        awaitFinalizationAndIdle(service)

        assertFalse(service.isRecording)
        val outcome = service.consumePendingOutcome()
        assertTrue(outcome is RecordingOutcome.Failed)
        assertEquals(app().getString(R.string.mic_mismatch_message), (outcome as RecordingOutcome.Failed).cause.message)
    }

    @Test
    fun `a verified external microphone satisfies the requirement and records`() {
        val (service, _) = serviceWith(Source(usbMic))
        val events = Events()
        service.listener = events

        service.startRecording(1L, requireExternalMic = true)
        settle()

        assertTrue(service.isRecording)
        assertNull(events.error)
        service.stopRecording()
        awaitFinalizationAndIdle(service)
    }
}
