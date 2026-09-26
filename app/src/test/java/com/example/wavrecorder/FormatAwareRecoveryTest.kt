package com.example.wavrecorder

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The crash-recovery journal and header repair for every negotiated format, alongside records and
 * files left by earlier app versions (16-bit mono, 44-byte header, no format keys).
 */
@RunWith(RobolectricTestRunner::class)
class FormatAwareRecoveryTest {

    @get:Rule
    val tempFolder = org.junit.rules.TemporaryFolder()

    private fun context() = ApplicationProvider.getApplicationContext<Context>()
    private val foreignOwner = "simulated-foreign-process"

    /** A segment exactly as WavRecorder leaves one when the process dies mid-write: its format's
     * empty header (0 audio bytes declared), then [audioBytes] of audio already on disk. */
    private fun interruptedSegment(name: String, format: PcmFormat, audioBytes: Int): File {
        val file = tempFolder.newFile(name)
        RandomAccessFile(file, "rw").use { raf ->
            val header = WavHeaderWriter.build(format, 0)
            raf.write(ByteArray(header.remaining()).also { header.get(it) })
            raf.write(ByteArray(audioBytes) { (it * 7 % 251).toByte() })
        }
        return file
    }

    private fun recoverOne(journal: ActiveSegmentJournal): RecoveryOutcome =
        WavRecoveryManager(journalFor = { journal }).recoverIfNeeded(context()).single()

    private fun assertRecovered(file: File, bytes: Long, outcome: RecoveryOutcome) {
        assertTrue("expected Recovered, was $outcome", outcome is RecoveryOutcome.Recovered)
        outcome as RecoveryOutcome.Recovered
        assertEquals(file.absolutePath, (outcome.target as OutputTarget.FileTarget).file.absolutePath)
        assertEquals(bytes, outcome.recoveredAudioBytes)
    }

    private fun seed(journal: ActiveSegmentJournal, token: String, file: File, format: PcmFormat) {
        journal.persistActiveWithOwnerForTest(token, foreignOwner, OutputTarget.FileTarget(file), format)
        journal.claimActiveAsRecoveryCandidate()
    }

    @Test
    fun `a new-format record round-trips every format field through the journal`() {
        val journal = ActiveSegmentJournal(context())
        val format = PcmFormat.of(96000, PcmEncoding.PCM_24_PACKED, 4)
        val target = OutputTarget.FileTarget(File("/tmp/x.wav"))
        journal.persistActive("t", null, target, format)

        val record = journal.peekActive()!!
        assertEquals(format, record.format)
        assertEquals(68L, record.dataOffset)
        assertEquals(24, record.bitsPerSample)
        assertEquals(false, record.isLegacy)

        // ... and survives being claimed into the recovery queue.
        journal.persistActiveWithOwnerForTest("u", foreignOwner, target, PcmFormat.of(48000, PcmEncoding.PCM_FLOAT, 2))
        journal.claimActiveAsRecoveryCandidate()
        val queued = journal.peekRecoveryCandidates().single()
        assertEquals(PcmFormat.of(48000, PcmEncoding.PCM_FLOAT, 2), queued.format)
        assertEquals(58L, queued.dataOffset)
    }

    @Test
    fun `a journal entry written by an earlier app version reads as legacy 16-bit`() {
        // Exactly the keys the previous release wrote -- no encoding/mask/offset keys at all.
        context().getSharedPreferences("wav_recorder_active_segment", Context.MODE_PRIVATE).edit()
            .putString("active_token", "old-token")
            .putString("active_target_type", "FILE")
            .putString("active_target_path", "/tmp/old.wav")
            .putInt("active_sample_rate", 48000)
            .putInt("active_channels", 1)
            .putInt("active_bits_per_sample", 16)
            .putString("active_owner_process_id", foreignOwner)
            .commit()

        val record = ActiveSegmentJournal(context()).peekActive()!!
        assertTrue(record.isLegacy)
        assertEquals(PcmFormat.pcm16Mono(48000), record.format)
        assertEquals(44L, record.dataOffset)
    }

    @Test
    fun `an unreadable encoding makes the record unreadable rather than misread`() {
        context().getSharedPreferences("wav_recorder_active_segment", Context.MODE_PRIVATE).edit()
            .putString("active_token", "t").putString("active_target_type", "FILE").putString("active_target_path", "/tmp/a.wav")
            .putInt("active_sample_rate", 48000).putInt("active_channels", 2).putInt("active_bits_per_sample", 24)
            .putString("active_encoding", "PCM_12_FROM_THE_FUTURE").putLong("active_data_offset", 68)
            .commit()
        assertNull(ActiveSegmentJournal(context()).peekActive())
    }

