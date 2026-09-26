package com.example.wavrecorder

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** WavRecorder writing negotiated (non-16-bit-mono) formats: headers, whole frames, pad bytes,
 * journal records, levels, and the WAV-size-limit rollover. */
@RunWith(RobolectricTestRunner::class)
class WavRecorderFormatTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun context() = ApplicationProvider.getApplicationContext<Context>()

    private fun awaitStop(recorder: WavRecorder, done: CountDownLatch) {
        assertTrue(done.await(3, TimeUnit.SECONDS))
        Thread.sleep(30)
        // Deliver posted callbacks (levels) before stop(), which deliberately discards any still queued.
        shadowOf(Looper.getMainLooper()).idle()
        recorder.stop()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun int24(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte())

    /** Records [reads] in [format] into fresh files; returns the files that remain. */
    private fun record(
        format: PcmFormat,
        reads: List<ByteArray>,
        bufferSize: Int = 4096,
        journal: ActiveSegmentJournal = ActiveSegmentJournal(context()),
        splitPlan: ((PcmFormat, Long, Int) -> SegmentSplitPlan)? = null,
        onAmplitude: (Float) -> Unit = {},
        recorderOut: (WavRecorder) -> Unit = {}
    ): List<File> {
        val done = CountDownLatch(1)
        val fake = FakeAudioSource(scriptedReads = reads, onExhausted = { done.countDown() })
        val dir = tempFolder.newFolder()
        val files = mutableListOf<File>()
        val recorder = if (splitPlan != null) {
            WavRecorder(startup = ImmediateStartup, openAudioSource = { WavRecorder.RecorderConfig(fake, format, bufferSize) }, journalFor = { journal }, splitPlanFor = splitPlan)
        } else {
            WavRecorder(startup = ImmediateStartup, openAudioSource = { WavRecorder.RecorderConfig(fake, format, bufferSize) }, journalFor = { journal })
        }
        recorderOut(recorder)
        recorder.start(
            context(),
            WavRecorder.NextTarget { OutputTarget.FileTarget(File(dir, "part${files.size + 1}.wav").also { it.createNewFile(); files += it }) },
            onSegmentStarted = {}, onAmplitude = onAmplitude, onError = {}
        )
        awaitStop(recorder, done)
        shadowOf(Looper.getMainLooper()).idle()
        return files.filter { it.exists() }
    }

    private fun parse(file: File) = file.inputStream().use { WavRiffParser.parse(it) }!!
    private fun audioOf(file: File): ByteArray = parse(file).let { f ->
        file.readBytes().copyOfRange(f.dataOffset.toInt(), (f.dataOffset + f.dataSize).toInt())
    }

    @Test
    fun `a 24-bit stereo session writes a spec-correct extensible WAV with the exact audio`() {
        val format = PcmFormat.of(96000, PcmEncoding.PCM_24_PACKED, 2)
        val frames = (0 until 50).flatMap { listOf(int24(it * 1000), int24(-it * 1000)) }.fold(ByteArray(0)) { a, b -> a + b }
        val file = record(format, listOf(frames.copyOfRange(0, 150), frames.copyOfRange(150, 300))).single()

        val parsed = parse(file)
        assertEquals(format, parsed.pcmFormat)
        assertEquals(68L, parsed.dataOffset)
        assertEquals(300L, parsed.dataSize)
        assertArrayEquals(frames, audioOf(file))
    }

    @Test
    fun `reads that end mid-frame are carried over so only whole frames are written`() {
        val format = PcmFormat.of(48000, PcmEncoding.PCM_24_PACKED, 2) // 6-byte frames
        val audio = ByteArray(6 * 7) { (it + 1).toByte() }
        // 5, then 11, then 26 bytes: none of the boundaries fall on a frame edge.
        val file = record(format, listOf(audio.copyOfRange(0, 5), audio.copyOfRange(5, 16), audio.copyOfRange(16, 42))).single()
        assertArrayEquals(audio, audioOf(file))
    }

    @Test
    fun `an incomplete final frame is never written`() {
        val format = PcmFormat.of(48000, PcmEncoding.PCM_24_PACKED, 2)
        val file = record(format, listOf(ByteArray(6 * 3 + 4) { 9 })).single()
        assertEquals(18L, parse(file).dataSize)
    }

    @Test
    fun `odd-length data gets its RIFF pad byte when the file is finalized`() {
        val format = PcmFormat.of(48000, PcmEncoding.PCM_24_PACKED, 1)
        val file = record(format, listOf(ByteArray(9) { 1 })).single() // 3 frames = 9 bytes
        assertEquals(68L + 9 + 1, file.length())
        assertEquals(0.toByte(), file.readBytes().last())
        val riff = ByteBuffer.wrap(file.readBytes(), 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong()
        assertEquals(file.length() - 8, riff)
        assertEquals(9L, parse(file).dataSize)
    }

    @Test
    fun `float and multichannel sessions round-trip`() {
        for (format in listOf(PcmFormat.of(48000, PcmEncoding.PCM_FLOAT, 1), PcmFormat.of(44100, PcmEncoding.PCM_32, 4))) {
            val audio = ByteArray(format.bytesPerFrame * 20) { (it * 3).toByte() }
            val file = record(format, listOf(audio)).single()
            assertEquals(format, parse(file).pcmFormat)
            assertArrayEquals("$format", audio, audioOf(file))
        }
    }

    @Test
    fun `the journal records the session's full format while a segment is open`() {
        val format = PcmFormat.of(96000, PcmEncoding.PCM_24_PACKED, 2)
        var seen: ActiveSegmentJournal.Record? = null
        val journal = object : ActiveSegmentJournal(context()) {
            override fun persistActive(token: String, expectedPreviousToken: String?, target: OutputTarget, format: PcmFormat): Boolean =
                super.persistActive(token, expectedPreviousToken, target, format).also { seen = peekActive() }
        }
        record(format, listOf(ByteArray(60)), journal = journal)
        assertEquals(format, seen?.format)
        assertEquals(68L, seen?.dataOffset)
    }

    @Test
    fun `the live level reads full scale for a full-scale 24-bit sample`() {
        val format = PcmFormat.of(48000, PcmEncoding.PCM_24_PACKED, 1)
        val levels = mutableListOf<Float>()
        record(format, listOf(int24(0x7FFFFF) + int24(0) + int24(-5)), onAmplitude = { levels += it })
        assertEquals(1f, levels.maxOrNull()!!, 0f)
    }

    @Test
    fun `the session format and split plan are exposed and fixed for the session`() {
        val format = PcmFormat.of(192000, PcmEncoding.PCM_32, 2)
        var recorder: WavRecorder? = null
        record(format, listOf(ByteArray(16)), recorderOut = { recorder = it })
        assertEquals(format, recorder!!.sessionFormat)
        val plan = recorder!!.sessionSplitPlan!!
        assertEquals(format, plan.format)
        assertTrue("192 kHz / 32-bit stereo outgrows a WAV file within the default 60 minutes", plan.limitedByRiff)
    }

    @Test
    fun `hitting the WAV size limit before the chosen duration starts a new file and says so`() {
        val format = PcmFormat.of(48000, PcmEncoding.PCM_24_PACKED, 2)
        var recorder: WavRecorder? = null
        // Pretend a WAV file can only safely hold 3 frames (the chosen duration is far longer).
        val files = record(
            format, listOf(ByteArray(18) { 1 }, ByteArray(18) { 2 }, ByteArray(6) { 3 }),
            splitPlan = { f, seconds, _ -> SegmentSplitPlan(f, f.bytesForSeconds(seconds), riffLimitBytes = 18) },
            recorderOut = { recorder = it }
        )
        assertEquals(3, files.size)
        assertEquals(listOf(18L, 18L, 6L), files.map { parse(it).dataSize })
        assertEquals(2, recorder!!.riffLimitedSplits)
        assertTrue(recorder!!.sessionSplitPlan!!.limitedByRiff)
    }

    @Test
    fun `no write can ever take a segment past what its header can describe`() {
        val format = PcmFormat.of(48000, PcmEncoding.PCM_16, 1)
        // A (pathological) plan whose threshold is beyond the representable ceiling: the pre-write
        // guard must still split first.
        val files = record(
            format, listOf(ByteArray(8) { 1 }, ByteArray(8) { 2 }, ByteArray(8) { 3 }),
            splitPlan = { f, _, _ -> SegmentSplitPlan(f, userLimitBytes = 1000, riffLimitBytes = 1000, hardLimitBytes = 16) }
        )
        assertTrue(files.all { parse(it).dataSize <= 16 })
        assertEquals(24L, files.sumOf { parse(it).dataSize })
    }

    @Test
    fun `a disconnect during a high-resolution session still leaves a valid, finalized file`() {
        val format = PcmFormat.of(96000, PcmEncoding.PCM_24_PACKED, 2)
        var connected = true
        var reads = 0
        val fake = object : AudioSource {
            override fun startRecording() {}
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                Thread.sleep(2)
                if (++reads == 3) connected = false
                ByteArray(60) { 5 }.copyInto(buffer, offset)
                return 60
            }
            override fun stop() {}
            override fun release() {}
            override fun describeMicrophone() = MicrophoneInfo("USB", isExternal = true, verified = true)
            override fun isDeviceConnected() = connected
        }
        val file = tempFolder.newFile("dc.wav")
        var error: Exception? = null
        val recorder = WavRecorder(startup = ImmediateStartup,
            headerFlushIntervalMs = 0,
            openAudioSource = { WavRecorder.RecorderConfig(fake, format, 60) }
        )
        recorder.start(context(), WavRecorder.NextTarget { OutputTarget.FileTarget(file) }, {}, {}, onError = { error = it })
        val deadline = System.currentTimeMillis() + 3000
        while (recorder.isActive && System.currentTimeMillis() < deadline) Thread.sleep(5)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(error is MicrophoneDisconnectedException)
        assertEquals(WavRecorder.FinalizeResult.Ok, recorder.stop())
        val parsed = parse(file)
        assertEquals(format, parsed.pcmFormat)
        assertEquals(0L, parsed.dataSize % format.bytesPerFrame)
        assertFalse(parsed.dataSize == 0L)
        assertNotNull(parsed)
    }
}
