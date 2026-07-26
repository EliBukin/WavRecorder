package com.example.wavrecorder

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
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
                Toast.makeText(requireContext(), getString(R.string.recording_error, e.message), Toast.LENGTH_LONG).show()
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
            if (recordingService?.isRecording == true) confirmStopRecording() else requestPermissionAndRecord()
        }
    }

    override fun onStart() {
        super.onStart()
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

    private fun resetToIdle() {
        binding.recordButton.text = getString(R.string.start_recording)
        binding.statusText.text = getString(R.string.status_idle)
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
