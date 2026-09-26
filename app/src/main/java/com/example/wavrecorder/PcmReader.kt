package com.example.wavrecorder

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The production read path for captured audio, identical for every [PcmEncoding]: 16-bit, packed
 * 24-bit, 32-bit integer and 32-bit float. Pure JVM -- [readInto] is the one call into Android
 * (`AudioRecord.read(ByteBuffer, Int, READ_BLOCKING)`), so the byte handling is unit-tested directly.
 *
 * Why a direct ByteBuffer rather than `AudioRecord.read(byte[], ...)`: the byte-array overload is
 * refused for `ENCODING_PCM_FLOAT` (it returns ERROR_INVALID_OPERATION without reading anything),
 * while the direct-buffer overload returns the raw samples of whatever encoding the AudioRecord was
 * built with -- one path, no per-encoding typed arrays, no conversion. Samples arrive unchanged in
 * the platform's native byte order; WAV is little-endian, which every Android ABI already is, and
 * [swapSamplesToLittleEndian] makes that a guarantee rather than an assumption.
 *
 * The direct buffer is allocated once, on the first read (so a negotiation candidate that is never
 * read from never allocates one), and reused for every read after that. Not thread-safe: a capture
 * loop reads from a single thread.
 */
internal class DirectPcmReader(
    private val format: PcmFormat,
    capacityBytes: Int,
    /** Byte order the platform writes samples in -- [ByteOrder.nativeOrder]; a parameter only so
     * the big-endian conversion can be tested on a little-endian machine. */
    private val nativeOrder: ByteOrder = ByteOrder.nativeOrder(),
    /** Reads up to the given number of bytes into the start of the given direct buffer, returning
     * the count read (0 or more), or a negative AudioRecord error code. */
    private val readInto: (ByteBuffer, Int) -> Int
) {
    /** Whole frames only (never less than one), so every request is frame-aligned as AudioRecord
     * recommends. */
    val capacity: Int = maxOf(format.bytesPerFrame, capacityBytes - capacityBytes % format.bytesPerFrame)

    private var buffer: ByteBuffer? = null

    /** The one direct buffer this reader uses, once allocated; for tests. */
    internal val directBuffer: ByteBuffer? get() = buffer

    /**
     * Reads up to [length] bytes (rounded down to whole frames, and at most [capacity]) into
     * `destination[offset ...]`, as little-endian samples. Returns the byte count copied, 0 when
     * nothing was available, or AudioRecord's negative error code unchanged -- in which case
     * [destination] is left untouched.
     */
    fun read(destination: ByteArray, offset: Int, length: Int): Int {
        require(offset >= 0 && length >= 0 && offset <= destination.size - length) {
            "Invalid read of $length bytes at $offset into ${destination.size}"
        }
        val requested = format.alignToFrame(minOf(length, capacity).toLong()).toInt()
        if (requested == 0) return 0
        val direct = buffer ?: ByteBuffer.allocateDirect(capacity).order(nativeOrder).also { buffer = it }
        // AudioRecord writes from the start of the buffer (and leaves its position unchanged), so
        // position 0 makes "the start" and "the position" the same place either way.
        direct.clear()
        val read = readInto(direct, requested)
        if (read <= 0) return read
        check(read <= requested) { "AudioRecord returned $read bytes for a $requested-byte read" }
        direct.clear()
        direct.get(destination, offset, read)
        if (nativeOrder != ByteOrder.LITTLE_ENDIAN) {
            swapSamplesToLittleEndian(destination, offset, read, format.bytesPerSample)
        }
        return read
    }
}

/** Reverses the bytes of every whole [sampleBytes]-byte sample in `buffer[offset, offset + length)`
 * in place: big-endian samples become the little-endian samples WAV stores, bit for bit. */
internal fun swapSamplesToLittleEndian(buffer: ByteArray, offset: Int, length: Int, sampleBytes: Int) {
    if (sampleBytes <= 1) return
    var start = offset
    val end = offset + length - length % sampleBytes
    while (start < end) {
        var i = start
        var j = start + sampleBytes - 1
        while (i < j) {
            val t = buffer[i]
            buffer[i] = buffer[j]
            buffer[j] = t
            i++
            j--
        }
        start += sampleBytes
    }
}

/**
 * Paces a capture loop through consecutive empty (0-byte) reads. A blocking AudioRecord read does
 * not normally return 0, but a source that ever did so repeatedly would otherwise be retried
 * immediately, spinning a CPU core flat out for as long as it lasted. Waits 1, 2, 4, 8, then
 * [MAX_DELAY_MS] ms per consecutive empty read -- far shorter than the ~100 ms capture buffer, so
 * audio arriving again is never lost to the wait -- and starts over after the next read with data.
 */
internal class EmptyReadBackoff(private val sleep: (Long) -> Unit = { Thread.sleep(it) }) {
    private var consecutive = 0

    fun onData() {
        consecutive = 0
    }

    /** Waits before the next read. Throws InterruptedException if the thread is interrupted. */
    fun onEmpty() {
        consecutive++
        sleep(delayMs(consecutive))
    }

    companion object {
        const val MAX_DELAY_MS = 10L

        fun delayMs(consecutive: Int): Long = minOf(1L shl (consecutive.coerceIn(1, 5) - 1), MAX_DELAY_MS)
    }
}
