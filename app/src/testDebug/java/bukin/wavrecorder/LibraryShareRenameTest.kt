package bukin.wavrecorder

import android.app.Activity
import android.app.Application
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.view.View
import androidx.fragment.app.testing.FragmentScenario
import androidx.fragment.app.testing.launchFragmentInContainer
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowToast
import java.io.File

/**
 * The Library's Share, Save a copy and Rename, end to end on real files: what reaches the system's
 * share sheet and save dialog, what the rename dialog does with good and refused names, and that a
 * recording being written -- or still waiting to be repaired -- is never shared, copied or renamed.
 */
@RunWith(RobolectricTestRunner::class)
class LibraryShareRenameTest {

    @get:Rule
    val isolation = TestIsolation()

    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Before
    fun resetFileProvider() = FileProviderTestSupport.reset()

    @After
    fun tearDown() = RecordingOwnership.resetForTest()

    private fun recording(name: String, bytes: ByteArray = ByteArray(2000) { (it % 101).toByte() }): File {
        val target = DestinationManager(app).createOutputFile(name) as OutputTarget.FileTarget
        target.file.writeBytes(bytes)
        return target.file
    }

    private fun pumpUntil(timeoutMs: Long = 3000, done: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!done() && System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** The Library, showing the recordings on disk (the real listing). */
    private fun launch(expectedCount: Int): FragmentScenario<LibraryFragment> {
        val scenario = isolation.closeAfter(launchFragmentInContainer<LibraryFragment>(themeResId = R.style.Theme_WavRecorder))
        pumpUntil { listed(scenario).size == expectedCount }
        assertEquals(expectedCount, listed(scenario).size)
        return scenario
    }

    private fun listed(scenario: FragmentScenario<LibraryFragment>): List<String> {
        var names = emptyList<String>()
        scenario.onFragment { fragment ->
            names = DestinationManager(app).listRecordings().map { it.name }
            val shown = fragment.requireView().findViewById<RecyclerView>(R.id.recordingsList).adapter!!.itemCount
            if (shown != names.size) names = emptyList()
        }
        return names
    }

    private fun itemFor(file: File) = RecordingItem(file.name, Uri.fromFile(file), 1.0, file.length())

    private fun nextStarted(scenario: FragmentScenario<LibraryFragment>): Intent? {
        var intent: Intent? = null
        scenario.onFragment { intent = shadowOf(it.requireActivity()).nextStartedActivity }
        return intent
    }

    @Suppress("DEPRECATION")
    private fun Intent.target(): Intent = getParcelableExtra(Intent.EXTRA_INTENT)!!

    @Suppress("DEPRECATION")
    private fun Intent.streams(): List<Uri> = if (action == Intent.ACTION_SEND) {
        listOf(getParcelableExtra(Intent.EXTRA_STREAM)!!)
    } else {
        getParcelableArrayListExtra(Intent.EXTRA_STREAM)!!
    }

    private fun shareable(file: File) = RecordingSharing.shareableUri(app, Uri.fromFile(file))

    // ---- Share ----

    @Test
    fun `Share opens the system share sheet with that recording`() {
        val file = recording("recording_20261019_115500_part01.wav")
        val scenario = launch(1)

        scenario.onFragment { it.shareRecordings(listOf(itemFor(file))) }

        val chooser = nextStarted(scenario)!!
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertEquals(app.resources.getQuantityString(R.plurals.share_chooser_title, 1, 1),
            chooser.getCharSequenceExtra(Intent.EXTRA_TITLE).toString())
        assertEquals(Intent.ACTION_SEND, chooser.target().action)
        assertEquals(listOf(shareable(file)), chooser.target().streams())
    }

    @Test
    fun `a recording being written is not shared`() {
        val file = recording("live.wav")
        val scenario = launch(1)
        val claim = RecordingOwnership.tryClaim(OutputTarget.FileTarget(file), RecordingOwnership.Use.CAPTURING)!!

        scenario.onFragment { it.shareRecordings(listOf(itemFor(file))) }

        assertNull(nextStarted(scenario))
        assertEquals(app.getString(R.string.share_in_use), ShadowToast.getTextOfLatestToast())
        claim.release()
    }

    @Test
    fun `Share in selection mode sends every selected recording at once`() {
        val a = recording("a.wav")
        val b = recording("b.wav")
        recording("c.wav")
        val scenario = launch(3)
        var shareButton: View? = null
        scenario.onFragment {
            it.enterSelectionMode()
            shareButton = it.requireView().findViewById(R.id.selectionShareButton)
        }
        assertFalse("nothing to share until something is selected", shareButton!!.isEnabled)

        scenario.onFragment {
            it.toggleSelection(itemFor(a))
            it.toggleSelection(itemFor(b))
        }
        assertTrue(shareButton!!.isEnabled)
        assertEquals(app.getString(R.string.share_selected_action), shareButton!!.contentDescription.toString())
        shareButton!!.performClick()

        val target = nextStarted(scenario)!!.target()
        assertEquals(Intent.ACTION_SEND_MULTIPLE, target.action)
        assertEquals(setOf(shareable(a), shareable(b)), target.streams().toSet())
        var stillSelecting = false
        scenario.onFragment { stillSelecting = it.selectionMode }
        assertTrue("the selection stays, in case the user backs out of the share sheet", stillSelecting)
    }

    @Test
    fun `sharing a selection leaves out a recording that came into use, and says so`() {
        val a = recording("a.wav")
        val b = recording("b.wav")
        val scenario = launch(2)
        scenario.onFragment {
            it.enterSelectionMode(itemFor(a))
            it.toggleSelection(itemFor(b))
        }
        val claim = RecordingOwnership.tryClaim(OutputTarget.FileTarget(b), RecordingOwnership.Use.RECOVERING)!!

        scenario.onFragment { it.shareSelected() }

        val target = nextStarted(scenario)!!.target()
        assertEquals(Intent.ACTION_SEND, target.action)
        assertEquals(listOf(shareable(a)), target.streams())
        assertEquals(app.resources.getQuantityString(R.plurals.share_skipped_in_use, 1, 1), ShadowToast.getTextOfLatestToast())
        claim.release()
    }

    // ---- Save a copy ----

    @Test
    fun `Save a copy asks where, named like the recording, then copies all of it`() {
        val audio = ByteArray(600_000) { (it % 211).toByte() }
        val file = recording("recording_20261019_115500_part01.wav", audio)
        val scenario = launch(1)

        scenario.onFragment { it.startSaveCopy(itemFor(file)) }

        var request: Intent? = null
        scenario.onFragment { request = shadowOf(it.requireActivity()).nextStartedActivityForResult?.intent }
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, request!!.action)
        assertEquals("audio/x-wav", request!!.type)
        assertEquals(file.name, request!!.getStringExtra(Intent.EXTRA_TITLE))

        val destination = File(app.cacheDir, "picked.wav").apply { createNewFile() }
        scenario.onFragment {
            shadowOf(it.requireActivity()).receiveResult(request, Activity.RESULT_OK, Intent().setData(Uri.fromFile(destination)))
        }
        pumpUntil { ShadowToast.getTextOfLatestToast() == app.getString(R.string.save_copy_done) }

        assertEquals(app.getString(R.string.save_copy_done), ShadowToast.getTextOfLatestToast())
        assertArrayEquals(audio, destination.readBytes())
        assertArrayEquals("the recording itself is only read", audio, file.readBytes())
    }

