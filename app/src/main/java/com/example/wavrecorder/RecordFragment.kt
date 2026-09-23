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
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

class RecordFragment : Fragment() {

    private var _binding: FragmentRecordBinding? = null
    private val binding get() = _binding!!

    private lateinit var destinationManager: DestinationManager
    private lateinit var recordingSettings: RecordingSettings
    private var currentTarget: OutputTarget? = null
    private var currentPartNumber = 1

    // Captured once, right after the binding is created (see onViewCreated) -- the record
    // button's own default, theme-driven (colorPrimary-based) tint/text, including MaterialButton's
    // correct disabled-state dimming. setRecordButtonRecording(false) restores exactly these,
    // rather than relying on backgroundTintList/setTextColor "reset to null" semantics that aren't
    // guaranteed to reproduce the original style-driven state precisely.
    private lateinit var defaultRecordButtonBackgroundTint: ColorStateList
    private lateinit var defaultRecordButtonTextColor: ColorStateList

    private var recordingService: RecordingService? = null
    // False from the moment onStart() requests a bind until a connection actually delivers the
    // service's real state (or the bind request itself is refused outright) -- recordingBusy()
    // treats "unknown" as "busy" (never as "idle") specifically so the microphone-test button
    // can't be enabled during this window: bindService() only reports whether the *request* was
    // accepted, not whether a recording might already be under way in the service on the other
    // end, and reading a still-null recordingService as "definitely idle" is exactly the bug this
    // closes. Reset to false again on disconnect (see serviceConnection.onServiceDisconnected)
    // since a lost connection means this Fragment no longer has live information either. internal
    // for tests -- see RecordFragmentServiceStateGateTest.
    internal var serviceStateKnown = false
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
    // The split duration captured for that same attempt at the moment Record was pressed (see
    // beginRecording()) -- carried alongside pendingRequestId so a connection landing later still
    // starts the session with the value the user had selected when they tapped, not whatever the
    // setting happens to be by then.
    internal var pendingSplitDuration: RecordingSplitDuration = RecordingSplitDuration.DEFAULT

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

    // internal (not private) + var so a test can substitute a MicTestSession wired to a fake
    // AudioSource, mirroring RecordingService's own recorder/destinationManager test seams.
    internal var micTestSession: MicTestSession = MicTestSession()
    // True from the moment the test button is tapped (before route verification even completes)
    // until it's fully stopped -- see startMicTest()/stopMicTest(). Gates real recording off
    // immediately, not just once the background test loop actually starts.
    private var micTestActive = false
    // Snapshotted the moment a test attempt begins: whether an external input was
    // detected/preferred beforehand -- mirrors expectedExternalMic's identical rationale for real
    // recording (see below). If true and the route MicTestSession actually verifies doesn't back
    // that up, the test must stop rather than silently continue against the built-in mic.
    private var expectedExternalMicForTest = false
    // Set synchronously from within MicTestSession's onMicrophoneInfo callback and only acted on
    // once MicTestSession.start() has actually returned -- mirrors pendingMicMismatch's identical
    // timing constraint below. MicTestSession.start() has not finished its own start transition
    // (generation/isActive/testThread) at the moment onMicrophoneInfo fires -- see that method's
    // doc -- so calling MicTestSession.stop() reentrantly from inside it here would race the
    // still-in-flight start() and could release the AudioSource out from under the background
    // thread start() is about to spin up against it.
    private var pendingTestMicMismatch = false
    // One-way per test session: true once a level has crossed MIC_TEST_SIGNAL_THRESHOLD, so the
    // "Signal detected" status is written at most once per test rather than toggling back and
    // forth on every throttled level update -- see updateMicTestSignalStatus().
    private var micTestSignalDetected = false
    // True from the moment resetMicTestUiForServiceUnavailable() runs (a service disconnect caught
    // mid-test) until a subsequent connection actually confirms genuine state -- lets
    // syncUiWithService() tell "this Fragment is showing a real, known idle state" apart from
    // "it's merely showing the honest 'service state unknown' status" when a reconnect reveals
    // idle, so the latter gets explicitly replaced with a real idle reset instead of being left
    // stuck (syncUiWithService's own else-branch otherwise only acts when there's an actual
    // pending outcome to display).
    private var micTestServiceUnavailableShown = false

