package bukin.wavrecorder

/**
 * What a recording may be renamed to. The user types a name without the extension; `.wav` is
 * always added, so a recording stays a recording to the Library (which lists `.wav` files only)
 * and to every app it is shared with.
 */
internal object RecordingRenameRules {

    /** Longest name, in characters, before the extension. */
    const val MAX_NAME_LENGTH = 100

    /** Most bytes a file name may take on Android's file systems. */
    private const val MAX_FILE_NAME_BYTES = 255

    private const val EXTENSION = ".wav"

    /** Characters no file name may contain on the storage this app writes to. */
    private const val FORBIDDEN_CHARACTERS = "/\\:*?\"<>|"

    /** The names this app gives new recordings. Reserved so a renamed file can never take the
     * name a recording starting at that moment is about to be created with. */
    private val RESERVED_PATTERN = Regex("""recording_\d{8}_\d{6}_part\d+""", RegexOption.IGNORE_CASE)

    sealed class Result {
        /** Rename the file to [fileName], which includes the extension. */
        data class Valid(val fileName: String) : Result()
        /** The name is the file's current one: nothing to do. */
        object Unchanged : Result()
        object Empty : Result()
        object ForbiddenCharacters : Result()
        object StartsWithDot : Result()
        object TooLong : Result()
        object Reserved : Result()
    }

    /** The name to offer for editing: the file name without its extension. */
    fun editableName(fileName: String): String =
        if (fileName.endsWith(EXTENSION, ignoreCase = true) && fileName.length > EXTENSION.length) {
            fileName.dropLast(EXTENSION.length)
        } else {
            fileName
        }

    /** Checks [input], what the user typed, as the new name for the file now called [currentFileName]. */
    fun validate(input: String, currentFileName: String): Result {
        // A typed ".wav" is the extension, not part of the name: it is added back below.
        val name = editableName(input.trim()).trim()
        if (name.isEmpty()) return Result.Empty
        if (name.any { it in FORBIDDEN_CHARACTERS || it.code < 0x20 || it.code == 0x7F }) return Result.ForbiddenCharacters
        if (name.startsWith(".")) return Result.StartsWithDot
        val fileName = name + EXTENSION
        if (fileName == currentFileName) return Result.Unchanged
        if (name.length > MAX_NAME_LENGTH || fileName.toByteArray(Charsets.UTF_8).size > MAX_FILE_NAME_BYTES) {
            return Result.TooLong
        }
        if (RESERVED_PATTERN.matches(name)) return Result.Reserved
        return Result.Valid(fileName)
    }
}