    @Test
    fun `backing out of the save dialog copies nothing`() {
        val file = recording("a.wav")
        val scenario = launch(1)
        scenario.onFragment { it.startSaveCopy(itemFor(file)) }
        var request: Intent? = null
        scenario.onFragment { request = shadowOf(it.requireActivity()).nextStartedActivityForResult?.intent }

        scenario.onFragment { shadowOf(it.requireActivity()).receiveResult(request, Activity.RESULT_CANCELED, null) }
        shadowOf(Looper.getMainLooper()).idle()

        var pending: Uri? = Uri.EMPTY
        scenario.onFragment { pending = it.pendingCopySource }
        assertNull(pending)
        assertNull(ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun `a recording being written can't be copied`() {
        val file = recording("live.wav")
        val scenario = launch(1)
        val claim = RecordingOwnership.tryClaim(OutputTarget.FileTarget(file), RecordingOwnership.Use.CAPTURING)!!

        scenario.onFragment { it.startSaveCopy(itemFor(file)) }

        var request: Any? = null
        scenario.onFragment { request = shadowOf(it.requireActivity()).nextStartedActivityForResult }
        assertNull(request)
        assertEquals(app.getString(R.string.save_copy_in_use), ShadowToast.getTextOfLatestToast())
        claim.release()
    }

    @Test
    fun `a copy waiting on the save dialog survives the screen being recreated`() {
        val file = recording("a.wav")
        val scenario = launch(1)
        scenario.onFragment { it.startSaveCopy(itemFor(file)) }

        scenario.recreate()

        var pending: Uri? = null
        scenario.onFragment { pending = it.pendingCopySource }
        assertEquals(Uri.fromFile(file), pending)
    }

    // ---- Rename ----

    private fun openRename(scenario: FragmentScenario<LibraryFragment>, file: File): androidx.appcompat.app.AlertDialog {
        var dialog: androidx.appcompat.app.AlertDialog? = null
        scenario.onFragment {
            it.showRenameDialog(itemFor(file))
            dialog = it.activeRenameDialog
        }
        shadowOf(Looper.getMainLooper()).idle()
        return dialog!!
    }

    private fun androidx.appcompat.app.AlertDialog.input() = findViewById<TextInputEditText>(R.id.renameInput)!!
    private fun androidx.appcompat.app.AlertDialog.inputLayout() = findViewById<TextInputLayout>(R.id.renameInputLayout)!!

    private fun androidx.appcompat.app.AlertDialog.submit(name: String) {
        input().setText(name)
        getButton(DialogInterface.BUTTON_POSITIVE).performClick()
    }

    @Test
    fun `Rename offers the current name and renames the file, which the list then shows`() {
        val audio = ByteArray(1234) { 5 }
        val file = recording("recording_20261019_115500_part01.wav", audio)
        val scenario = launch(1)

        val dialog = openRename(scenario, file)
        assertEquals("recording_20261019_115500_part01", dialog.input().text.toString())
        dialog.submit("Band practice")
        pumpUntil { !dialog.isShowing && listed(scenario) == listOf("Band practice.wav") }

        assertFalse(dialog.isShowing)
        assertEquals(listOf("Band practice.wav"), listed(scenario))
        assertFalse(file.exists())
        assertArrayEquals(audio, File(file.parentFile, "Band practice.wav").readBytes())
        assertNull("no error toast", ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun `a refused name keeps the dialog open with the reason, and changes nothing`() {
        val file = recording("a.wav")
        val scenario = launch(1)
        val dialog = openRename(scenario, file)

        val cases = mapOf(
            "" to R.string.rename_error_empty,
            "a/b" to R.string.rename_error_characters,
            ".hidden" to R.string.rename_error_dot,
            "x".repeat(101) to R.string.rename_error_too_long,
            "recording_20261019_120000_part01" to R.string.rename_error_reserved
        )
        for ((name, error) in cases) {
            dialog.submit(name)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue("'$name'", dialog.isShowing)
            assertEquals("'$name'", app.getString(error), dialog.inputLayout().error.toString())
        }

        dialog.input().setText("typing again")
        assertNull("typing clears the error", dialog.inputLayout().error)
        assertTrue(file.exists())
        dialog.dismiss()
    }

    @Test
    fun `a name another recording has is reported in the dialog, which stays open`() {
        val mine = recording("mine.wav", byteArrayOf(1))
        val other = recording("Taken.wav", byteArrayOf(2))
        val scenario = launch(2)
        val dialog = openRename(scenario, mine)

        dialog.submit("taken")
        pumpUntil { dialog.inputLayout().error != null }

        assertTrue(dialog.isShowing)
        assertEquals(app.getString(R.string.rename_error_taken), dialog.inputLayout().error.toString())
        assertTrue("another name can be tried", dialog.getButton(DialogInterface.BUTTON_POSITIVE).isEnabled)
        assertArrayEquals(byteArrayOf(1), mine.readBytes())
        assertArrayEquals(byteArrayOf(2), other.readBytes())
        dialog.dismiss()
    }

    @Test
    fun `leaving the name as it is just closes the dialog`() {
        val file = recording("recording_20261019_115500_part01.wav")
        val scenario = launch(1)
        val dialog = openRename(scenario, file)

        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(dialog.isShowing)
        assertTrue(file.exists())
    }

    @Test
    fun `a recording being written can't be renamed`() {
        val file = recording("live.wav")
        val scenario = launch(1)
        val claim = RecordingOwnership.tryClaim(OutputTarget.FileTarget(file), RecordingOwnership.Use.CAPTURING)!!

        var dialog: androidx.appcompat.app.AlertDialog? = null
        scenario.onFragment {
            it.showRenameDialog(itemFor(file))
            dialog = it.activeRenameDialog
        }

        assertNull(dialog)
        assertEquals(app.getString(R.string.rename_in_use), ShadowToast.getTextOfLatestToast())
        claim.release()
    }

    @Test
    fun `a recording still waiting to be repaired keeps its name, and the user is told why`() {
        val file = recording("interrupted.wav")
        val journal = ActiveSegmentJournal(app)
        assertTrue(journal.persistActiveWithOwnerForTest("t", "another-process", OutputTarget.FileTarget(file), 48000, 1, 16))
        assertTrue(journal.claimActiveAsRecoveryCandidate() is ActiveSegmentJournal.ClaimResult.Claimed)
        val scenario = launch(1)

        val dialog = openRename(scenario, file)
        dialog.submit("Renamed")
        pumpUntil { ShadowToast.getTextOfLatestToast() != null }

        assertFalse(dialog.isShowing)
        assertEquals(app.getString(R.string.rename_in_use), ShadowToast.getTextOfLatestToast())
        assertTrue(file.exists())
        assertFalse(File(file.parentFile, "Renamed.wav").exists())
    }

    @Test
    fun `leaving the screen closes the rename dialog`() {
        val file = recording("a.wav")
        val scenario = launch(1)
        val dialog = openRename(scenario, file)

        scenario.moveToState(androidx.lifecycle.Lifecycle.State.DESTROYED)

        assertFalse(dialog.isShowing)
        assertNotNull(file.takeIf { it.exists() })
    }
}