    private enum class PendingPermissionAction { RECORD, MIC_TEST }
    // Which flow a just-launched permission request belongs to, consulted only from within
    // handlePermissionResult() once the launcher's callback actually fires -- see
    // requestPermissionAndRecord()/the test button's click listener for where this is set.
    private var pendingPermissionAction = PendingPermissionAction.RECORD

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
            // The real state is now known -- syncUiWithService() below (via its own leading
            // refreshTestButtonEnabled() call) is what actually defends against a mic test that's
            // unexpectedly still active against this now-known state; see that method's doc.
            serviceStateKnown = true
            if (pendingStart) {
                pendingStart = false
                startAndCheckMicRoute(service, pendingRequestId, pendingSplitDuration)
            }
            syncUiWithService()
            refreshTestButtonEnabled()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            recordingService?.listener = null
            recordingService = null
            // A lost connection means this Fragment no longer has live information about whether
            // recording is busy -- back to "unknown" (never "idle") until a new connection lands.
            serviceStateKnown = false
            if (micTestActive) {
                // Unlike stopMicTestForRecordingConflict()'s deliberately partial reset (used only
                // when a *known*-busy service connects and is about to render its own real
                // recording UI immediately afterward via syncUiWithService()), nothing renders
                // anything here -- recordingService is now null, so nothing will ever come along
                // to clean up a leftover "Testing microphone" status/waveform/level section on its
                // own. Every piece of mic-test UI state must be fully reset right here, exactly
                // once, alongside actually stopping/releasing the session.
                micTestSession.stop()
                resetMicTestUiForServiceUnavailable()
            } else {
                refreshTestButtonEnabled()
            }
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
                setRecordButtonRecording(true)
                binding.audioLevelSection.visibility = View.VISIBLE
                updateRecordingStatus()
                refreshTestButtonEnabled()
            }
        }

        override fun onError(e: Exception) {
            if (_binding != null) handleError(e)
        }

        override fun onStopping() {
            // Immediate feedback the instant a stop is requested (from either this screen's own
            // button or the notification, while visible) -- the actual outcome now arrives
            // asynchronously, up to a couple of seconds later (see RecordingService's
            // beginAsyncFinalize), so without this the status text would keep showing stale
            // "Recording…" text for that whole window. The button is also disabled for the same
            // window: RecordingService now structurally rejects a new start attempted while a
            // previous session is still finalizing (see ServiceState), but a tappable button that
            // still read "Stop Recording" during that gap would look actionable and, before this
            // fix, silently mutate the still-finalizing session's own ownership -- disabling it
            // here removes that confusing path entirely rather than only guarding against it
            // server-side. Re-enabled by resetToIdle() once a terminal outcome actually arrives.
            if (_binding != null) {
                binding.statusText.text = getString(R.string.status_finalizing)
                binding.recordButton.isEnabled = false
            }
            refreshTestButtonEnabled()
        }

        override fun onStartRejected() {
            // Defense in depth: the record button is already disabled for this exact window (see
            // onStopping() above), so this should be unreachable from a normal tap -- but a
            // still-in-flight ACTION_START Intent (e.g. a notification tap racing this screen's own
            // state) can reach RecordingService directly, bypassing this screen's button entirely.
            if (_binding != null) {
                Toast.makeText(requireContext(), R.string.recording_still_finalizing, Toast.LENGTH_SHORT).show()
            }
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
            is RecordingOutcome.Failed -> handleError(outcome.cause)
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
            val deniedMessage = if (pendingPermissionAction == PendingPermissionAction.MIC_TEST) {
                R.string.mic_test_permission_denied
            } else {
                R.string.permission_denied
            }
            Toast.makeText(requireContext(), deniedMessage, Toast.LENGTH_LONG).show()
            return
        }
        when (pendingPermissionAction) {
            // The test never touches POST_NOTIFICATIONS -- it never starts a foreground service or
            // shows a notification -- so unlike RECORD's flow below, RECORD_AUDIO alone is enough.
            PendingPermissionAction.MIC_TEST -> startMicTest()
            PendingPermissionAction.RECORD -> if (notificationPermissionDenied()) {
                confirmRecordWithoutNotificationPermission()
            } else {
                beginRecording()
            }
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
        // Captured immediately, before any state transition can touch them -- see the fields'
        // own doc.
        defaultRecordButtonBackgroundTint = binding.recordButton.backgroundTintList
            ?: ColorStateList.valueOf(
                MaterialColors.getColor(binding.recordButton, com.google.android.material.R.attr.colorPrimary)
            )
        defaultRecordButtonTextColor = binding.recordButton.textColors
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        destinationManager = DestinationManager(requireContext())
        recordingSettings = RecordingSettings(requireContext())
        updateDestinationLabel()
        bindSplitDurationToggle()

        binding.chooseFolderButton.setOnClickListener { folderPicker.launch(null) }
        binding.recordButton.setOnClickListener {
            when {
                recordingService?.isRecording == true -> confirmStopRecording()
                latestPreferredMicStatus == PreferredMicStatus.NoneDetected -> confirmRecordWithoutExternalMic()
                else -> requestPermissionAndRecord()
            }
        }
        binding.testMicButton.setOnClickListener {
            if (micTestActive) {
                stopMicTest()
            } else {
                pendingPermissionAction = PendingPermissionAction.MIC_TEST
                if (hasMicrophonePermission()) {
                    startMicTest()
                } else {
                    requestPermissionsLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                }
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

        // Unknown again for this fresh visibility window until a connection actually lands (see
        // serviceStateKnown's own doc) -- must be reset before the bindService() call below, not
        // after, so refreshTestButtonEnabled() at the end of this method never briefly reads a
        // stale "known" state left over from a previous visit.
        serviceStateKnown = false

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
        refreshTestButtonEnabled()
    }

    override fun onPause() {
        super.onPause()
        // MainActivity hosts this Fragment inside a 2-page ViewPager2/FragmentStateAdapter -- with
        // only 2 pages, switching to the Library tab leaves this one in the STARTED state (still
        // "kept alive" as the adjacent page), receiving only onPause(), never onStop(). Without
        // this override, mic testing would keep running with its AudioRecord held open and its
        // waveform meter updating while nobody can see it. Real recording is entirely unaffected --
        // it deliberately keeps running via the foreground service regardless of tab visibility,
        // and nothing here touches recordingService/beginRecording/etc. Mirrors onStop()'s
        // identical stop+reset pair, so a later onStop() (should this screen actually be stopped
        // too, e.g. the whole Activity backgrounding) finds micTestActive already false and safely
        // no-ops rather than releasing/resetting a second time.
        if (micTestActive) {
            micTestSession.stop()
            resetMicTestUi()
        }
    }

    override fun onStop() {
        super.onStop()
        audioManager?.unregisterAudioDeviceCallback(audioDeviceCallback)
        audioManager = null

        // Belt-and-suspenders alongside onPause()'s identical guard above: onPause() always runs
        // before onStop() in the normal Fragment lifecycle, so micTestActive is already false by
        // the time this runs in practice -- kept regardless in case some future path reaches
        // onStop() without onPause() having run first.
        if (micTestActive) {
            micTestSession.stop()
            resetMicTestUi()
        }

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
        refreshTestButtonEnabled()
        val service = recordingService ?: return
        // Captured, then unconditionally cleared, before branching: whichever branch below runs,
        // a real connection has now landed, so this Fragment is no longer showing the honest
        // "service state unknown" status -- see micTestServiceUnavailableShown's own doc. The
        // captured value is what lets the idle (else) branch below tell a genuine, already-known
        // idle reconnect apart from one recovering from that unavailable status, without needing
        // the flag itself to still read true once we're past this point.
        val wasShowingServiceUnavailable = micTestServiceUnavailableShown
        micTestServiceUnavailableShown = false
        if (service.isRecording) {
            currentTarget = service.lastTarget
            currentPartNumber = service.lastPartNumber
            setRecordButtonRecording(true)
            binding.recordButton.isEnabled = true
            binding.audioLevelSection.visibility = View.VISIBLE
            binding.statusDetailText.visibility = View.GONE
            updateRecordingStatus()
            service.lastMicrophoneInfo?.let { updateMicDeviceLabel(it) }
        } else if (service.isFinalizing) {
            // Reconnecting (e.g. after rotation) while a previous session's background join is
            // still in flight -- mirrors onStopping()'s live callback so this screen shows the
            // same "Finalizing…"/disabled state it would have if it had never been torn down.
            binding.statusText.text = getString(R.string.status_finalizing)
            binding.recordButton.isEnabled = false
        } else {
            // Catches this screen up on whatever happened while it wasn't around to see it live
            // (or wasn't bound yet) -- see RecordingService.consumePendingOutcome.
            val outcome = service.consumePendingOutcome()
            if (outcome != null) {
                displayPendingOutcome(outcome)
            } else if (wasShowingServiceUnavailable) {
                // Genuinely idle now confirmed -- replace the "Reconnecting…" status left behind
                // by resetMicTestUiForServiceUnavailable() with a real idle reset, rather than
                // leaving it stuck (there's no pending outcome here to otherwise trigger any
                // update at all).
                resetToIdle()
            }
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
        pendingPermissionAction = PendingPermissionAction.RECORD
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
        // Captured exactly once per attempt, here: the session keeps this value for its whole
        // lifetime even if the user changes the setting while it's recording.
        val splitDuration = recordingSettings.splitDuration
        pendingSplitDuration = splitDuration

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
            startAndCheckMicRoute(service, requestId, splitDuration)
        } else {
            pendingStart = true
        }
        refreshTestButtonEnabled()
    }

    /** Starts recording and then, only once [RecordingService.startRecording] has actually
     * returned, checks whether [pendingMicMismatch] got set: [MicrophoneInfo] is delivered
     * synchronously from inside [RecordingService.startRecording] (via [WavRecorder.start]),
     * *before* the recorder's own `isRecording` flag even flips true -- calling
     * [RecordingService.stopRecording] reentrantly from within that callback would silently no-op
     * (stopRecording() bails out whenever the recorder doesn't consider itself active yet) and
     * let recording continue unnoticed on the wrong input. Deferring the check to right after
     * startRecording() returns avoids that race entirely. */
    private fun startAndCheckMicRoute(
        service: RecordingService,
        requestId: Long,
        splitDuration: RecordingSplitDuration
    ) {
        pendingMicMismatch = false
        service.startRecording(requestId, splitDuration.minutes)
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

    /** Whether real recording currently occupies (or is about to occupy) this screen's
     * attention -- pending a bound connection, or genuinely PREPARING/RECORDING/FINALIZING once
     * one exists. Single source of truth for both directions of mutual exclusion with the
     * microphone test: gates [startMicTest] from beginning, and (via [refreshTestButtonEnabled])
     * keeps [binding.testMicButton] disabled for the same window. */
    /** [serviceStateKnown] being false counts as busy, never as idle -- see that field's own doc:
     * this is what keeps the microphone-test button disabled for the whole window between
     * onStart()'s bindService() call and a connection (or a lost one) actually revealing the real
     * state, instead of optimistically reading a still-null [recordingService] as "nothing to
     * worry about". */
    private fun recordingBusy(): Boolean =
        !serviceStateKnown || pendingStart || recordingService?.let { it.state != ServiceState.IDLE } == true

    /** Re-derives [binding.testMicButton]'s enabled state from live recording state -- called from
     * every point that can change it. Deliberately not gated on [micTestActive] itself: the button
     * must stay enabled (tappable, to stop) for the whole time a test is active, which is exactly
     * when [recordingBusy] is guaranteed false (mutual exclusion), and disabled whenever recording
     * genuinely is busy, which is exactly when a test can never be active in the first place.
     *
     * Also the single defensive backstop for the *other* direction of that same guarantee: if a
     * test is (still, or unexpectedly) active at the exact moment recording becomes busy -- a
     * delayed service connection revealing a pre-existing recording, or a notification/other
     * component starting one directly while this screen is visible and showing a test -- this is
     * where it gets shut down, since every call site above that can change recording's busy state
     * already calls this. Deliberately checked here rather than duplicated at each individual call
     * site.
     *
     * internal (not private) so a test can assert the mutual-exclusion gate directly against a
     * synchronously-set field (e.g. [pendingStart]) without needing to drive a full async
     * recording start just to observe it. */
    internal fun refreshTestButtonEnabled() {
        if (_binding == null) return
        val busy = recordingBusy()
        if (micTestActive && busy) {
            stopMicTestForRecordingConflict()
        }
        binding.testMicButton.isEnabled = !busy
    }

    /** Defensive shutdown for when real recording is found to be (or becomes) busy while a
     * microphone test is unexpectedly still active -- see [refreshTestButtonEnabled]'s doc for the
     * scenarios this guards against. Releases the test's [AudioSource] exactly like [stopMicTest],
     * but -- unlike [resetMicTestUi] -- never touches [binding.statusText]/
     * [binding.audioLevelSection]/[binding.waveformView]/the mic status label: those are about to
     * be driven by the real recording's own state by whichever caller (syncUiWithService,
     * onSegmentStarted, ...) triggered this via [refreshTestButtonEnabled], and resetting them to
     * the idle mic-test UI here would either flash it uselessly or clobber the real recording UI
     * rendered right after this returns. */
    private fun stopMicTestForRecordingConflict() {
        micTestSession.stop()
        micTestActive = false
        if (_binding == null) return
        binding.testMicButton.text = getString(R.string.test_microphone)
    }

    /** Starts a live microphone check -- never a file, a journal record, or the recording
     * foreground service; see [MicTestSession]'s own doc. Disables real recording immediately
     * (not only once the background loop actually starts), reuses the exact same post-route-
     * verification status display ([updateMicDeviceLabel]) and level meter ([binding.waveformView])
     * real recording itself uses, since the two are never active at the same time. */
    private fun startMicTest() {
        if (micTestActive || recordingBusy()) return
        micTestActive = true
        micTestSignalDetected = false
        // Snapshotted now, exactly like beginRecording()'s identical expectedExternalMic --
        // whatever AudioManager reports changing mid-test doesn't retroactively change what this
        // one attempt promised the user.
        expectedExternalMicForTest = latestPreferredMicStatus is PreferredMicStatus.ExternalConnected
        pendingTestMicMismatch = false
        binding.testMicButton.text = getString(R.string.stop_test)
        binding.recordButton.isEnabled = false
        binding.waveformView.clear()
        binding.audioLevelSection.visibility = View.VISIBLE
        binding.statusText.text = getString(R.string.mic_test_status_active)
        binding.statusDetailText.text = getString(R.string.mic_test_status_waiting)
        binding.statusDetailText.visibility = View.VISIBLE
        micTestSession.start(
            context = requireContext(),
            onMicrophoneInfo = { info ->
                if (_binding != null) updateMicDeviceLabel(info)
                // Fires synchronously from inside start(), before its own start transition has
                // finished (see pendingTestMicMismatch's doc) -- recorded here, not acted on until
                // start() actually returns below.
                if (expectedExternalMicForTest && !(info.verified && info.isExternal)) {
                    pendingTestMicMismatch = true
                }
            },
            onLevel = { level ->
                if (_binding != null) {
                    binding.waveformView.addAmplitude(level)
                    updateMicTestSignalStatus(level)
                }
            },
            onError = { e -> handleMicTestError(e) }
        )
        // Only safe to act on now that start() has fully returned -- see pendingTestMicMismatch's
        // doc for why calling stop() from inside onMicrophoneInfo itself would race start()'s own
        // still-in-flight generation/isActive/testThread setup.
        if (pendingTestMicMismatch) {
            pendingTestMicMismatch = false
            handleMicTestMismatch()
        }
    }

    private fun stopMicTest() {
        micTestSession.stop()
        resetMicTestUi()
    }

    /** [MicTestSession.start]'s onError contract requires the caller to call [MicTestSession.stop]
     * itself to actually release the underlying [AudioSource] -- see that method's doc for why. */
    private fun handleMicTestError(e: Exception) {
        micTestSession.stop()
        if (_binding == null) {
            micTestActive = false
            return
        }
        val message = when (e) {
            is MicrophoneDisconnectedException -> getString(R.string.mic_test_disconnected_error)
            is MicrophoneRouteChangedException -> getString(R.string.mic_test_route_changed_error)
            else -> getString(R.string.mic_test_error, e.message)
        }
        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
        resetMicTestUi()
    }

    /** An external microphone was expected/preferred for this attempt, but the route
     * [MicTestSession] actually verified once it started didn't back that up (built-in, or
     * unverifiable) -- mirrors [showExternalMicMismatchDialog]'s real-recording equivalent, but as
     * a plain, honest toast rather than a retry dialog: unlike real recording, nothing has been
     * captured or saved either way, so there's nothing to decide -- the user just taps the test
     * button again once the microphone is actually reachable. */
    private fun handleMicTestMismatch() {
        micTestSession.stop()
        if (_binding == null) {
            micTestActive = false
            return
        }
        Toast.makeText(requireContext(), getString(R.string.mic_test_route_mismatch_error), Toast.LENGTH_LONG).show()
        resetMicTestUi()
    }

    /** One-way per test session (see [micTestSignalDetected]): the first time a level crosses
     * [MIC_TEST_SIGNAL_THRESHOLD], replaces the neutral "Waiting for signal…" sub-status with
     * "Signal detected" and never writes again -- [onLevel] already only fires at a throttled rate
     * (see [MicTestSession]'s own doc), and this adds a second layer of throttling on top so the
     * status text itself is written at most once per test, never on every update. */
    private fun updateMicTestSignalStatus(level: Float) {
        if (_binding == null || micTestSignalDetected || level < MIC_TEST_SIGNAL_THRESHOLD) return
        micTestSignalDetected = true
        binding.statusDetailText.text = getString(R.string.mic_test_status_signal_detected)
    }

    private fun resetMicTestUi() {
        micTestActive = false
        if (_binding == null) return
        binding.testMicButton.text = getString(R.string.test_microphone)
        binding.recordButton.isEnabled = true
        binding.audioLevelSection.visibility = View.GONE
        binding.waveformView.clear()
        binding.statusText.text = getString(R.string.status_idle)
        binding.statusDetailText.visibility = View.GONE
        updateIdleMicStatusLabel()
        refreshTestButtonEnabled()
    }

    /** Mirrors [resetMicTestUi] exactly -- same button/level-section/waveform cleanup, same
     * trailing [refreshTestButtonEnabled] call -- except the headline never claims the definite,
     * known-idle "Ready to record" status: the service connection was just lost, not confirmed
     * idle, and [recordingBusy] already treats that as busy (never idle) for exactly this reason --
     * the status text must say the same honest thing rather than contradicting it. The record
     * button is still re-enabled (mirroring the same "connecting" window at a fresh [onStart],
     * where it's tappable by default and a tap simply queues [pendingStart] until a connection
     * lands) -- what must not happen is the status text overclaiming a verified idle state, which
     * is the actual "misleadingly available" risk here, not the button's own tappability.
     * [micTestServiceUnavailableShown] records that this (not a genuine idle reset) is what's
     * currently shown, so a later reconnection that turns out to be idle (see [syncUiWithService])
     * knows to explicitly replace it rather than leaving it stuck. */
    private fun resetMicTestUiForServiceUnavailable() {
        micTestActive = false
        micTestServiceUnavailableShown = true
        if (_binding == null) return
        binding.testMicButton.text = getString(R.string.test_microphone)
        binding.recordButton.isEnabled = true
        binding.audioLevelSection.visibility = View.GONE
        binding.waveformView.clear()
        binding.statusText.text = getString(R.string.status_reconnecting)
        binding.statusDetailText.visibility = View.GONE
        updateIdleMicStatusLabel()
        refreshTestButtonEnabled()
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

    /** Applies (or clears) the destructive/red styling that must be visible exactly when
     * [binding.recordButton] represents "Stop recording" -- an actual, real recording, never the
     * microphone-test button (a separate, independent button/style entirely). Colors always come
     * from the theme's own `?attr/colorError`/`?attr/colorOnError` (resolved via
     * [MaterialColors.getColor], the same helper this file already uses for
     * [defaultRecordButtonBackgroundTint]'s own fallback), never a hardcoded literal.
     *
     * Built directly in Kotlin via [buildDisabledAwareColorStateList] rather than a `<selector>`
     * XML resource referencing `?attr/...` items: that XML pattern is genuinely fragile --
     * Robolectric's own resource inflater doesn't resolve a theme-attribute color reference nested
     * inside a `<selector>`'s `<item>` reliably (confirmed directly: it silently produced Android's
     * unresolved-resource placeholder color instead of the real theme color, in a setup where the
     * exact same attribute resolves correctly via a direct [MaterialColors.getColor] call), and
     * this sidesteps that class of resolution issue on any platform rather than only working around
     * it for tests. */
    private fun setRecordButtonRecording(isRecording: Boolean) {
        binding.recordButton.text = getString(
            if (isRecording) R.string.stop_recording else R.string.start_recording
        )
        if (isRecording) {
            binding.recordButton.backgroundTintList = buildDisabledAwareColorStateList(
                com.google.android.material.R.attr.colorError, disabledAlpha = 0.38f
            )
            binding.recordButton.setTextColor(
                buildDisabledAwareColorStateList(com.google.android.material.R.attr.colorOnError, disabledAlpha = 0.6f)
            )
        } else {
            binding.recordButton.backgroundTintList = defaultRecordButtonBackgroundTint
            binding.recordButton.setTextColor(defaultRecordButtonTextColor)
        }
    }

    /** A two-state [ColorStateList] for [colorAttr] (a theme color attribute, e.g.
     * `?attr/colorError`): the theme's real color when enabled, and the same color at
     * [disabledAlpha] when disabled -- mirroring the reduced-alpha treatment MaterialButton's own
     * default (colorPrimary-driven) disabled state already uses elsewhere in this app, so the
     * brief disabled window during "Finalizing…" (see [RecordFragment.Listener.onStopping]) looks
     * consistent with every other disabled control rather than introducing a one-off treatment. */
    private fun buildDisabledAwareColorStateList(@androidx.annotation.AttrRes colorAttr: Int, disabledAlpha: Float): ColorStateList {
        val color = MaterialColors.getColor(binding.recordButton, colorAttr)
        val disabledColor = MaterialColors.compositeARGBWithAlpha(color, (disabledAlpha * 255).toInt())
        return ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(disabledColor, color)
        )
    }

    private fun setMicStatusDotColor(@ColorRes colorRes: Int) {
        binding.micStatusDot.backgroundTintList =
            ColorStateList.valueOf(ContextCompat.getColor(requireContext(), colorRes))
    }

    private fun resetToIdle() {
        // Defensive: whatever the caller, a real idle reset means this Fragment is no longer
        // showing the "service state unknown" status -- see micTestServiceUnavailableShown's own
        // doc. syncUiWithService() already clears this itself before calling here, but this keeps
        // the invariant true regardless of which of resetToIdle()'s several other callers runs.
        micTestServiceUnavailableShown = false
        setRecordButtonRecording(false)
        binding.recordButton.isEnabled = true
        binding.statusText.text = getString(R.string.status_idle)
        binding.statusDetailText.visibility = View.GONE
        binding.audioLevelSection.visibility = View.GONE
        updateIdleMicStatusLabel()
        binding.waveformView.clear()
        refreshTestButtonEnabled()
    }

    /** Reflects the persisted choice, then persists every change the user makes. Deliberately left
     * enabled while recording: a change only ever applies to the next session (see
     * [beginRecording]), which the hint under the toggle says. */
    private fun bindSplitDurationToggle() {
        binding.splitDurationToggle.check(splitButtonIdFor(recordingSettings.splitDuration))
        binding.splitDurationToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            splitDurationForButtonId(checkedId)?.let { recordingSettings.splitDuration = it }
        }
    }

    private fun splitButtonIdFor(duration: RecordingSplitDuration): Int = when (duration) {
        RecordingSplitDuration.MINUTES_30 -> R.id.split30Button
        RecordingSplitDuration.MINUTES_45 -> R.id.split45Button
        RecordingSplitDuration.MINUTES_60 -> R.id.split60Button
    }

    private fun splitDurationForButtonId(buttonId: Int): RecordingSplitDuration? = when (buttonId) {
        R.id.split30Button -> RecordingSplitDuration.MINUTES_30
        R.id.split45Button -> RecordingSplitDuration.MINUTES_45
        R.id.split60Button -> RecordingSplitDuration.MINUTES_60
        else -> null
    }

    private fun updateDestinationLabel() {
        binding.destinationLabel.text = getString(R.string.destination_label, destinationManager.displayName())
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Belt-and-suspenders alongside onStop()'s identical guard: onStop() should always run
        // first in the normal Fragment lifecycle, but this ensures a live test's AudioRecord is
        // never left open past this screen's own view existing, regardless of the exact teardown
        // path taken.
        if (micTestActive) {
            micTestSession.stop()
            micTestActive = false
        }
        _binding = null
    }

    companion object {
        // A modest floor above pure silence/room noise (see peakAmplitude's -45dBFS..0dBFS
        // mapping onto 0f..1f) -- picked to reliably trigger on a normal speaking voice or a
        // tap/clap without false-triggering on typical quiet-room background noise. Not a precise
        // SNR measurement, just enough to tell the user their input is actually reaching the meter.
        private const val MIC_TEST_SIGNAL_THRESHOLD = 0.15f
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
