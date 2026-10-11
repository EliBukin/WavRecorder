package bukin.wavrecorder

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Bundle
import android.os.Looper
import android.os.Parcel
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.AudioDeviceInfoBuilder
import org.robolectric.shadows.ShadowActivity
import org.robolectric.shadows.ShadowToast
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * What survives the app's process being killed and started again -- and what must not be resumed
 * by it: a pending permission request, a persisted outcome, and an interrupted recording.
 *
 * What the harness simulates, in one JVM (Robolectric can't end a process):
 *  - **Kept**, as Android keeps them: SharedPreferences (the pending outcome, the segment journal),
 *    the recording files, the granted permissions, and the Activity's saved-instance Bundle --
 *    round-tripped through a Parcel, the form the system holds it in for a killed process.
 *  - **Gone**, as in a new process: every Activity, Fragment and RecordingService instance -- a new
 *    "process" builds fresh ones, from the saved Bundle where Android would -- and everything
 *    [RecordingOwnership] holds in memory. The killed process's objects get no lifecycle callbacks
 *    (a killed process runs none); they are only cleaned up once the test is over.
 *  - A permission result reaches the restored Activity as the system delivers it: through the
 *    Activity, with the request code the killed process asked with.
 *  - A new process has a new [ProcessInstanceId]; here the killed process's journal record is
 *    re-tagged with another owner id instead.
 * **Not simulated**: the JVM itself restarting (static state such as the shared audio worker), the
 * system's own kill-and-restore sequencing, and a new Application instance -- those remain for the
 * on-device check. [androidx.fragment.app.testing.FragmentScenario.recreate] is not used here: it
 * keeps the process, which is exactly what this must not.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ProcessDeathRestorationTest {

    @get:Rule
    val isolation = TestIsolation()

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun app(): Application = ApplicationProvider.getApplicationContext()

    private val processes = mutableListOf<AppProcess>()

    @Before
    fun setUp() {
        shadowOf(app()).denyPermissions(Manifest.permission.RECORD_AUDIO)
        shadowOf(app()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        // An external input, so a Record tap goes straight to the permission request.
        shadowOf(app().getSystemService(AudioManager::class.java)).setInputDevices(
            listOf(AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_USB_DEVICE).build())
        )
    }

    @After
    fun tearDown() {
        // The test is over: now every process's objects are cleaned up, the killed ones included.
        processes.forEach { it.cleanUp() }
        RecordingOwnership.resetForTest()
    }

    /**
     * One app process: its own RecordingService (bound as the service), and -- unless a test gives
     * it a working [recorder] -- a recorder and a microphone test that can't open a microphone
     * unless the test runs their startup worker, so any attempt to is visible and nothing native
     * is touched.
     */
    private inner class AppProcess(recorder: WavRecorder? = null, dir: File? = null) {
        val recorderWorker = ManualExecutor()
        val micTestWorker = ManualExecutor()
        val service: RecordingService = Robolectric.buildService(RecordingService::class.java).create().get()
        private var controller: ActivityController<MainActivity>? = null

        init {
            service.recorder = recorder ?: WavRecorder(startup = AudioStartup(recorderWorker, ManualExecutor()))
            val files = dir ?: tempFolder.newFolder()
            service.destinationManager = object : DestinationManager(app()) {
                override fun createOutputFile(fileName: String): OutputTarget = OutputTarget.FileTarget(File(files, fileName))
            }
            shadowOf(app()).setComponentNameAndServiceForBindService(
                ComponentName(app(), RecordingService::class.java), service.LocalBinder()
            )
            processes += this
        }

        val activity: MainActivity get() = controller!!.get()

        /** Opens the app -- a cold start, or, with [saved], the system restoring the killed one's screen. */
        fun open(saved: Bundle? = null): AppProcess {
            val c = Robolectric.buildActivity(MainActivity::class.java)
            controller = c
            if (saved == null) c.setup() else c.setup(saved)
            idle()
            recordScreen().micTestSession = MicTestSession(startup = AudioStartup(micTestWorker, ManualExecutor()))
            return this
        }

        fun recordScreen(): RecordFragment = activity.supportFragmentManager.fragments.filterIsInstance<RecordFragment>().first()

        fun tap(id: Int) {
            recordScreen().requireView().findViewById<View>(id).performClick()
            idle()
        }

        fun lastPermissionRequest(): ShadowActivity.PermissionsRequest = shadowOf(activity).lastRequestedPermission

        /** The system delivering the user's answer to [request] to this process's Activity. */
        fun answer(request: ShadowActivity.PermissionsRequest, granted: Boolean) {
            if (granted) shadowOf(app()).grantPermissions(Manifest.permission.RECORD_AUDIO)
            else shadowOf(app()).denyPermissions(Manifest.permission.RECORD_AUDIO)
            val results = IntArray(request.requestedPermissions.size) {
                if (granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
            }
            activity.onRequestPermissionsResult(request.requestCode, request.requestedPermissions, results)
            idle()
        }

        /** The process is killed: everything but what Android keeps is gone. Returns the saved Bundle. */
        fun die(): Bundle {
            val saved = Bundle()
            controller?.saveInstanceState(saved)
            RecordingOwnership.simulateProcessDeathForTest()
            return saved.throughParcel()
        }

        /** Nothing started a recording or a microphone test, or asked for a microphone. */
        fun assertNothingStarted() {
            assertEquals("no recording start was requested", emptyList<String>(), recordingStarts())
            assertEquals(ServiceState.IDLE, service.state)
            assertEquals("the recorder never asked for a microphone", 0, recorderWorker.pending)
            assertEquals("the microphone test never asked for one", 0, micTestWorker.pending)
            assertFalse(recordScreen().micTestSession.starting || recordScreen().micTestSession.active)
        }

        fun statusText(): String = recordScreen().requireView().findViewById<TextView>(R.id.statusText).text.toString()

        fun cleanUp() {
            service.recorder.stop()
            recorderWorker.runAll()
            controller?.let { if (!it.get().isDestroyed) it.pause().stop().destroy() }
            idle()
        }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun drainStartedServices() {
        while (shadowOf(app()).nextStartedService != null) Unit
    }

    /** Every request to start recording made since the last check (consumed). Other service intents
     * -- e.g. retracting a start that was never made -- aren't starts. */
    private fun recordingStarts(): List<String> = generateSequence { shadowOf(app()).nextStartedService }
        .mapNotNull { it.action }.filter { it == RecordingService.ACTION_START }.toList()

    /** What the system does with a killed process's saved state: it keeps it as a Parcel. */
    private fun Bundle.throughParcel(): Bundle {
        val out = Parcel.obtain()
        val bytes = try {
            out.writeBundle(this)
            out.marshall()
        } finally {
            out.recycle()
        }
        val back = Parcel.obtain()
        try {
            back.unmarshall(bytes, 0, bytes.size)
            back.setDataPosition(0)
            return back.readBundle(RecordFragment::class.java.classLoader)!!
        } finally {
            back.recycle()
        }
    }

    /** The Record screen's own saved state, found inside the Activity's (Bundles nest Bundles). */
    private fun Bundle.recordScreenState(): Bundle {
        val found = mutableListOf<Bundle>()
        fun walk(bundle: Bundle) {
            bundle.classLoader = RecordFragment::class.java.classLoader
            if (bundle.containsKey(PENDING_PERMISSION_KEY)) found += bundle
            bundle.keySet().toList().forEach { key ->
                @Suppress("DEPRECATION")
                val value = try { bundle.get(key) } catch (_: RuntimeException) { null }
                if (value is Bundle) walk(value)
            }
        }
        walk(this)
        return found.single()
    }

    private companion object {
        const val PENDING_PERMISSION_KEY = "pending_permission_action"
    }

    // ---- A permission request pending when the process died --------------------------------------

    /** Process A asks for the microphone for [button], then dies with the system dialog up. */
    private fun diedWhileAsking(button: Int): Pair<Bundle, ShadowActivity.PermissionsRequest> {
        shadowOf(app()).denyPermissions(Manifest.permission.RECORD_AUDIO) // not yet granted: the dialog is up
        val a = AppProcess().open()
        a.tap(button)
        val request = a.lastPermissionRequest()
        assertTrue(Manifest.permission.RECORD_AUDIO in request.requestedPermissions)
        drainStartedServices()
        return a.die() to request
    }

    @Test
    fun `a recording's pending request survives process death, and only the user's grant starts the recording`() {
        val (saved, request) = diedWhileAsking(R.id.recordButton)

        val b = AppProcess().open(saved)
        b.assertNothingStarted()

        b.answer(request, granted = true)
        assertEquals(listOf(RecordingService.ACTION_START), recordingStarts())
        assertFalse("never a microphone test", b.recordScreen().micTestSession.starting)
        assertEquals(0, b.micTestWorker.pending)
    }

    @Test
    fun `a microphone test's pending request survives process death and stays a microphone test`() {
        val (saved, request) = diedWhileAsking(R.id.testMicButton)

        val b = AppProcess().open(saved)
        b.assertNothingStarted()

        b.answer(request, granted = true)
        assertTrue(b.recordScreen().micTestSession.starting)
        assertEquals("never a recording", emptyList<String>(), recordingStarts())
        assertEquals(ServiceState.IDLE, b.service.state)
    }

    @Test
    fun `a denial delivered after process death starts nothing, and says why`() {
        val (saved, request) = diedWhileAsking(R.id.recordButton)

        val b = AppProcess().open(saved)
        b.answer(request, granted = false)

        b.assertNothingStarted()
        assertEquals(app().getString(R.string.permission_denied), ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun `with the pending request missing from the restored state, a grant starts nothing`() {
        val (saved, request) = diedWhileAsking(R.id.recordButton)
        saved.recordScreenState().remove(PENDING_PERMISSION_KEY)

        val b = AppProcess().open(saved)
        b.answer(request, granted = true)
        b.assertNothingStarted()
    }

    @Test
    fun `an unknown or malformed saved request starts nothing`() {
        for (corrupt in listOf<(Bundle) -> Unit>(
            { it.putString(PENDING_PERMISSION_KEY, "RECORD_EVERYTHING") },
            { it.putInt(PENDING_PERMISSION_KEY, 1) }
        )) {
            val (saved, request) = diedWhileAsking(R.id.recordButton)
            corrupt(saved.recordScreenState())

            val b = AppProcess().open(saved)
            b.answer(request, granted = true)
            b.assertNothingStarted()
        }
    }

    @Test
    fun `a request already answered before the process died is not answered again`() {
        val a = AppProcess().open()
        a.tap(R.id.recordButton)
        val request = a.lastPermissionRequest()
        a.answer(request, granted = false) // consumed: denied, in process A
        drainStartedServices()
        val saved = a.die()

        val b = AppProcess().open(saved)
        b.answer(request, granted = true) // the same answer, arriving again
        b.assertNothingStarted()
    }

    @Test
    fun `a restored request is stale once a recording has started some other way - the grant starts nothing new`() {
        val (saved, request) = diedWhileAsking(R.id.recordButton)

        val b = AppProcess().open(saved)
        b.service.startRecording(99L) // e.g. from the notification, before the answer arrives
        drainStartedServices()
        b.answer(request, granted = true)

        assertEquals("no second start", emptyList<String>(), recordingStarts())
        assertEquals(99L, b.service.currentRequestId)
    }

    // ---- Capture is never resumed by restoration -------------------------------------------------------

    /** Silence, paced like a microphone. */
    private class Silence : AudioSource {
        override fun startRecording() {}
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            Thread.sleep(2)
            buffer.fill(0, offset, offset + length)
            return length
        }
        override fun stop() {}
        override fun release() {}
    }

    @Test
    fun `a recording in progress when the process died is not resumed by restoring its screen`() {
        shadowOf(app()).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val worker = ManualExecutor()
        val deliver = ManualExecutor()
        val a = AppProcess(WavRecorder(startup = AudioStartup(worker, deliver),
            openAudioSource = { WavRecorder.RecorderConfig(Silence(), 48000, 960) })).open()
        a.service.startRecording(1L)
        worker.runAll()
        deliver.runAll()
        idle()
        assertTrue("process A is recording", a.service.isRecording)
        drainStartedServices()
        val saved = a.die()

        val b = AppProcess().open(saved)
        b.assertNothingStarted()
        assertEquals(app().getString(R.string.status_idle), b.statusText())
        assertTrue(b.recordScreen().requireView().findViewById<View>(R.id.recordButton).isEnabled)
    }

    // ---- Outcomes persisted by the process that died -----------------------------------------------------

    /** Paced audio: [chunks] chunks of 64 bytes, then empty reads. */
    private class Audio(private val chunks: Int) : AudioSource {
        val reads = AtomicInteger()
        override fun startRecording() {}
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            Thread.sleep(1)
            if (reads.incrementAndGet() > chunks) return 0
            buffer.fill(7, offset, offset + 64)
            return 64
        }
        override fun stop() {}
        override fun release() {}
    }

    private class FailingWriter(private val channel: FileChannel, private val failAtWrite: Int, private val failPatch: Boolean) : SegmentWriter {
        private var audioWrites = 0
        override fun write(buffer: ByteBuffer): Int {
            if (channel.position() >= 44 && ++audioWrites == failAtWrite) throw IOException("the storage failed")
            return channel.write(buffer)
        }
        override fun position() = channel.position()
        override fun position(newPosition: Long) {
            if (failPatch && newPosition == 0L && channel.position() > 44) throw IOException("the header patch failed")
            channel.position(newPosition)
        }
        override fun close() = channel.close()
    }

    /** Process A records unattended (no screen), ends as [end] makes it, persists its outcome, and dies. */
    private fun diedAfterRecording(failAtWrite: Int = 0, failPatch: Boolean = false) {
        shadowOf(app()).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val source = Audio(chunks = 3)
        val a = AppProcess(WavRecorder(startup = ImmediateStartup,
            openAudioSource = { WavRecorder.RecorderConfig(source, 48000, 64) },
            wrapChannel = { FailingWriter(it, failAtWrite, failPatch) }))
        a.service.startRecording(1L)
        val deadline = System.currentTimeMillis() + 5000
        while (a.service.pendingOutcomeStore.peekSessionId() == null && source.reads.get() <= 3 && System.currentTimeMillis() < deadline) {
            idle()
            Thread.sleep(2)
        }
        if (a.service.isRecording) a.service.stopAndAwaitFinalization()
        val persistDeadline = System.currentTimeMillis() + 5000
        while ((a.service.isFinalizing || a.service.pendingOutcomeStore.peekSessionId() == null) && System.currentTimeMillis() < persistDeadline) {
            idle()
            Thread.sleep(2)
        }
        check(a.service.pendingOutcomeStore.peekSessionId() != null) { "process A persisted no outcome" }
        drainStartedServices()
        a.die()
    }

    /** Process B starts cold and shows what A left; process C, started after it, shows nothing more. */
    private fun restartTwice(check: (AppProcess) -> Unit) {
        val toastsBefore = ShadowToast.shownToastCount()
        val b = AppProcess().open()
        check(b)
        b.assertNothingStarted()
        assertEquals(ServiceState.IDLE, b.service.state)
        val toastsAfterB = ShadowToast.shownToastCount()
        assertTrue("B showed at most one message", toastsAfterB - toastsBefore <= 1)
        b.die()

        val c = AppProcess().open()
        c.assertNothingStarted()
        assertEquals("shown once: nothing more in C", toastsAfterB, ShadowToast.shownToastCount())
        assertEquals(app().getString(R.string.status_idle), c.statusText())
    }

    @Test
    fun `a saved recording persisted before the process died is shown once, with its duration`() {
        diedAfterRecording()
        restartTwice { b ->
            assertEquals(app().getString(R.string.status_saved_title), b.statusText())
        }
    }

    @Test
    fun `a partial save persisted before the process died is shown once, as saved up to the failure`() {
        diedAfterRecording(failAtWrite = 2)
        restartTwice {
            assertTrue(ShadowToast.showedToast(app().getString(R.string.recording_incomplete_error)))
        }
    }

    @Test
    fun `a nothing-saved outcome persisted before the process died is shown once, as nothing saved`() {
        diedAfterRecording(failAtWrite = 1)
        restartTwice { b ->
            assertTrue(ShadowToast.showedToast(app().getString(R.string.recording_not_saved_error)))
            assertNotEquals(app().getString(R.string.status_saved_title), b.statusText())
        }
    }

    @Test
    fun `a finalization failure persisted before the process died is shown once, as needing recovery`() {
        diedAfterRecording(failPatch = true)
        restartTwice { b ->
            val needsRecovery = app().getString(R.string.recording_needs_recovery, "", "").substringBefore(":")
            assertTrue(b.statusText(), b.statusText().startsWith(needsRecovery))
        }
    }

    @Test
    fun `an older outcome superseded by a later session delivered live is not shown after a restart`() {
        diedAfterRecording(failAtWrite = 1) // session 1: persisted, never shown

        // Process B records session 2, delivered live to a listener -- no screen opened.
        val b = AppProcess(WavRecorder(startup = ImmediateStartup,
            openAudioSource = { WavRecorder.RecorderConfig(Audio(chunks = 3), 48000, 64) }))
        val delivered = AtomicBoolean()
        b.service.listener = object : RecordingService.Listener {
            override fun onAmplitude(amplitude: Float) {}
            override fun onSegmentStarted(target: OutputTarget, partNumber: Int) {}
            override fun onError(e: Exception) { delivered.set(true) }
            override fun onStopped(lastTarget: OutputTarget?) { delivered.set(true) }
            override fun onFinalizationFailed(target: OutputTarget?, cause: Exception) { delivered.set(true) }
            override fun onFinalizationUnknown(target: OutputTarget?) { delivered.set(true) }
        }
        b.service.startRecording(2L)
        Thread.sleep(50)
        b.service.stopAndAwaitFinalization()
        assertTrue(delivered.get())
        b.service.listener = null
        drainStartedServices()
        b.die()

        val toastsBefore = ShadowToast.shownToastCount()
        val c = AppProcess().open()
        assertEquals("nothing stale shown", toastsBefore, ShadowToast.shownToastCount())
        assertEquals(app().getString(R.string.status_idle), c.statusText())
        c.assertNothingStarted()
    }

    @Test
    fun `an unreadable persisted outcome shows nothing and starts nothing`() {
        val prefs = app().getSharedPreferences("wav_recorder_pending_outcome", Context.MODE_PRIVATE)
        for (corrupt in listOf<() -> Unit>(
            { prefs.edit().clear().putLong("session_id", 7L).putString("category", "FROM_A_NEWER_VERSION").commit() },
            { prefs.edit().clear().putLong("session_id", 8L).putString("category", "FAILED_BUT_SAVED").commit() } // no file
        )) {
            corrupt()
            val toastsBefore = ShadowToast.shownToastCount()
            val b = AppProcess().open()
            assertEquals(toastsBefore, ShadowToast.shownToastCount())
            assertEquals(app().getString(R.string.status_idle), b.statusText())
            b.assertNothingStarted()
            b.die()
        }
    }

    // ---- A recording interrupted by the process's death -----------------------------------------------------

    /** Writes normally until the process "dies"; from then on it can't touch the file at all. */
    private class DyingWriter(private val channel: FileChannel, private val dead: AtomicBoolean) : SegmentWriter {
        override fun write(buffer: ByteBuffer): Int {
            if (dead.get()) throw IOException("the process is gone")
            return channel.write(buffer)
        }
        override fun position() = channel.position()
        override fun position(newPosition: Long) {
            if (dead.get()) throw IOException("the process is gone")
            channel.position(newPosition)
        }
        override fun close() = channel.close()
    }

    private fun sha(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    @Test
    fun `an interrupted part is recovered by the next process, completed parts are left alone, and nothing is repaired twice`() {
        // Process A records 1.5 s into 1-second parts (48 kHz 16-bit mono), then dies mid-part-2.
        val dir = tempFolder.newFolder()
        val dead = AtomicBoolean()
        val source = object : AudioSource {
            val reads = AtomicInteger()
            override fun startRecording() {}
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                Thread.sleep(1)
                if (reads.incrementAndGet() > 15) return 0
                buffer.fill(7, offset, offset + 9600)
                return 9600
            }
            override fun stop() {}
            override fun release() {}
        }
        val recorderA = WavRecorder(startup = ImmediateStartup, segmentMaxSeconds = 1,
            openAudioSource = { WavRecorder.RecorderConfig(source, 48000, 9600) },
            wrapChannel = { DyingWriter(it, dead) })
        val part1 = File(dir, "part1.wav")
        val part2 = File(dir, "part2.wav")
        var n = 0
        recorderA.start(app(), WavRecorder.NextTarget { OutputTarget.FileTarget(File(dir, "part${++n}.wav")) },
            onSegmentStarted = {}, onAmplitude = {}, onError = {})
        val deadline = System.currentTimeMillis() + 5000
        while (part2.length() < 44 + 48_000 && System.currentTimeMillis() < deadline) Thread.sleep(2)
        assertEquals("half a second reached part 2", 44L + 48_000, part2.length())
        dead.set(true) // the process is killed: nothing more reaches its files
        val part1Sha = sha(part1)
        val journal = ActiveSegmentJournal(app())
        val record = journal.peekActive()!!
        assertEquals(part2.absolutePath, (record.target as OutputTarget.FileTarget).file.absolutePath)
        RecordingOwnership.simulateProcessDeathForTest()
        // A new process has a new ProcessInstanceId: the record now belongs to a previous process.
        assertTrue(journal.persistActiveWithOwnerForTest(record.token, "the-process-that-died", record.target, PcmFormat.pcm16Mono(48000)))

        // Process B starts: it claims the record, and recovers part 2 in the background.
        val b = AppProcess(dir = dir)
        val recoveryDeadline = System.currentTimeMillis() + 5000
        while (journal.peekRecoveryCandidates().isNotEmpty() && System.currentTimeMillis() < recoveryDeadline) Thread.sleep(5)
        assertTrue("recovered, and off the queue", journal.peekRecoveryCandidates().isEmpty())
        assertNull(journal.peekActive())
        assertEquals("part 2's header now covers the half second", 48_000L, WavRiffParser.parse(part2.inputStream())!!.dataSize)
        assertEquals("part 1, completed before the death, untouched", part1Sha, sha(part1))
        assertEquals("the recording is not resumed", ServiceState.IDLE, b.service.state)
        assertEquals(0, b.recorderWorker.pending)
        val part2Sha = sha(part2)

        // Process C finds nothing left to recover, and changes nothing.
        b.die()
        val c = AppProcess(dir = dir)
        Thread.sleep(100)
        assertTrue(journal.peekRecoveryCandidates().isEmpty())
        assertEquals(part1Sha, sha(part1))
        assertEquals("not repaired twice", part2Sha, sha(part2))
        assertEquals(listOf("part1.wav", "part2.wav"), dir.list()!!.sorted())
        assertEquals(ServiceState.IDLE, c.service.state)

        recorderA.stop() // process A's leftover objects, cleaned up only now the test is over
    }
}
