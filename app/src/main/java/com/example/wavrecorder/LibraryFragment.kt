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
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.wavrecorder.databinding.DialogStatsBinding
import com.example.wavrecorder.databinding.FragmentLibraryBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

class LibraryFragment : Fragment() {

    private var _binding: FragmentLibraryBinding? = null
    private val binding get() = _binding!!

    // internal + var (rather than private/val) so a test can substitute a DestinationManager
    // subclass that fails on command, e.g. simulating a revoked SAF permission.
    internal lateinit var destinationManager: DestinationManager
    private lateinit var adapter: RecordingsAdapter

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

    // Bumped on every refreshList() call; a scan result only gets applied if it's still the most
    // recent one requested. Concurrent scans (e.g. onResume() firing again before a slow SAF
    // listing finishes) can otherwise complete out of order and let a stale result clobber a
    // newer one.
    private val refreshRequestId = AtomicInteger(0)

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

    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                resumeOnFocusGain = false
                pauseActive()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                resumeOnFocusGain = mediaPlayer?.isPlaying == true
                pauseActive()
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (resumeOnFocusGain) {
                    resumeOnFocusGain = false
                    resumeActive()
                }
            }
        }
    }

    /** Wired headphones pulled out mid-playback would otherwise blast audio through the speaker. */
    private val becomingNoisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (mediaPlayer?.isPlaying == true) pauseActive()
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
        // Listing a SAF-picked folder walks DocumentFile, which is one Binder round-trip to the
        // storage provider per file (name, mime type, last-modified are each a separate query) -
        // enough to visibly stutter the tab on a slower provider, so this runs off the main thread.
        // Captures only the manager reference (not `this`/the fragment/any view) so a slow or
        // failing scan can't keep the fragment's view tree alive from a background thread.
        val manager = destinationManager
        val appContext = requireContext().applicationContext
        val requestId = refreshRequestId.incrementAndGet()
        Thread({
            // listRecordings() can throw -- a revoked SAF permission (folder deleted, permission
            // pulled from Settings) surfaces as a SecurityException, and other provider failures
            // are possible too. Previously nothing caught this, so the scan thread would die
            // silently: no crash, but also no list update and no indication anything went wrong.
            val result = try {
                Result.success(manager.listRecordings())
            } catch (e: Exception) {
                Result.failure(e)
            }
            Handler(Looper.getMainLooper()).post {
                if (_binding == null) return@post
                // A newer refreshList() has since been requested; this result is stale (the two
                // scans raced and this one lost) and must not overwrite the list with newer data
                // -- true whether this scan succeeded or failed.
                if (requestId != refreshRequestId.get()) return@post
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
        }, "LibraryScanThread").start()
    }

    private fun showStats(item: RecordingItem) {
        val dialogBinding = DialogStatsBinding.inflate(LayoutInflater.from(requireContext()))
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(item.name)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.stats_close, null)
            .show()

        val context = requireContext().applicationContext
        Thread({
            val stats = AudioStatsReader.read(context, item.uri)
            Handler(Looper.getMainLooper()).post {
                if (!isAdded || !dialog.isShowing) return@post
                dialogBinding.statsProgress.visibility = View.GONE
                dialogBinding.statsText.visibility = View.VISIBLE
                dialogBinding.statsText.text = stats?.let { formatStats(it) }
                    ?: getString(R.string.stats_read_failed)
            }
        }, "AudioStatsThread").start()
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
        try {
            val player = MediaPlayer().apply {
                setDataSource(requireContext(), item.uri)
                setOnCompletionListener { releasePlayer() }
                setOnPreparedListener { mp ->
                    isPreparingPlayback = false
                    activeDurationMs = mp.duration
                    mp.start()
                    progressHandler.post(progressTick)
                    adapter.notifyDataSetChanged()
                }
                setOnErrorListener { _, what, extra ->
                    isPreparingPlayback = false
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.playback_failed, "error $what/$extra"),
                        Toast.LENGTH_SHORT
                    ).show()
                    releasePlayer()
                    true
                }
                // prepare() blocks the calling thread until the MediaExtractor/decoder is ready to
                // go, which can take long enough on a slow SAF provider or large file to visibly
                // stall the UI thread this is always called from. prepareAsync() hands the same
                // work to MediaPlayer's own internal thread instead; setOnPreparedListener above
                // is what actually starts playback once it's done.
                prepareAsync()
            }
            mediaPlayer = player
        } catch (e: Exception) {
            isPreparingPlayback = false
            Toast.makeText(requireContext(), getString(R.string.playback_failed, e.message), Toast.LENGTH_SHORT).show()
            releasePlayer()
            return
        }
        adapter.notifyDataSetChanged()
    }

    private fun pauseActive() {
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
        adapter.notifyDataSetChanged()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        releasePlayer()
        requireContext().unregisterReceiver(becomingNoisyReceiver)
        _binding = null
    }
}
