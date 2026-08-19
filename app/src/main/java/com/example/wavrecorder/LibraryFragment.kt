package com.example.wavrecorder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.wavrecorder.databinding.DialogStatsBinding
import com.example.wavrecorder.databinding.FragmentLibraryBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.lang.ref.WeakReference
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

class LibraryFragment : Fragment() {

    private var _binding: FragmentLibraryBinding? = null
    private val binding get() = _binding!!

    // internal + var (rather than private/val) so a test can substitute a DestinationManager
    // subclass that fails on command, e.g. simulating a revoked SAF permission.
    internal lateinit var destinationManager: DestinationManager
    private lateinit var adapter: RecordingsAdapter

    private val mediaPlayerFactory: () -> MediaPlayer = { MediaPlayer() }

    /** The currently loaded MediaPlayer, if any. It stays alive across pause/resume. */
    private var mediaPlayer: MediaPlayer? = null
    private var activeUri: Uri? = null
    private var activeDurationMs = 0
    private var currentSpeed = 1.0f
    // True from the moment prepareAsync() is called until onPrepared/onError fires. MediaPlayer
    // throws IllegalStateException for start()/pause()/seekTo()/playbackParams while still in its
    // Preparing state, so every control path that isn't the initial playNew() call must check
    // this and no-op instead of touching the player.
    private var isPreparingPlayback = false
    // Set when a focus loss (of either kind) arrives while still Preparing: onPrepared checks this
    // instead of unconditionally auto-starting, so a track that lost focus before it ever finished
    // preparing doesn't start playing anyway. Cleared by onPrepared itself, or by a focus-gain that
    // arrives before preparation finishes (see audioFocusListener).
    private var suppressAutoStartOnPrepared = false

    // Bumped on every refreshList() call; a scan result only gets applied if it's still the most
    // recent one requested. Kept as a *secondary* guard alongside the Job-cancellation coalescing
    // below (see refreshJob) -- listRecordings() itself is a plain blocking call, not a suspending
    // one, so cancelling its Job can't interrupt it mid-call; this is what still guarantees a
    // result that happens to complete late is never applied.
    private val refreshRequestId = AtomicInteger(0)

    // The currently in-flight (or most recently launched) refreshList() coroutine, if any --
    // cancelled at the top of every new refreshList() call so a rapid burst of requests (e.g.
    // onResume() firing repeatedly) coalesces to "only the newest one matters" instead of
    // accumulating unbounded background work. Both this Job and showStats()'s own per-call Job are
    // launched via viewLifecycleOwner.lifecycleScope, which is itself cancelled the moment this
    // Fragment's view is destroyed -- see onDestroyView() -- so queued/running work is genuinely
    // cancelled there too, not merely left to finish and have its result discarded.
    private var refreshJob: Job? = null

    private val progressHandler = Handler(Looper.getMainLooper())
    private val progressTick = object : Runnable {
        override fun run() {
            activeUri?.let { adapter.notifyProgressChanged(it) }
            progressHandler.postDelayed(this, 200)
        }
    }

    companion object {
        private val SPEED_STEPS = floatArrayOf(1.0f, 1.25f, 1.5f, 2.0f)
    }

    private lateinit var audioManager: AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var resumeOnFocusGain = false