    @Test
    fun `a crashed 24-bit stereo segment is repaired in place with its 68-byte header`() {
        val format = PcmFormat.of(96000, PcmEncoding.PCM_24_PACKED, 2)
        val file = interruptedSegment("crash24.wav", format, audioBytes = 6 * 1000 + 4) // + a partial frame
        val audioBefore = file.readBytes().copyOfRange(68, file.length().toInt())
        val journal = ActiveSegmentJournal(context())
        seed(journal, "crash24", file, format)

        assertRecovered(file, 6000, recoverOne(journal))
        val parsed = file.inputStream().use { WavRiffParser.parse(it) }!!
        assertEquals(format, parsed.pcmFormat)
        assertEquals(6000L, parsed.dataSize)
        assertEquals(68L, parsed.dataOffset)
        assertArrayEquals("the audio itself is never touched", audioBefore, file.readBytes().copyOfRange(68, file.length().toInt()))
        assertTrue(journal.peekRecoveryCandidates().isEmpty())
    }

    @Test
    fun `a crashed float segment gets its fact chunk and sizes repaired`() {
        val format = PcmFormat.of(48000, PcmEncoding.PCM_FLOAT, 1)
        val file = interruptedSegment("crashfloat.wav", format, audioBytes = 4 * 250)
        val journal = ActiveSegmentJournal(context())
        seed(journal, "crashfloat", file, format)

        assertRecovered(file, 1000, recoverOne(journal))
        val header = file.readBytes().copyOfRange(0, 58)
        val b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(250, b.getInt(46)) // fact: sample frames
        assertEquals(1000, b.getInt(54)) // data size
    }

    @Test
    fun `odd-length 24-bit mono data counts a pad byte only if one is really there`() {
        val format = PcmFormat.of(48000, PcmEncoding.PCM_24_PACKED, 1)
        val withoutPad = interruptedSegment("nopad.wav", format, audioBytes = 9)
        val withPad = interruptedSegment("pad.wav", format, audioBytes = 10) // 3 frames + the pad byte
        val journal = ActiveSegmentJournal(context())
        seed(journal, "nopad", withoutPad, format)
        seed(journal, "pad", withPad, format)

        WavRecoveryManager(journalFor = { journal }).recoverIfNeeded(context())

        fun riff(file: File) = ByteBuffer.wrap(file.readBytes(), 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong()
        assertEquals(68L - 8 + 9, riff(withoutPad))
        assertEquals(68L - 8 + 9 + 1, riff(withPad))
        assertEquals(9L, withPad.inputStream().use { WavRiffParser.parse(it) }!!.dataSize)
    }

    @Test
    fun `a legacy record recovers a legacy file exactly as before`() {
        val file = tempFolder.newFile("legacy.wav")
        RandomAccessFile(file, "rw").use { raf ->
            val header = WavHeaderWriter.build(48000, 1, 16, 0)
            raf.write(ByteArray(header.remaining()).also { header.get(it) })
            raf.write(ByteArray(1001) { 3 })
        }
        val journal = ActiveSegmentJournal(context())
        journal.persistActiveWithOwnerForTest("legacy", foreignOwner, OutputTarget.FileTarget(file), 48000, 1, 16)
        journal.claimActiveAsRecoveryCandidate()
        assertTrue(journal.peekRecoveryCandidates().single().isLegacy)

        assertRecovered(file, 1000, recoverOne(journal))
        val expected = WavHeaderWriter.build(48000, 1, 16, 1000).let { ByteArray(it.remaining()).also { b -> it.get(b) } }
        assertArrayEquals(expected, file.readBytes().copyOfRange(0, 44))
    }

    @Test
    fun `a record whose header size doesn't match its format is refused without writing`() {
        val format = PcmFormat.of(48000, PcmEncoding.PCM_24_PACKED, 2)
        val file = interruptedSegment("mismatch.wav", format, audioBytes = 60)
        val before = file.readBytes()
        val journal = ActiveSegmentJournal(context())
        val record = ActiveSegmentJournal.Record.of("mismatch", OutputTarget.FileTarget(file), format).copy(dataOffset = 44)
        context().getSharedPreferences("wav_recorder_active_segment", Context.MODE_PRIVATE).edit()
            .putString("active_token", record.token).putString("active_target_type", "FILE")
            .putString("active_target_path", file.absolutePath)
            .putInt("active_sample_rate", record.sampleRate).putInt("active_channels", record.channels)
            .putInt("active_bits_per_sample", record.bitsPerSample).putString("active_encoding", record.encoding!!.name)
            .putInt("active_channel_mask", record.channelMask).putInt("active_channel_index_mask", 0)
            .putLong("active_data_offset", record.dataOffset).putString("active_owner_process_id", foreignOwner)
            .commit()
        journal.claimActiveAsRecoveryCandidate()

        val outcome = recoverOne(journal)

        assertTrue(outcome is RecoveryOutcome.Failed)
        assertEquals(RecoveryOutcome.Mutation.UNTOUCHED, (outcome as RecoveryOutcome.Failed).mutation)
        assertArrayEquals(before, file.readBytes())
        assertEquals(1, journal.peekRecoveryCandidates().size)
    }
}
