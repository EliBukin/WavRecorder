package com.example.wavrecorder

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.ColorStateList
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
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import com.example.wavrecorder.databinding.FragmentRecordBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

class RecordFragment : Fragment() {

    private var _binding: FragmentRecordBinding? = null
    private val binding get() = _binding!!

    private lateinit var destinationManager: DestinationManager
    private var currentTarget: OutputTarget? = null
    private var currentPartNumber = 1

    private var recordingService: RecordingService? = null
    // True from a successful bindService() call in onStart() until onStop() undoes it -- tracked
    // separately from onServiceConnected() actually having run, since that callback is
    // asynchronous and can otherwise land (or never land at all) after this screen has already
    // stopped. onStop() unbinds based on this, not on whether a connection ever completed.
    private var bindRequested = false
    // internal (not private) so a test can simulate "a start was queued right before this screen
    // stopped" deterministically, without depending on exactly when Robolectric's bindService()
    // happens to deliver onServiceConnected -- see RecordFragmentLifecycleTest.
    internal var pendingStart = false
    // The request id (see RecordingService.EXTRA_REQUEST_ID) of the beginRecording() attempt
    // [pendingStart] refers to, if any -- carried alongside it so a later ACTION_CANCEL_START (see
    // onStop()) or a fulfilled connection (see serviceConnection below) always names the exact
    // same attempt the original ACTION_START Intent did, letting RecordingService tell a stale
    // message for an already-resolved attempt apart from a genuinely new retry. internal for
    // tests -- see RecordFragmentLifecycleTest.
    internal var pendingRequestId: Long = 0L

    // Snapshotted at the moment a start attempt is actually made (see startAndCheckMicRoute()):
    // whether an external input was detected/preferred beforehand, and whether the user has
    // already explicitly agreed to fall back to the phone mic for this specific attempt (via the
    // post-start mismatch dialog below). Together these decide whether onMicrophoneInfo's
    // verified-route mismatch check applies at all.
    private var expectedExternalMic = false
    private var acceptPhoneMicForThisAttempt = false
    // Set from within the (synchronous) onMicrophoneInfo callback and only acted on once
    // service.startRecording() has actually returned -- see startAndCheckMicRoute() for why this
    // can't just call stopRecording() reentrantly from inside the callback itself.
    private var pendingMicMismatch = false

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

