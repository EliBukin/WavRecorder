package com.example.wavrecorder

import android.net.Uri
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Which recording files are in use right now -- being captured to, finalized, or repaired -- and
 * therefore must not be deleted. The one authority that everything writing a recording
 * ([WavRecorder], [WavRecoveryManager]) and everything deleting one ([DestinationManager], and
 * through it the Library) goes through.
 *
 * Each file is either free, claimed (for a [Use]), or being deleted. Claiming and starting a
 * delete are decided atomically, under one lock -- but that lock only ever guards these state
 * changes, never storage or provider I/O, so a slow delete or file creation holds up nothing but
 * itself:
 *  - A delete marks the file as being deleted (refused if it is claimed), deletes it outside the
 *    lock, then clears the mark. A claim on a file being deleted is refused.
 *  - A new segment's file is created outside the lock, then claimed, then checked to still exist:
 *    a delete that won the race -- one that started, or even finished, between the file being
 *    created and claimed -- makes the writer create another file instead, so it never writes into
 *    a file the user deleted ([createClaimed]). Once claimed, nothing can delete it.
 *  - [useOf], which the Library calls while binding rows on the main thread, reads a published
 *    snapshot without taking the lock at all.
 * A completed segment's claim is released once it is finalized, so earlier parts of a recording
 * stay deletable while it continues.
 *
 * Process-wide by design: recording, recovery and the Library all run in this app's one process.
 * Claims are in memory only; after a process death nothing is being written, and the recovery
 * journal takes over (see [WavRecoveryManager]).
 */
internal object RecordingOwnership {

    /** What a claimed file is being used for. */
    enum class Use { CAPTURING, FINALIZING, RECOVERING }

    /** A held claim on one file; [release] (idempotent) makes it deletable again. */
    class Claim internal constructor(val key: String, use: Use) {
        @Volatile var use: Use = use
            private set

        @Volatile var released = false
            internal set

        /** Capture has ended and the file is being finalized: still in use. */
        fun markFinalizing() {
            synchronized(lock) {
                if (released) return
                use = Use.FINALIZING
                publishLocked()
            }
            notifyChanged()
        }

        fun release() {
            synchronized(lock) {
                if (released) return
                released = true
                if (claims[key] === this) claims.remove(key)
                publishLocked()
            }
            notifyChanged()
        }
    }

    /** Most files [createClaimed] makes before giving up, should deletes keep winning the race. */
    private const val MAX_CREATE_ATTEMPTS = 3

    private val lock = Any()
    // Guarded by [lock]; never touched while doing storage I/O.
    private val claims = HashMap<String, Claim>()
    private val deleting = HashMap<String, Int>() // key -> deletes in progress
    // What useOf() reads: replaced (never mutated) under the lock on every change.
    @Volatile private var published: Map<String, Use> = emptyMap()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /** The identity a claim is keyed by: an app-storage file's absolute path as a file URI, or a
     * document URI as reported (the same string the folder listing yields for it). No I/O. */
    fun keyOf(target: OutputTarget): String = when (target) {
        is OutputTarget.FileTarget -> fileKey(target.file)
        is OutputTarget.SafTarget -> target.uri.toString()
    }

    fun keyOf(uri: Uri): String {
        val path = uri.path
        return if (uri.scheme == "file" && path != null) fileKey(File(path)) else uri.toString()
    }

    private fun fileKey(file: File): String = Uri.fromFile(file.absoluteFile).toString()

    /**
     * Creates a target with [create] and claims it for [use], guaranteeing it is claimed while it
     * still exists: [create] runs outside the lock (it may be slow storage or provider I/O); the new
     * file is then claimed -- refused if a delete of it is under way -- and [exists] is checked
     * right after creation and again after the claim, so a delete that removed it in between is
     * caught. If either happens, another file is created (up to [MAX_CREATE_ATTEMPTS]), so the
     * caller never writes into a file that was deleted. A target that doesn't exist yet when
     * created (one only created when first opened) can't have been listed, so no delete can have
     * removed it. Throws [IOException] if no file survives.
     */
    fun <T : OutputTarget> createClaimed(use: Use, exists: (T) -> Boolean, create: () -> T): Pair<T, Claim> {
        repeat(MAX_CREATE_ATTEMPTS) {
            val target = create()
            val existedWhenCreated = exists(target)
            val claim = tryClaim(target, use)
            if (claim != null) {
                if (!existedWhenCreated || exists(target)) return target to claim
                claim.release() // deleted before it could be claimed
            }
        }
        throw IOException("A new recording file was deleted as soon as it was created, $MAX_CREATE_ATTEMPTS times")
    }

    /** Claims an existing [target] for [use], or returns null if it is already claimed or being deleted. */
    fun tryClaim(target: OutputTarget, use: Use): Claim? {
        val claim = synchronized(lock) {
            val key = keyOf(target)
            if (claims.containsKey(key) || deleting.containsKey(key)) return null
            Claim(key, use).also {
                claims[key] = it
                publishLocked()
            }
        }
        notifyChanged()
        return claim
    }

    /** What [uri] is in use for, or null when it isn't. Never blocks: reads a published snapshot. */
    fun useOf(uri: Uri): Use? = published[keyOf(uri)]

    /**
     * Runs [delete] for [uri] unless it is in use. Starting the delete is decided under the lock
     * (refused if the file is claimed); [delete] itself runs outside it, with the file marked as
     * being deleted so that nothing can claim it meanwhile. Returns [delete]'s result, or null when
     * the file is in use and was left alone.
     */
    fun <R> deleteUnlessInUse(uri: Uri, delete: () -> R): R? {
        val key = keyOf(uri)
        synchronized(lock) {
            if (claims.containsKey(key)) return null
            deleting[key] = (deleting[key] ?: 0) + 1
        }
        try {
            return delete()
        } finally {
            synchronized(lock) {
                val remaining = (deleting[key] ?: 1) - 1
                if (remaining > 0) deleting[key] = remaining else deleting.remove(key)
            }
        }
    }

    /** Called (on whichever thread changed it) whenever a file is claimed, changes use or is
     * released -- e.g. so the Library can relabel its rows. */
    fun addChangeListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeChangeListener(listener: () -> Unit) {
        listeners -= listener
    }

    /** How many listeners are registered: tests only, to check a screen didn't outlive its test. */
    internal val listenerCount: Int get() = listeners.size

    private fun publishLocked() {
        published = claims.mapValues { it.value.use }
    }

    private fun notifyChanged() {
        listeners.forEach { listener ->
            try { listener() } catch (_: Exception) {}
        }
    }

    /** Drops every claim: tests only (a test that deliberately abandons a writer leaves its
     * claim). Each dropped claim is marked released, so a late release() from it is a no-op. */
    internal fun resetForTest() {
        synchronized(lock) {
            claims.values.forEach { it.released = true }
            claims.clear()
            deleting.clear()
            publishLocked()
        }
    }

    /** Tests only: what an app process's death leaves of this -- nothing, as a new process starts
     * with no claims, deletes or listeners. (Not part of [resetForTest], which tests call after
     * every run: a screen still registered then is a leak, not something to clear.) */
    internal fun simulateProcessDeathForTest() {
        resetForTest()
        listeners.clear()
    }
}
