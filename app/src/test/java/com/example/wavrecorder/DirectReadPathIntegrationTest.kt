package com.example.wavrecorder

import android.Manifest
import android.app.Application
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.AudioDeviceInfoBuilder
import org.robolectric.shadows.AudioProfileBuilder
import org.robolectric.shadows.ShadowAudioRecord
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger

/**
 * The production read path end to end, through Android's real `AudioRecord` Java code: Robolectric
 * runs the framework's own AudioRecord and simulates only its native layer -- here, a source that
 * writes native-endian samples into the direct buffer the way the platform does, and counts which
 * read path was used. A USB microphone advertising 32-bit float makes the production negotiation
 * pick float, which `AudioRecord.read(byte[])` refuses outright; only routing is stood in for
 * ([RoutedAsRequested]), since Robolectric's AudioRecord can't route.
 */
@RunWith(RobolectricTestRunner::class)
class DirectReadPathIntegrationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun app(): Application = ApplicationProvider.getApplicationContext()

    private val floatMono = PcmFormat.of(48000, PcmEncoding.PCM_FLOAT, 1)
    private val samples = floatArrayOf(-1f, 0f, 1f, 0.5f, -0.5f, 0.25f)

    /** Stands in for AudioRecord's native layer: [data] (already in the platform's native byte
     * order) written from the buffer's position, handed out once; empty reads after that. */
    private class NativeSource(data: ByteArray) : ShadowAudioRecord.AudioRecordSource {
        val directReads = AtomicInteger()
        val byteArrayReads = AtomicInteger()
        @Volatile var remaining: ByteArray = data

        override fun readInDirectBuffer(buffer: ByteBuffer, sizeInBytes: Int, isBlocking: Boolean): Int {
            directReads.incrementAndGet()
            val n = minOf(sizeInBytes, remaining.size)
            val base = buffer.position()
            for (i in 0 until n) buffer.put(base + i, remaining[i])
            remaining = remaining.copyOfRange(n, remaining.size)
            return n
        }

        override fun readInByteArray(audioData: ByteArray, offsetInBytes: Int, sizeInBytes: Int, isBlocking: Boolean): Int {
            byteArrayReads.incrementAndGet()
            return sizeInBytes
        }
    }

    private fun floats(order: ByteOrder, values: FloatArray): ByteArray =
        ByteBuffer.allocate(4 * values.size).order(order).apply { values.forEach { putFloat(it) } }.array()

    private fun install(data: ByteArray) = NativeSource(data).also { ShadowAudioRecord.setSource(it) }

    private fun awaitDrained(source: NativeSource) {
        val deadline = System.currentTimeMillis() + 3000
        while (source.remaining.isNotEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(5)
        Thread.sleep(50)
    }

    @Before
    fun setUp() {
        shadowOf(app()).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val usbMic = AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_USB_DEVICE).setProfiles(listOf(
            AudioProfileBuilder.newBuilder().setFormat(AudioFormat.ENCODING_PCM_FLOAT).setSamplingRates(intArrayOf(48000))
                .setChannelMasks(intArrayOf(AudioFormat.CHANNEL_IN_MONO)).setChannelIndexMasks(IntArray(0))
                .setEncapsulationType(0).build()
        )).build()
        shadowOf(app().getSystemService(AudioManager::class.java)).setInputDevices(listOf(usbMic))
    }

    @After
    fun tearDown() {
        ShadowAudioRecord.clearSource()
    }

    @Test
    fun `a float recording is read through the direct buffer and saved bit-exactly`() {
        val source = install(floats(ByteOrder.nativeOrder(), samples))
        val file = File(tempFolder.newFolder(), "float.wav")
        val recorder = WavRecorder(openAudioSource = routedOpenBestAudioRecord())
        var error: Exception? = null
        try {
            recorder.start(
                app(), WavRecorder.NextTarget { OutputTarget.FileTarget(file.apply { createNewFile() }) },
                onSegmentStarted = {}, onAmplitude = {}, onError = { error = it }
            )
            awaitStartupDelivered { recorder.isStarting }
            awaitDrained(source)
            shadowOf(Looper.getMainLooper()).idle()
        } finally {
            recorder.stop()
        }

        assertNull(error)
        assertEquals(floatMono, recorder.sessionFormat)
        val parsed = file.inputStream().use { WavRiffParser.parse(it) }!!
        assertEquals(floatMono, parsed.pcmFormat)
        assertTrue(parsed.isFloat)
        val saved = file.readBytes().copyOfRange(parsed.dataOffset.toInt(), (parsed.dataOffset + parsed.dataSize).toInt())
        assertArrayEquals(floats(ByteOrder.LITTLE_ENDIAN, samples), saved)
        assertTrue(source.directReads.get() > 0)
        assertEquals("the byte-array read is never used", 0, source.byteArrayReads.get())
    }

    @Test
    fun `the microphone test meters float through the same direct-buffer read path`() {
        val source = install(floats(ByteOrder.nativeOrder(), samples))
        val levels = mutableListOf<Float>()
        val session = MicTestSession(openAudioSource = routedOpenBestAudioRecord(), levelUpdateIntervalMs = 0)
        try {
            session.start(app(), onMicrophoneInfo = {}, onLevel = { levels += it }, onError = {})
            awaitStartupDelivered { session.starting }
            awaitDrained(source)
            shadowOf(Looper.getMainLooper()).idle()

            assertTrue(session.active)
            assertEquals(floatMono, session.format)
            assertEquals("the -1/+1 samples read at full scale", 1f, levels.maxOrNull())
        } finally {
            session.stop()
        }
        assertTrue(source.directReads.get() > 0)
        assertEquals(0, source.byteArrayReads.get())
    }

    @Test
    fun `by default, recording and the microphone test both read through the direct buffer`() {
        // No seams at all: no input devices, so both negotiate the phone mic's 16-bit fallback.
        shadowOf(app().getSystemService(AudioManager::class.java)).setInputDevices(emptyList())
        val pcm = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putShort(-32768).putShort(0).putShort(32767).putShort(1).array()

        val recordingSource = install(pcm)
        val file = File(tempFolder.newFolder(), "pcm16.wav")
        val recorder = WavRecorder()
        try {
            recorder.start(app(), WavRecorder.NextTarget { OutputTarget.FileTarget(file.apply { createNewFile() }) }, {}, {}, {})
            awaitStartupDelivered { recorder.isStarting }
            awaitDrained(recordingSource)
        } finally {
            recorder.stop()
        }
        val parsed = file.inputStream().use { WavRiffParser.parse(it) }!!
        assertArrayEquals(
            ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putShort(-32768).putShort(0).putShort(32767).putShort(1).array(),
            file.readBytes().copyOfRange(parsed.dataOffset.toInt(), (parsed.dataOffset + parsed.dataSize).toInt())
        )

        val testSource = install(pcm)
        val session = MicTestSession()
        try {
            session.start(app(), {}, {}, {})
            awaitStartupDelivered { session.starting }
            awaitDrained(testSource)
            assertEquals(recorder.sessionFormat, session.format)
        } finally {
            session.stop()
        }

        for (source in listOf(recordingSource, testSource)) {
            assertTrue(source.directReads.get() > 0)
            assertEquals(0, source.byteArrayReads.get())
        }
    }

    @Test
    fun `the framework's byte-array read refuses float, which is why it is never used`() {
        val source = install(floats(ByteOrder.nativeOrder(), samples))
        val record = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(48000)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
            .setBufferSizeInBytes(4096)
            .build()
        try {
            assertEquals(AudioRecord.ERROR_INVALID_OPERATION, record.read(ByteArray(64), 0, 64))
            assertEquals("refused before reaching the audio layer", 0, source.byteArrayReads.get())
            assertEquals(24, record.read(ByteBuffer.allocateDirect(64), 64, AudioRecord.READ_BLOCKING))
        } finally {
            record.release()
        }
    }
}
