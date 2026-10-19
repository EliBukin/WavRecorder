package bukin.wavrecorder

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingRenameRulesTest {

    private val current = "recording_20261019_115500_part01.wav"

    private fun check(input: String) = RecordingRenameRules.validate(input, current)

    @Test
    fun `a plain name gets the wav extension added`() {
        assertEquals(RecordingRenameRules.Result.Valid("Band practice.wav"), check("Band practice"))
    }

    @Test
    fun `a typed extension isn't doubled, and surrounding spaces are dropped`() {
        assertEquals(RecordingRenameRules.Result.Valid("Interview.wav"), check("  Interview.wav "))
        assertEquals(RecordingRenameRules.Result.Valid("Interview.wav"), check("Interview.WAV"))
    }

    @Test
    fun `names in other scripts are accepted`() {
        assertEquals(RecordingRenameRules.Result.Valid("חזרה 1.wav"), check("חזרה 1"))
    }

    @Test
    fun `an empty or blank name is refused`() {
        assertEquals(RecordingRenameRules.Result.Empty, check(""))
        assertEquals(RecordingRenameRules.Result.Empty, check("   "))
    }

    @Test
    fun `characters file systems reject are refused, so a name can never be a path`() {
        for (c in listOf("/", "\\", ":", "*", "?", "\"", "<", ">", "|", "\u0000", "\n", "\u007F")) {
            assertEquals("'$c'", RecordingRenameRules.Result.ForbiddenCharacters, check("a${c}b"))
        }
        assertEquals(RecordingRenameRules.Result.ForbiddenCharacters, check("../escape"))
    }

    @Test
    fun `a name starting with a dot is refused`() {
        assertEquals(RecordingRenameRules.Result.StartsWithDot, check(".hidden"))
        assertEquals(RecordingRenameRules.Result.StartsWithDot, check(".wav"))
    }

    @Test
    fun `a name over the length limit is refused, in characters and in bytes`() {
        val longest = "a".repeat(RecordingRenameRules.MAX_NAME_LENGTH)
        assertEquals(RecordingRenameRules.Result.Valid("$longest.wav"), check(longest))
        assertEquals(RecordingRenameRules.Result.TooLong, check(longest + "a"))
        // 90 three-byte characters: within the character limit, past the 255-byte file-name limit.
        assertEquals(RecordingRenameRules.Result.TooLong, check("音".repeat(90)))
    }

    @Test
    fun `the names new recordings are given are reserved`() {
        assertEquals(RecordingRenameRules.Result.Reserved, check("recording_20261019_120000_part01"))
        assertEquals(RecordingRenameRules.Result.Reserved, check("RECORDING_20261019_120000_PART2"))
        assertEquals(RecordingRenameRules.Result.Valid("recording_tuesday.wav"), check("recording_tuesday"))
    }

    @Test
    fun `the current name is no change, even for a reserved one`() {
        assertEquals(RecordingRenameRules.Result.Unchanged, check("recording_20261019_115500_part01"))
        assertEquals(RecordingRenameRules.Result.Unchanged, RecordingRenameRules.validate("Talk", "Talk.wav"))
    }

    @Test
    fun `the editable name is the file name without its extension`() {
        assertEquals("recording_20261019_115500_part01", RecordingRenameRules.editableName(current))
        assertEquals("Talk", RecordingRenameRules.editableName("Talk.WAV"))
        assertEquals("notes.txt", RecordingRenameRules.editableName("notes.txt"))
        assertEquals(".wav", RecordingRenameRules.editableName(".wav"))
    }

    @Test
    fun `a renamed recording's title is its name, without the extension`() {
        assertEquals("Band practice", RecordingNameFormatter.friendlyTitle("Band practice.wav"))
    }
}
