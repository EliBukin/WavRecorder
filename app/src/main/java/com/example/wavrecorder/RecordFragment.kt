package com.example.wavrecorder

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.example.wavrecorder.databinding.FragmentRecordBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class RecordFragment : Fragment() {

    private var _binding: FragmentRecordBinding? = null
    private val binding get() = _binding!!

    private lateinit var destinationManager: DestinationManager
    private var currentTarget: OutputTarget? = null
    private var currentPartNumber = 1

    private var recordingService: RecordingService? = null
    private var isBound = false
    private var pendingStart = false

    private var audioManager: AudioManager? = null
    // Defaults to "none detected" rather than an optimistic guess: until the first real query
    // runs (see refreshPreferredMicStatus(), called from onStart()), there's nothing to back up
    // any stronger claim, and a stale "connected" default could let a Record tap skip the
    // no-external-mic confirmation it's specifically meant to guard.
    private var latestPreferredMicStatus: PreferredMicStatus = PreferredMicStatus.NoneDetected
    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) = refreshPreferredMicStatus()
        override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) = refreshPreferredMicStatus()
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as RecordingService.LocalBinder).getService()
            recordingService = service
            service.listener = recordingListener
            isBound = true
            if (pendingStart) {
                pendingStart = false
                service.startRecording()
            }
            syncUiWithService()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            recordingService = null
            isBound = false
        }
    }

    private val recordingListener = object : RecordingService.Listener {
        override fun onAmplitude(amplitude: Float) {
            if (_binding != null) binding.waveformView.addAmplitude(amplitude)
        }

        override fun onSegmentStarted(target: OutputTarget, partNumber: Int) {
            if (_binding != null) {
                currentTarget = target
                currentPartNumber = partNumber
                binding.recordButton.text = getString(R.string.stop_recording)
                updateRecordingStatus()
            }
        }

        override fun onError(e: Exception) {
            if (_binding != null) {
                val message = when (e) {
                    is MicrophoneDisconnectedException -> getString(R.string.mic_disconnected_error)
                    is MicrophoneRouteChangedException -> getString(R.string.mic_route_changed_error)
                    else -> getString(R.string.recording_error, e.message)
                }
                Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
                resetToIdle()
            }
        }

        override fun onStopped(lastTarget: OutputTarget?) {
            if (_binding != null) {
                resetToIdle()
                binding.statusText.text = lastTarget?.let {
                    getString(R.string.status_saved, it.displayPath)
                } ?: getString(R.string.status_idle)
            }
        }

        override fun onMicrophoneInfo(info: MicrophoneInfo) {
            if (_binding != null) updateMicDeviceLabel(info)
        }

        override fun onFinalizationFailed(target: OutputTarget?, cause: Exception) {
            if (_binding != null) {
                resetToIdle()
                // Deliberately never worded as "saved" -- the header/writer didn't actually close
                // cleanly, so the file (if any) needs to be treated as possibly needing recovery,
                // not a normal successful result.
                binding.statusText.text = target?.let {
                    getString(R.string.recording_needs_recovery, it.displayPath, cause.message ?: "")
                } ?: getString(R.string.recording_error, cause.message)
            }
        }

        override fun onFinalizationUnknown(target: OutputTarget?) {
            if (_binding != null) {
                resetToIdle()
                // Deliberately distinct from both "Saved" and "needs recovery": whether the last
                // segment actually finished writing is genuinely unconfirmed (the recording thread
                // never joined in time), not known to have failed -- wording this as a plain save
                // would be a false confidence claim the recorder itself can't back up.
                binding.statusText.text = target?.let {
                    getString(R.string.recording_needs_verification, it.displayPath)
                } ?: getString(R.string.recording_needs_verification_unknown_location)
            }
        }
    }

    private val requestPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            handlePermissionResult(results)
        }

    // The results map only contains entries for permissions that were actually requested, so a
    // permission already granted before the call (e.g. RECORD_AUDIO, when only POST_NOTIFICATIONS
    // was missing) is absent from it rather than present with value true. Checking the map instead
    // of the live permission state would misread that absence as a denial. internal for testing.
    @Suppress("UNUSED_PARAMETER")
    internal fun handlePermissionResult(results: Map<String, Boolean>) {
        if (hasMicrophonePermission()) {
            beginRecording()
        } else {
            Toast.makeText(requireContext(), R.string.permission_denied, Toast.LENGTH_LONG).show()
        }
    }

    private fun hasMicrophonePermission(): Boolean =
        ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private val folderPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                destinationManager.setTreeUri(uri)
                updateDestinationLabel()
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRecordBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        destinationManager = DestinationManager(requireContext())
        updateDestinationLabel()

        binding.chooseFolderButton.setOnClickListener { folderPicker.launch(null) }
        binding.recordButton.setOnClickListener {
            when {
                recordingService?.isRecording == true -> confirmStopRecording()
                latestPreferredMicStatus == PreferredMicStatus.NoneDetected -> confirmRecordWithoutExternalMic()
                else -> requestPermissionAndRecord()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Registered for exactly as long as this screen is visible, mirroring the service
        // binding just below -- so the idle status can react immediately to a mic being plugged
        // in or removed while the user is looking at this screen, without waiting for a
        // recording to be in progress (that path is covered separately, by SystemAudioSource).
        audioManager = requireContext().getSystemService(AudioManager::class.java)
        audioManager?.registerAudioDeviceCallback(audioDeviceCallback, null)
        refreshPreferredMicStatus()

        // Bind whenever visible so we always have a live channel for waveform/status updates
        // and can resync with a recording that's been running in the background (screen off,
        // another app in front) since we were last here.
        requireContext().bindService(
            Intent(requireContext(), RecordingService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )
    }

    override fun onStop() {
        super.onStop()
        audioManager?.unregisterAudioDeviceCallback(audioDeviceCallback)
        audioManager = null

        // Only detaches the UI's live connection — the service is independently started via
        // startForegroundService() once recording begins, so it (and the recording) keeps
        // running after this unbind, which is the whole point.
        if (isBound) {
            recordingService?.listener = null
            requireContext().unbindService(serviceConnection)
            isBound = false
        }
        recordingService = null
    }

    private fun syncUiWithService() {
        val service = recordingService ?: return
        if (service.isRecording) {
            currentTarget = service.lastTarget
            currentPartNumber = service.lastPartNumber
            binding.recordButton.text = getString(R.string.stop_recording)
            updateRecordingStatus()
            service.lastMicrophoneInfo?.let { updateMicDeviceLabel(it) }
        }
    }

    private fun confirmStopRecording() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.stop_confirm_title)
            .setMessage(R.string.stop_confirm_message)
            .setNegativeButton(R.string.stop_confirm_cancel, null)
            .setPositiveButton(R.string.stop_confirm_positive) { _, _ -> recordingService?.stopRecording() }
            .show()
    }

    /** Recording is never blocked on this -- it's purely a heads-up, since the phone's own mic is
     * a perfectly valid (if non-preferred) input, and the post-start route verification (see
     * RecordingService.Listener.onMicrophoneInfo/onError) remains the actual source of truth
     * regardless of what the user chooses here. */
    private fun confirmRecordWithoutExternalMic() {
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(R.string.no_external_mic_confirm_message)
            .setNegativeButton(R.string.no_external_mic_confirm_cancel, null)
            .setPositiveButton(R.string.no_external_mic_confirm_positive) { _, _ -> requestPermissionAndRecord() }
            .show()
    }

    private fun requestPermissionAndRecord() {
        val needed = mutableListOf<String>()
        if (!hasMicrophonePermission()) {
            needed += Manifest.permission.RECORD_AUDIO
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }

        if (needed.isEmpty()) {
            beginRecording()
        } else {
            requestPermissionsLauncher.launch(needed.toTypedArray())
        }
    }

    private fun beginRecording() {
        binding.waveformView.clear()

        val serviceIntent = Intent(requireContext(), RecordingService::class.java)
        ContextCompat.startForegroundService(requireContext(), serviceIntent)

        val service = recordingService
        if (service != null) {
            service.startRecording()
        } else {
            pendingStart = true
        }
    }

    private fun updateRecordingStatus() {
        binding.statusText.text = if (currentPartNumber > 1) {
            getString(R.string.status_recording_part, currentPartNumber)
        } else {
            getString(R.string.status_recording)
        }
    }

    private fun updateMicDeviceLabel(info: MicrophoneInfo) {
        binding.micDeviceLabel.text = when {
            !info.verified -> getString(R.string.mic_device_unverified, info.label)
            info.isExternal -> getString(R.string.mic_device_external_verified, info.label)
            else -> getString(R.string.mic_device_builtin_verified, info.label)
        }
    }

    /** Re-queries [AudioManager] for the currently preferred input device and, unless a recording
     * is actually in progress right now, reflects it in the idle status label immediately -- called
     * from [onStart] and every time [audioDeviceCallback] fires. [latestPreferredMicStatus] itself
     * is always kept current regardless of recording state, so the moment recording actually does
     * stop (see [resetToIdle]), the label reflects reality rather than a status computed before
     * whatever changed (e.g. the mic that had been recording from disconnecting) was noticed. */
    private fun refreshPreferredMicStatus() {
        latestPreferredMicStatus = queryPreferredMicStatus(audioManager)
        if (recordingService?.isRecording != true) updateIdleMicStatusLabel()
    }

    /** Deliberately worded as "connected"/"detected", never "verified" or "active" -- this is only
     * ever a claim about AudioManager's attached-device list, not about what an actual AudioRecord
     * would end up routed to; see [PreferredMicStatus]. */
    private fun updateIdleMicStatusLabel() {
        if (_binding == null) return
        binding.micDeviceLabel.text = when (val status = latestPreferredMicStatus) {
            is PreferredMicStatus.Insta360Connected ->
                getString(R.string.mic_idle_insta360_connected, status.label)
            PreferredMicStatus.ExternalConnected -> getString(R.string.mic_idle_external_connected)
            PreferredMicStatus.NoneDetected -> getString(R.string.mic_idle_none_detected)
        }
    }

    private fun resetToIdle() {
        binding.recordButton.text = getString(R.string.start_recording)
        binding.statusText.text = getString(R.string.status_idle)
        updateIdleMicStatusLabel()
        binding.waveformView.clear()
    }

    private fun updateDestinationLabel() {
        binding.destinationLabel.text = getString(R.string.destination_label, destinationManager.displayName())
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