    // internal (not private) so a test can simulate a connection landing after this screen has
    // already stopped -- see RecordFragmentLifecycleTest.
    internal val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            // A connection can complete after onStop() already ran: unbindService() there can't
            // retract a connection already in flight, and bindRequested being false here means
            // exactly that happened. Also guards the (defense-in-depth) case where the view was
            // torn down for some other reason without bindRequested yet having been cleared. Either
            // way, this must not wire a listener nothing will ever clear, resync UI that no longer
            // exists, or start a recording nobody asked for anymore.
            if (!bindRequested || _binding == null || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                return
            }
            val service = (binder as RecordingService.LocalBinder).getService()
            recordingService = service
            service.listener = recordingListener
            if (pendingStart) {
                pendingStart = false
                startAndCheckMicRoute(service, pendingRequestId)
            }
            syncUiWithService()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            recordingService?.listener = null
            recordingService = null
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
                binding.audioLevelSection.visibility = View.VISIBLE
                updateRecordingStatus()
            }
        }

        override fun onError(e: Exception) {
            if (_binding != null) handleError(e)
        }

        override fun onStopped(lastTarget: OutputTarget?) {
            if (_binding != null) handleSaved(lastTarget, recordingService?.lastSessionStartedAtMillis)
        }

        override fun onMicrophoneInfo(info: MicrophoneInfo) {
            if (_binding != null) {
                updateMicDeviceLabel(info)
                // An external mic was detected/preferred for this attempt and the user hasn't
                // already agreed to fall back to the phone mic -- but the route actually verified
                // is either the builtin mic or couldn't be verified at all. Recorded here (not
                // acted on) because this fires synchronously from inside recorder.start(), before
                // RecordingService.startRecording() has even returned -- see
                // startAndCheckMicRoute() for why stopping the session right here would be unsafe.
                if (expectedExternalMic && !acceptPhoneMicForThisAttempt && !(info.verified && info.isExternal)) {
                    pendingMicMismatch = true
                }
            }
        }

        override fun onFinalizationFailed(target: OutputTarget?, cause: Exception) {
            if (_binding != null) handleFinalizationFailed(target, cause)
        }

        override fun onFinalizationUnknown(target: OutputTarget?) {
            if (_binding != null) handleFinalizationUnknown(target)
        }
    }

    private fun handleError(e: Exception) {
        val message = when (e) {
            is MicrophoneDisconnectedException -> getString(R.string.mic_disconnected_error)
            is MicrophoneRouteChangedException -> getString(R.string.mic_route_changed_error)
            // Reconstructed from durable storage (see PendingOutcomeStore) after this outcome
            // outlived the RecordingService instance that produced it -- its message is already
            // the fully-resolved, safe text (computed once, at persist time), so it's shown as-is
            // rather than re-wrapped in the generic "Recording error: %s" template below, which
            // would otherwise double up on wording that's already complete.
            is PersistedOutcomeException -> e.message ?: getString(R.string.recording_error, null)
            else -> getString(R.string.recording_error, e.message)
        }
        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
        resetToIdle()
    }

    private fun handleSaved(lastTarget: OutputTarget?, startedAt: Long?) {
        resetToIdle()
        if (lastTarget != null) {
            showSavedSummary(startedAt)
        } else {
            binding.statusText.text = getString(R.string.status_idle)
        }
    }

    private fun handleFinalizationFailed(target: OutputTarget?, cause: Exception) {
        resetToIdle()
        // Deliberately never worded as "saved" -- the header/writer didn't actually close
        // cleanly, so the file (if any) needs to be treated as possibly needing recovery, not a
        // normal successful result.
        binding.statusText.text = target?.let {
            getString(R.string.recording_needs_recovery, it.displayPath, cause.message ?: "")
        } ?: getString(R.string.recording_error, cause.message)
    }

    private fun handleFinalizationUnknown(target: OutputTarget?) {
        resetToIdle()
        // Deliberately distinct from both "Saved" and "needs recovery": whether the last segment
        // actually finished writing is genuinely unconfirmed (the recording thread never joined in
        // time), not known to have failed -- wording this as a plain save would be a false
        // confidence claim the recorder itself can't back up.
        binding.statusText.text = target?.let {
            getString(R.string.recording_needs_verification, it.displayPath)
        } ?: getString(R.string.recording_needs_verification_unknown_location)
    }

    /** Catches this screen up on a terminal outcome it wasn't around to see live -- e.g. the app
     * was backgrounded when a session ended (see [RecordingService.consumePendingOutcome]) --
     * using the exact same display logic the live listener callbacks above use, so a "Recording
     * saved"/"needs recovery"/etc. message looks identical whether it was shown live or caught up
     * on later. */
    private fun displayPendingOutcome(outcome: RecordingOutcome) {
        when (outcome) {
            is RecordingOutcome.Saved -> handleSaved(outcome.target, outcome.startedAtMillis)
            is RecordingOutcome.FailedButSaved -> handleError(outcome.cause)
            is RecordingOutcome.FinalizationFailed -> handleFinalizationFailed(outcome.target, outcome.cause)
            is RecordingOutcome.FinalizationUnknown -> handleFinalizationUnknown(outcome.target)
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
        if (!hasMicrophonePermission()) {
            Toast.makeText(requireContext(), R.string.permission_denied, Toast.LENGTH_LONG).show()
            return
        }
        if (notificationPermissionDenied()) {
            confirmRecordWithoutNotificationPermission()
        } else {
            beginRecording()
        }
    }

    private fun hasMicrophonePermission(): Boolean =
        ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /** Recording may legally start on Android 13+ without POST_NOTIFICATIONS, but the foreground
     * service's ongoing notification -- and its Stop action -- simply won't be visible while it's
     * denied. Always re-checks the live permission state rather than trusting a launcher result,
     * so this also correctly reports "denied" when notifications were never requested at all in
     * this attempt (e.g. only RECORD_AUDIO was missing) but had already been denied earlier. */
    private fun notificationPermissionDenied(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED

    /** Recording is never blocked on this either (mirrors [confirmRecordWithoutExternalMic]) --
     * it's an explicit, honest heads-up that the Stop control won't be reachable from the
     * notification shade, not a hard requirement. */
    private fun confirmRecordWithoutNotificationPermission() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.no_notification_permission_title)
            .setMessage(R.string.no_notification_permission_message)
            .setNegativeButton(R.string.no_notification_permission_cancel, null)
            .setPositiveButton(R.string.no_notification_permission_positive) { _, _ -> beginRecording() }
            .show()
    }

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
        // another app in front) since we were last here. bindService() only reports whether the
        // *request* was accepted, not whether onServiceConnected has run yet -- bindRequested
        // tracks that acceptance so onStop() can always undo it, even if the connection itself
        // never completed (or hasn't yet) while this screen was visible.
        bindRequested = requireContext().bindService(
            Intent(requireContext(), RecordingService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )
    }

    override fun onStop() {
        super.onStop()
        audioManager?.unregisterAudioDeviceCallback(audioDeviceCallback)
        audioManager = null

        // A start requested by this screen must not fire once it's no longer around to show the
        // result -- cancel it outright rather than let a delayed onServiceConnected (see
        // serviceConnection) act on it. Also explicitly retracts the underlying
        // startForegroundService() request at the service itself (which owns that decision, not
        // this Fragment's local pendingStart) so a connection that never completes can't leave a
        // started-but-never-promoted foreground service behind -- see
        // RecordingService.onStartCommand(). Harmless (a guarded no-op) if nothing was actually
        // pending, e.g. the connection already won the race and started recording.
        if (pendingStart) {
            pendingStart = false
            requireContext().startService(
                Intent(requireContext(), RecordingService::class.java)
                    .setAction(RecordingService.ACTION_CANCEL_START)
                    .putExtra(RecordingService.EXTRA_REQUEST_ID, pendingRequestId)
            )
        }

        // Only detaches the UI's live connection — the service is independently started via
        // startForegroundService() once recording begins, so it (and the recording) keeps
        // running after this unbind, which is the whole point. Always unbinds whenever a bind was
        // accepted, regardless of whether onServiceConnected ever actually ran for it.
        if (bindRequested) {
            recordingService?.listener = null
            requireContext().unbindService(serviceConnection)
            bindRequested = false
        }
        recordingService = null
    }

    private fun syncUiWithService() {
        val service = recordingService ?: return
        if (service.isRecording) {
            currentTarget = service.lastTarget
            currentPartNumber = service.lastPartNumber
            binding.recordButton.text = getString(R.string.stop_recording)
            binding.audioLevelSection.visibility = View.VISIBLE
            binding.statusDetailText.visibility = View.GONE
            updateRecordingStatus()
            service.lastMicrophoneInfo?.let { updateMicDeviceLabel(it) }
        } else {
            // Catches this screen up on whatever happened while it wasn't around to see it live
            // (or wasn't bound yet) -- see RecordingService.consumePendingOutcome.
            service.consumePendingOutcome()?.let { displayPendingOutcome(it) }
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
            // Both permissions already settled (and notifications, specifically, already granted
            // -- otherwise it would have been added to `needed` above), so there's nothing for
            // handlePermissionResult's notification check to catch that isn't already true here.
            beginRecording()
        } else {
            requestPermissionsLauncher.launch(needed.toTypedArray())
        }
    }

    /** [acceptPhoneMic] is only ever true when this is a direct retry from the post-start mismatch
     * dialog (see [showExternalMicMismatchDialog]) -- the user has already explicitly agreed to
     * fall back to the phone mic for that one attempt, so this attempt's own mismatch check must
     * not fire again for the same, already-acknowledged outcome. */
    private fun beginRecording(acceptPhoneMic: Boolean = false) {
        expectedExternalMic = latestPreferredMicStatus is PreferredMicStatus.ExternalConnected
        acceptPhoneMicForThisAttempt = acceptPhoneMic
        binding.waveformView.clear()
        binding.statusDetailText.visibility = View.GONE

        // A fresh, strictly-increasing id for this one attempt -- attached to the Intent below and
        // passed directly to startRecording() in the already-bound case, so RecordingService can
        // correlate the two (and a later ACTION_CANCEL_START) as referring to the same logical
        // request, regardless of the order they actually arrive in. See
        // RecordingService.EXTRA_REQUEST_ID/handleActionStart for why this matters: without it, a
        // delayed ACTION_START for an attempt that already failed synchronously (or a stale
        // cancellation/timeout for one that's since been superseded by a newer retry) has no way
        // to be told apart from a genuinely new request.
        val requestId = requestIdGenerator.incrementAndGet()
        pendingRequestId = requestId

        // Explicitly tagged ACTION_START (rather than a bare Intent) so RecordingService itself
        // has an authoritative record that it now owes either a real startForeground() call or a
        // safe stopSelf() -- see onStop() below for the corresponding cancellation, and
        // RecordingService.onStartCommand() for how it's honored regardless of whether this
        // Fragment's own bind connection ever completes.
        val serviceIntent = Intent(requireContext(), RecordingService::class.java)
            .setAction(RecordingService.ACTION_START)
            .putExtra(RecordingService.EXTRA_REQUEST_ID, requestId)
        ContextCompat.startForegroundService(requireContext(), serviceIntent)

        val service = recordingService
        if (service != null) {
            startAndCheckMicRoute(service, requestId)
        } else {
            pendingStart = true
        }
    }

    /** Starts recording and then, only once [RecordingService.startRecording] has actually
     * returned, checks whether [pendingMicMismatch] got set: [MicrophoneInfo] is delivered
     * synchronously from inside [RecordingService.startRecording] (via [WavRecorder.start]),
     * *before* the recorder's own `isRecording` flag even flips true -- calling
     * [RecordingService.stopRecording] reentrantly from within that callback would silently no-op
     * (stopRecording() bails out whenever the recorder doesn't consider itself active yet) and
     * let recording continue unnoticed on the wrong input. Deferring the check to right after
     * startRecording() returns avoids that race entirely. */
    private fun startAndCheckMicRoute(service: RecordingService, requestId: Long) {
        pendingMicMismatch = false
        service.startRecording(requestId)
        if (pendingMicMismatch) {
            pendingMicMismatch = false
            // The session just started (almost certainly before any audio was ever captured, per
            // the timing above) is stopped immediately -- WavRecorder's own empty-segment cleanup
            // deletes it rather than leaving a pointless silent file behind.
            service.stopRecording()
            showExternalMicMismatchDialog()
        }
    }

    /** Blocking: the user must explicitly choose, rather than recording silently continuing on an
     * input they didn't expect. Mirrors [confirmRecordWithoutExternalMic]'s wording/tone but for a
     * distinct situation -- an external mic *was* detected beforehand, it just didn't verify as
     * actually in use once recording tried to start. */
    private fun showExternalMicMismatchDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.mic_mismatch_title)
            .setMessage(R.string.mic_mismatch_message)
            .setCancelable(false)
            .setNegativeButton(R.string.mic_mismatch_retry) { _, _ ->
                refreshPreferredMicStatus()
                requestPermissionAndRecord()
            }
            .setPositiveButton(R.string.mic_mismatch_continue_phone) { _, _ ->
                beginRecording(acceptPhoneMic = true)
            }
            .show()
    }

    private fun updateRecordingStatus() {
        binding.statusText.text = if (currentPartNumber > 1) {
            getString(R.string.status_recording_part, currentPartNumber)
        } else {
            getString(R.string.status_recording)
        }
    }

    /** Friendly "Recording saved" summary shown once a session actually finalizes -- deliberately
     * never the raw filename (that stays available in the Library); [startedAt] is
     * [RecordingService.lastSessionStartedAtMillis], captured before [resetToIdle] runs, which is
     * both the displayed date/time and (paired with "now") the elapsed duration, with no file I/O
     * involved at all. */
    private fun showSavedSummary(startedAt: Long?) {
        binding.statusText.text = getString(R.string.status_saved_title)
        if (startedAt == null) return
        val dateText = SAVED_AT_FORMAT.format(Date(startedAt))
        val durationText = DurationFormatter.format(
            requireContext(), (System.currentTimeMillis() - startedAt).coerceAtLeast(0) / 1000
        )
        binding.statusDetailText.text = getString(R.string.status_saved_detail, dateText, durationText)
        binding.statusDetailText.visibility = View.VISIBLE
    }

    /** Two-line status shown once recording has actually started and [AudioRecord.getRoutedDevice]
     * has verified what's really being captured from -- the source of truth, superseding whatever
     * [updateIdleMicStatusLabel] guessed beforehand. */
    private fun updateMicDeviceLabel(info: MicrophoneInfo) {
        if (_binding == null) return
        val titleRes = when {
            !info.verified -> R.string.mic_status_unverified_title
            info.isExternal -> R.string.mic_status_verified_external_title
            else -> R.string.mic_status_verified_builtin_title
        }
        binding.micStatusTitle.text = getString(titleRes)
        binding.micStatusSubtitle.text = getString(R.string.mic_status_active_subtitle, info.label)
        setMicStatusDotColor(
            when {
                !info.verified -> R.color.status_preferred_purple
                info.isExternal -> R.color.status_verified_green
                else -> R.color.status_warning_orange
            }
        )
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
        when (val status = latestPreferredMicStatus) {
            is PreferredMicStatus.ExternalConnected -> {
                binding.micStatusTitle.text = getString(R.string.mic_status_connected_title, status.label)
                binding.micStatusSubtitle.text = getString(R.string.mic_status_connected_subtitle)
                setMicStatusDotColor(R.color.status_preferred_purple)
            }
            PreferredMicStatus.NoneDetected -> {
                binding.micStatusTitle.text = getString(R.string.mic_status_none_title)
                binding.micStatusSubtitle.text = getString(R.string.mic_status_none_subtitle)
                setMicStatusDotColor(R.color.status_warning_orange)
            }
        }
    }

    private fun setMicStatusDotColor(@ColorRes colorRes: Int) {
        binding.micStatusDot.backgroundTintList =
            ColorStateList.valueOf(ContextCompat.getColor(requireContext(), colorRes))
    }

    private fun resetToIdle() {
        binding.recordButton.text = getString(R.string.start_recording)
        binding.statusText.text = getString(R.string.status_idle)
        binding.statusDetailText.visibility = View.GONE
        binding.audioLevelSection.visibility = View.GONE
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

    companion object {
        private val SAVED_AT_FORMAT = SimpleDateFormat("MMM d, yyyy · h:mm a", Locale.getDefault())
        // Companion-scoped (not per-instance) so it survives this Fragment being recreated (e.g.
        // rotation) while RecordingService -- a longer-lived, separate component -- keeps its own
        // currentRequestId high-water-mark across that same recreation; a per-instance counter that
        // reset to 0 on recreation could mint an id low enough to be wrongly rejected as stale by a
        // service that had already seen higher ones from before the recreation. Only reset by a
        // fresh process (which also gives RecordingService a fresh instance/state), so the two
        // always stay consistent with each other.
        private val requestIdGenerator = AtomicLong(0)
    }
}