    // Android documents isPlaying()/pause() (and most other MediaPlayer control calls) as
    // undefined while still in the Preparing state, which prepareAsync() leaves the player in
    // until onPrepared/onError fires -- a focus-change or ACTION_AUDIO_BECOMING_NOISY broadcast
    // can land at any moment, including squarely inside that window, so every branch below is
    // written to never touch isPlaying()/pause() while isPreparingPlayback is still true.
    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                resumeOnFocusGain = false
                if (isPreparingPlayback) {
                    // Permanent loss: cancel the auto-start outright rather than start playback
                    // into a focus state that's already gone, or leave it to time out on its own.
                    suppressAutoStartOnPrepared = true
                } else {
                    pauseActive()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (isPreparingPlayback) {
                    // Transient: don't auto-start once ready either, but remember to once focus
                    // actually comes back (below), the same way an already-playing track would.
                    suppressAutoStartOnPrepared = true
                    resumeOnFocusGain = true
                } else {
                    resumeOnFocusGain = mediaPlayer?.isPlaying == true
                    pauseActive()
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (resumeOnFocusGain) {
                    resumeOnFocusGain = false
                    if (isPreparingPlayback) {
                        // Still not ready: let onPrepared itself start it once it is, instead of
                        // touching a player that's still Preparing.
                        suppressAutoStartOnPrepared = false
                    } else {
                        resumeActive()
                    }
                }
            }
        }
    }

    /** Wired headphones pulled out mid-playback would otherwise blast audio through the speaker. */
    private val becomingNoisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!isPreparingPlayback && mediaPlayer?.isPlaying == true) pauseActive()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLibraryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        destinationManager = DestinationManager(requireContext())
        audioManager = requireContext().getSystemService(Context.AUDIO_SERVICE) as AudioManager
        requireContext().registerReceiver(
            becomingNoisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        )
        adapter = RecordingsAdapter(
            onPlayPause = { item -> togglePlayPause(item) },
            onSeekTo = { item, ms -> if (item.uri == activeUri) seekActiveTo(ms) },
            onSpeedToggle = { item -> if (item.uri == activeUri) cycleSpeed() },
            onShowStats = { item -> showStats(item) },
            onDelete = { item -> confirmDelete(item) },
            isActive = { uri -> uri == activeUri },
            isPlaying = { uri -> uri == activeUri && mediaPlayer?.isPlaying == true },
            isPreparing = { uri -> uri == activeUri && isPreparingPlayback },
            playbackPositionMs = { uri -> if (uri == activeUri) (mediaPlayer?.currentPosition ?: 0) else 0 },
            playbackDurationMs = { uri -> if (uri == activeUri) activeDurationMs else 0 },
            playbackSpeedLabel = { uri -> if (uri == activeUri) formatSpeed(currentSpeed) else formatSpeed(1.0f) }
        )
        binding.recordingsList.layoutManager = LinearLayoutManager(requireContext())
        binding.recordingsList.adapter = adapter
    }

    private fun formatSpeed(speed: Float): String {
        val text = if (speed == speed.toLong().toFloat()) {
            String.format(Locale.US, "%.1f", speed)
        } else {
            String.format(Locale.US, "%.2f", speed).trimEnd('0')
        }
        return "${text}x"
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    // internal for testing: a test swaps in a DestinationManager that throws, then calls this
    // directly to exercise the failure path without waiting on the fragment's own onResume().
    internal fun refreshList() {
        // Guards viewLifecycleOwner below, which throws IllegalStateException with no view --
        // production never calls this without one (onResume() can't fire before onViewCreated(),
        // and the one other caller, deleteRecording(), already checks _binding itself first), but
        // this keeps the same graceful no-op safety the old backgroundExecutor?.execute() seam had
        // for any caller (a test included) that does.
        if (_binding == null) return
        // Listing a SAF-picked folder walks DocumentFile, which is one Binder round-trip to the
        // storage provider per file (name, mime type, last-modified are each a separate query) -
        // enough to visibly stutter the tab on a slower provider, so this runs off the main thread.
        // Captures only the manager reference (not `this`/the fragment/any view) so a slow or
        // failing scan can't keep the fragment's view tree alive from a background thread.
        val manager = destinationManager
        val appContext = requireContext().applicationContext
        val requestId = refreshRequestId.incrementAndGet()
        // Coalesce: a rapid burst of refresh requests (e.g. onResume() firing repeatedly) cancels
        // whatever was previously in flight/queued rather than piling up unbounded background
        // work -- only the newest request is ever allowed to actually apply a result.
        refreshJob?.cancel()
        refreshJob = viewLifecycleOwner.lifecycleScope.launch {
            // runInterruptible (not plain withContext) so cancelling this Job also interrupts the
            // underlying thread -- listRecordings() and the per-file interruption check it now
            // does (see DestinationManager) can then actually notice and bail out, rather than
            // running the full scan to completion regardless of cancellation.
            //
            // listRecordings() can throw -- a revoked SAF permission (folder deleted, permission
            // pulled from Settings) surfaces as a SecurityException, and other provider failures
            // are possible too. Previously nothing caught this, so the scan thread would die
            // silently: no crash, but also no list update and no indication anything went wrong.
            // CancellationException is deliberately never caught here as an ordinary failure --
            // rethrowing it is what actually lets this coroutine finish cancelling, instead of
            // swallowing it and continuing to run "cancelled" code (an anti-pattern
            // kotlinx.coroutines itself warns against).
            val result = try {
                Result.success(runInterruptible(Dispatchers.IO) { manager.listRecordings() })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            if (_binding == null) return@launch
            // Secondary guard alongside this coroutine's own cancellation above: listRecordings()
            // is a plain blocking call, not a suspending one, so a cancelled Job can't interrupt
            // it mid-call -- if it happens to finish anyway, this still keeps its now-stale result
            // from overwriting a newer one that already landed.
            if (requestId != refreshRequestId.get()) return@launch
            result.fold(
                onSuccess = { items ->
                    adapter.submitList(items)
                    binding.emptyText.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
                },
                onFailure = { e ->
                    // Deliberately leaves the previously-shown list (if any) in place rather
                    // than clearing it to empty -- a transient/permission failure shouldn't
                    // make existing recordings look like they've vanished.
                    Toast.makeText(
                        appContext,
                        getString(R.string.library_load_failed, e.message ?: e.javaClass.simpleName),
                        Toast.LENGTH_LONG
                    ).show()
                }
            )
        }
    }

    // internal for testing: lets a test substitute a fake reader that blocks on command, without
    // needing a real (uncontrollable-timing) file read to deterministically exercise
    // showStats()'s dismiss-cancellation/view-destruction paths -- same rationale as
    // destinationManager's own internal+var seam above.
    internal var statsReader: (Context, Uri) -> AudioStats? = AudioStatsReader::read

    // The currently-shown statistics dialog, if any -- tracked (rather than left purely local to
    // showStats()) specifically so onDestroyView() can dismiss it explicitly. A Dialog built from
    // an Activity Context isn't torn down just because this Fragment's *view* is destroyed (the
    // two lifecycles are independent), so without this a dialog left open while navigating away
    // from this tab would linger on screen indefinitely with a stale window reference, even though
    // its own backing coroutine is already correctly cancelled via viewLifecycleOwner.lifecycleScope.
    private var activeStatsDialog: androidx.appcompat.app.AlertDialog? = null

    // internal for testing: same rationale as refreshList() -- lets a test drive this directly.
    internal fun showStats(item: RecordingItem) {
        if (_binding == null) return
        val dialogBinding = DialogStatsBinding.inflate(LayoutInflater.from(requireContext()))
        // Assigned right after the coroutine below actually launches; the dismiss listener only
        // ever fires later (a user interaction, or the positive "Close" button), by which point it
        // always sees the real Job.
        var statsJob: Job? = null
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(item.name)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.stats_close, null)
            // Cancels the scan the moment the user dismisses the dialog (Close button, tap
            // outside, back press, or onDestroyView() explicitly dismissing it below) -- previously
            // this kept scanning a potentially large WAV file to completion regardless, wastefully
            // holding dialogBinding/dialog alive for no purpose once nothing could ever display
            // the result.
            .setOnDismissListener {
                statsJob?.cancel()
                if (activeStatsDialog === it) activeStatsDialog = null
            }
            .show()
        activeStatsDialog = dialog

        val context = requireContext().applicationContext
        val uri = item.uri
        // Held weakly across the suspension point below rather than captured directly: a
        // Kotlin coroutine's local variables live in its own heap-allocated continuation object
        // for the coroutine's full lifetime, including while genuinely suspended inside
        // runInterruptible waiting on a blocking read that may not even be interruptible in
        // practice (some providers ignore it) -- a strong reference here would keep the dialog and
        // its binding artificially alive for that whole window even after the dialog is dismissed
        // and the Job cancelled. A dismissed dialog can now be collected immediately instead.
        val dialogRef = WeakReference(dialog)
        val dialogBindingRef = WeakReference(dialogBinding)
        statsJob = viewLifecycleOwner.lifecycleScope.launch {
            val stats = try {
                runInterruptible(Dispatchers.IO) { statsReader(context, uri) }
            } catch (e: CancellationException) {
                throw e
            }
            val liveDialog = dialogRef.get() ?: return@launch
            val liveBinding = dialogBindingRef.get() ?: return@launch
            if (!isAdded || !liveDialog.isShowing) return@launch
            liveBinding.statsProgress.visibility = View.GONE
            liveBinding.statsText.visibility = View.VISIBLE
            liveBinding.statsText.text = stats?.let { formatStats(it) }
                ?: getString(R.string.stats_read_failed)
        }
    }

    private fun formatStats(s: AudioStats): String {
        val channelLabel = when (s.channels) {
            1 -> "Mono (1)"
            2 -> "Stereo (2)"
            else -> "${s.channels} ch"
        }
        val peak = s.peakDbfs?.let { String.format(Locale.US, "%.1f dBFS", it) } ?: "silence"
        val rms = s.rmsDbfs?.let { String.format(Locale.US, "%.1f dBFS", it) } ?: "silence"
        val clippedPercent = if (s.sampleCount > 0) s.clippedSamples * 100.0 / s.sampleCount else 0.0

        return buildString {
            appendLine("Sample rate:  ${s.sampleRate} Hz")
            appendLine("Channels:     $channelLabel")
            appendLine("Bit depth:    ${s.bitsPerSample}-bit PCM")
            appendLine("Bitrate:      ${String.format(Locale.US, "%.1f", s.bitrateKbps)} kbps")
            appendLine("Duration:     ${String.format(Locale.US, "%.2f s", s.durationSeconds)}")
            appendLine("File size:    ${formatBytes(s.sizeBytes)}")
            appendLine("Samples:      ${String.format(Locale.US, "%,d", s.sampleCount)}")
            appendLine("Peak level:   $peak")
            appendLine("RMS level:    $rms")
            append("Clipped:      ${String.format(Locale.US, "%.2f%%", clippedPercent)} (${s.clippedSamples} samples)")
        }
    }

    private fun formatBytes(bytes: Long): String {
        val kb = bytes / 1024.0
        return if (kb < 1024) String.format(Locale.US, "%.0f KB", kb)
        else String.format(Locale.US, "%.2f MB", kb / 1024)
    }

    private fun confirmDelete(item: RecordingItem) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_confirm_title)
            .setMessage(getString(R.string.delete_confirm_message, item.name))
            .setNegativeButton(R.string.delete_confirm_cancel, null)
            .setPositiveButton(R.string.delete_confirm_positive) { _, _ -> deleteRecording(item) }
            .show()
    }

    private fun deleteRecording(item: RecordingItem) {
        if (item.uri == activeUri) releasePlayer()
        // DocumentFile.delete() on a SAF uri is a content-provider round trip like the listing
        // above, and File.delete() itself can block on I/O too; either can janks the UI thread if
        // run synchronously from this button-click handler.
        //
        // Deliberately its own raw Thread, not viewLifecycleOwner.lifecycleScope like
        // refreshList()/showStats(): the user just explicitly confirmed a destructive, one-shot
        // action, and unlike a scan (whose result is simply redundant once superseded/torn-down)
        // a confirmed delete must actually finish even if this screen is destroyed a moment later
        // -- lifecycleScope would cancel it right along with everything else on view destruction,
        // which is exactly wrong here. A plain independent Thread keeps that guarantee explicit
        // and unaffected by this fragment's view lifecycle.
        val manager = destinationManager
        val appContext = requireContext().applicationContext
        Thread({
            val deleted = manager.deleteRecording(item.uri)
            Handler(Looper.getMainLooper()).post {
                if (_binding == null) return@post
                if (!deleted) {
                    Toast.makeText(appContext, R.string.delete_failed, Toast.LENGTH_SHORT).show()
                }
                refreshList()
            }
        }, "DeleteRecordingThread").start()
    }

    private fun togglePlayPause(item: RecordingItem) {
        when {
            activeUri != item.uri -> playNew(item)
            // Still waiting on onPrepared/onError for this same item; MediaPlayer isn't in a
            // state that accepts start()/pause() yet, so ignore the extra tap rather than let it
            // throw.
            isPreparingPlayback -> Unit
            mediaPlayer?.isPlaying == true -> pauseActive()
            else -> resumeActive()
        }
    }

    private fun playNew(item: RecordingItem) {
        releasePlayer()
        if (!requestAudioFocus()) {
            Toast.makeText(requireContext(), R.string.audio_focus_denied, Toast.LENGTH_SHORT).show()
            return
        }
        // Marked active (and preparing) immediately so the list shows this row as the selected
        // one right away, even though playback itself only starts once onPrepared fires.
        activeUri = item.uri
        currentSpeed = 1.0f
        isPreparingPlayback = true
        suppressAutoStartOnPrepared = false
        try {
            // createConfigureOrRelease() releases the just-created player itself if configure
            // throws -- previously, a failure in setDataSource()/prepareAsync() left the
            // freshly-created MediaPlayer unreachable from any variable (the old `apply` block's
            // result was never assigned to `mediaPlayer`), leaking the underlying native
            // decoder/codec handle it holds rather than just JVM-collectible memory.
            val player = createConfigureOrRelease(
                create = mediaPlayerFactory,
                configure = { p ->
                    p.setDataSource(requireContext(), item.uri)
                    // Every callback below takes the MediaPlayer instance it fired on (`mp`) and
                    // checks it's still the one this fragment considers active before doing
                    // anything else. release()'d players are documented to stop firing callbacks,
                    // but a callback already queued on the main looper's message queue at the
                    // moment release() runs can still be delivered afterward -- e.g. switching
                    // tracks calls releasePlayer() on the old player and immediately builds a new
                    // one, and a stale callback for the *old* instance landing after that
                    // reassignment must not act on the new one, or on a view that's since been
                    // destroyed (releasePlayer() also runs from onDestroyView(), nulling
                    // mediaPlayer, so the identity check alone covers that case too: no live mp
                    // can ever match a null field).
                    p.setOnCompletionListener { mp -> if (mp === mediaPlayer) releasePlayer() }
                    p.setOnPreparedListener { mp ->
                        if (mp !== mediaPlayer) return@setOnPreparedListener
                        isPreparingPlayback = false
                        activeDurationMs = mp.duration
                        if (suppressAutoStartOnPrepared) {
                            // A permanent focus loss (or a still-unresolved transient one)
                            // arrived while this was preparing -- leave it prepared but paused
                            // instead of starting into a focus state that's already gone; see
                            // audioFocusListener.
                            suppressAutoStartOnPrepared = false
                        } else {
                            mp.start()
                            progressHandler.post(progressTick)
                        }
                        if (_binding != null) adapter.notifyDataSetChanged()
                    }
                    p.setOnErrorListener { mp, what, extra ->
                        if (mp === mediaPlayer) {
                            isPreparingPlayback = false
                            if (_binding != null) {
                                Toast.makeText(
                                    requireContext(),
                                    getString(R.string.playback_failed, "error $what/$extra"),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                            releasePlayer()
                        }
                        true
                    }
                    // prepare() blocks the calling thread until the MediaExtractor/decoder is
                    // ready to go, which can take long enough on a slow SAF provider or large
                    // file to visibly stall the UI thread this is always called from.
                    // prepareAsync() hands the same work to MediaPlayer's own internal thread
                    // instead; setOnPreparedListener above is what actually starts playback once
                    // it's done.
                    p.prepareAsync()
                },
                release = { it.release() }
            )
            mediaPlayer = player
        } catch (e: Exception) {
            // The just-created player (if any) was already released by createConfigureOrRelease()
            // itself above; releasePlayer() here only resets this fragment's own state (focus,
            // activeUri, preparing flags) -- `mediaPlayer` was never actually assigned on this
            // failure path, so it has nothing of its own left to release.
            isPreparingPlayback = false
            Toast.makeText(requireContext(), getString(R.string.playback_failed, e.message), Toast.LENGTH_SHORT).show()
            releasePlayer()
            return
        }
        adapter.notifyDataSetChanged()
    }

    private fun pauseActive() {
        if (isPreparingPlayback) return // pause() before onPrepared is undefined behavior
        mediaPlayer?.pause()
        progressHandler.removeCallbacks(progressTick)
        activeUri?.let { adapter.notifyProgressChanged(it) }
    }

    private fun resumeActive() {
        if (isPreparingPlayback) return // not yet in a state that accepts start()
        if (!requestAudioFocus()) {
            Toast.makeText(requireContext(), R.string.audio_focus_denied, Toast.LENGTH_SHORT).show()
            return
        }
        mediaPlayer?.start()
        progressHandler.post(progressTick)
        activeUri?.let { adapter.notifyProgressChanged(it) }
    }

    private fun seekActiveTo(ms: Int) {
        if (isPreparingPlayback) return // seekTo() before onPrepared throws
        mediaPlayer?.seekTo(ms)
        activeUri?.let { adapter.notifyProgressChanged(it) }
    }

    private fun cycleSpeed() {
        if (isPreparingPlayback) return // playbackParams before onPrepared throws
        val player = mediaPlayer ?: return
        val currentIndex = SPEED_STEPS.indexOfFirst { it == currentSpeed }.let { if (it < 0) 0 else it }
        val nextSpeed = SPEED_STEPS[(currentIndex + 1) % SPEED_STEPS.size]
        val wasPlaying = player.isPlaying
        try {
            player.playbackParams = PlaybackParams().setSpeed(nextSpeed)
            currentSpeed = nextSpeed
            // Setting playbackParams implicitly starts the player on some OEM builds; restore intent.
            if (wasPlaying) player.start() else player.pause()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), getString(R.string.playback_failed, e.message), Toast.LENGTH_SHORT).show()
        }
        activeUri?.let { adapter.notifyProgressChanged(it) }
        adapter.notifyDataSetChanged()
    }

    private fun requestAudioFocus(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener(audioFocusListener)
                .build()
            focusRequest = request
            audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                audioFocusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(audioFocusListener)
        }
        resumeOnFocusGain = false
    }

    /** Fully tears down playback: track finished, a different track was picked, or we're leaving. */
    private fun releasePlayer() {
        progressHandler.removeCallbacks(progressTick)
        abandonAudioFocus()
        mediaPlayer?.apply {
            // stop() while still in the Preparing state throws; release() is valid from any
            // state regardless, so that alone is enough to tear the player down.
            try { stop() } catch (_: Exception) {}
            release()
        }
        mediaPlayer = null
        activeUri = null
        activeDurationMs = 0
        currentSpeed = 1.0f
        isPreparingPlayback = false
        suppressAutoStartOnPrepared = false
        if (_binding != null) adapter.notifyDataSetChanged()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        releasePlayer()
        // Explicit, rather than left to whatever the OS/window manager eventually does: a Dialog
        // built from an Activity Context has its own independent lifecycle from this Fragment's
        // view, so it would otherwise linger on screen (with a now-stale window reference) even
        // though the coroutine backing it is about to be cancelled below regardless -- see
        // activeStatsDialog's own doc. dismiss() itself triggers the dismiss listener, which
        // cancels statsJob -- redundant with the lifecycleScope cancellation just after, but
        // harmless (Job.cancel() is idempotent).
        activeStatsDialog?.dismiss()
        activeStatsDialog = null
        // No explicit job cancellation needed here beyond the above: viewLifecycleOwner's own
        // Lifecycle already moves to DESTROYED just after this returns, which is what actually
        // cancels viewLifecycleOwner.lifecycleScope (and therefore refreshList()'s/showStats()'s
        // in-flight coroutines) -- see refreshJob's doc.
        requireContext().unregisterReceiver(becomingNoisyReceiver)
        _binding = null
    }
}

/**
 * Constructs an instance via [create], then runs [configure] against it -- if [configure] throws,
 * the just-created instance is released via [release] before the exception propagates, so a
 * caller's own failure handling never has to reach into a partially-configured instance it never
 * got a chance to assign anywhere itself. Pulled out (rather than inlined in
 * [LibraryFragment.playNew]) so this ordering guarantee is directly unit-testable without a real
 * (`final`, so unsubclassable/unmockable here) [android.media.MediaPlayer].
 */
internal fun <T> createConfigureOrRelease(create: () -> T, configure: (T) -> Unit, release: (T) -> Unit): T {
    val instance = create()
    try {
        configure(instance)
    } catch (e: Exception) {
        release(instance)
        throw e
    }
    return instance
}
