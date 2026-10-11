package bukin.wavrecorder

import android.app.Application
import android.content.ComponentName
import android.os.Looper
import android.widget.TextView
import androidx.fragment.app.testing.launchFragmentInContainer
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Rule
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * The "Recording saved" summary states how much was recorded, not how long ago the recording
 * started (audit finding F6): an outcome caught up with long after the stop still shows the
 * recorded duration, and one without a known duration shows none rather than a made-up one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RecordFragmentSavedSummaryTest {

    @get:Rule
    val isolation = TestIsolation()

    private fun app(): Application = ApplicationProvider.getApplicationContext()

    private fun summaryShownFor(outcome: RecordingOutcome): String {
        val service = Robolectric.buildService(RecordingService::class.java).create().get()
        shadowOf(app()).setComponentNameAndServiceForBindService(
            ComponentName(app(), RecordingService::class.java), service.LocalBinder()
        )
        PendingOutcomeStore(app()).persist(1, outcome, null)
        val scenario = launchFragmentInContainer<RecordFragment>(themeResId = R.style.Theme_WavRecorder)
        shadowOf(Looper.getMainLooper()).idle()
        var text = ""
        scenario.onFragment { text = it.requireView().findViewById<TextView>(R.id.statusDetailText).text.toString() }
        scenario.close()
        return text
    }

    private val twoHoursAgo = System.currentTimeMillis() - 7_200_000
    private val target = OutputTarget.FileTarget(File(ApplicationProvider.getApplicationContext<Application>().cacheDir, "ten_seconds.wav"))

    @Test
    fun `a summary shown two hours after a ten-second recording says ten seconds`() {
        val text = summaryShownFor(RecordingOutcome.Saved(target, twoHoursAgo, recordedMillis = 10_000))

        assertTrue(text, text.contains(DurationFormatter.format(app(), 10)))
        assertFalse(text, text.contains(DurationFormatter.format(app(), 7_200)))
    }

    @Test
    fun `without a known recorded duration only the date is shown - no duration is made up`() {
        val text = summaryShownFor(RecordingOutcome.Saved(target, twoHoursAgo))

        assertFalse(text, text.contains(DurationFormatter.format(app(), 7_200)))
        assertTrue("the date is still there", text.isNotBlank())
    }
}
