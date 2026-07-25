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

class LibraryFragment : Fragment() {

    private var _binding: FragmentLibraryBinding? = null
    private val binding get() = _binding!!

    private lateinit var destinationManager: DestinationManager
    private lateinit var adapter: RecordingsAdapter

    /** The currently loaded MediaPlayer, if any. It stays alive across pause/resume. */
    private var mediaPlayer: MediaPlayer? = null
    private var activeUri: Uri? = null
    private var activeDurationMs = 0
    private var currentSpeed = 1.0f

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

    private fun refreshList() {
        // Listing a SAF-picked folder walks DocumentFile, which is one Binder round-trip to the
        // storage provider per file (name, mime type, last-modified are each a separate query) -
        // enough to visibly stutter the tab on a slower provider, so this runs off the main thread.
        val manager = destinationManager
        Thread({
            val items = manager.listRecordings()
            Handler(Looper.getMainLooper()).post {
                if (_binding == null) return@post
                adapter.submitList(items)
                binding.emptyText.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
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
        val deleted = destinationManager.deleteRecording(item.uri)
        if (!deleted) {
            Toast.makeText(requireContext(), R.string.delete_failed, Toast.LENGTH_SHORT).show()
        }
        refreshList()
    }

    private fun togglePlayPause(item: RecordingItem) {
        when {
            activeUri != item.uri -> playNew(item)
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
        try {
            val player = MediaPlayer().apply {
                setDataSource(requireContext(), item.uri)
                setOnCompletionListener { releasePlayer() }
                prepare()
                start()
            }
            mediaPlayer = player
            activeUri = item.uri
            activeDurationMs = player.duration
            currentSpeed = 1.0f
            progressHandler.post(progressTick)
        } catch (e: Exception) {
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
        if (!requestAudioFocus()) {
            Toast.makeText(requireContext(), R.string.audio_focus_denied, Toast.LENGTH_SHORT).show()
            return
        }
        mediaPlayer?.start()
        progressHandler.post(progressTick)
        activeUri?.let { adapter.notifyProgressChanged(it) }
    }

    private fun seekActiveTo(ms: Int) {
        mediaPlayer?.seekTo(ms)
        activeUri?.let { adapter.notifyProgressChanged(it) }
    }

    private fun cycleSpeed() {
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
            try { stop() } catch (_: Exception) {}
            release()
        }
        mediaPlayer = null
        activeUri = null
        activeDurationMs = 0
        currentSpeed = 1.0f
        adapter.notifyDataSetChanged()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        releasePlayer()
        requireContext().unregisterReceiver(becomingNoisyReceiver)
        _binding = null
    }
}
