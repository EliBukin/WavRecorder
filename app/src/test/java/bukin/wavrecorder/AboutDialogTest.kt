package bukin.wavrecorder

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.DialogInterface
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** The header's info button opens "About & privacy": the version, the privacy policy (offline, in
 * the app) and, from there, the open-source notices. */
@RunWith(RobolectricTestRunner::class)
class AboutDialogTest {

    @get:Rule
    val isolation = TestIsolation()

    private fun app(): Application = ApplicationProvider.getApplicationContext()
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Before
    fun setUp() {
        shadowOf(app()).grantPermissions(Manifest.permission.RECORD_AUDIO)
        shadowOf(app()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val idleService = Robolectric.buildService(RecordingService::class.java).create().get()
        shadowOf(app()).setComponentNameAndServiceForBindService(
            ComponentName(app(), RecordingService::class.java),
            idleService.LocalBinder()
        )
    }

    private fun MainActivity.shownDialog(tag: String): AlertDialog {
        val fragment = supportFragmentManager.findFragmentByTag(tag) as DialogFragment?
        assertNotNull("$tag dialog shown", fragment)
        return fragment!!.requireDialog() as AlertDialog
    }

    private fun AlertDialog.message(): String = findViewById<TextView>(android.R.id.message)!!.text.toString()

    @Test
    fun `the info button is a labelled 48dp touch target`() {
        val scenario = isolation.closeAfter(ActivityScenario.launch(MainActivity::class.java))
        scenario.onActivity { activity ->
            val button = activity.findViewById<View>(R.id.aboutButton)
            assertEquals(activity.getString(R.string.about_open), button.contentDescription)
            val minimum = activity.resources.getDimensionPixelSize(R.dimen.touch_target)
            assertEquals(minimum, button.layoutParams.width)
            assertEquals(minimum, button.layoutParams.height)
        }
    }

    @Test
    fun `it shows the version and the privacy policy, and leads to the open-source licenses`() {
        val scenario = isolation.closeAfter(ActivityScenario.launch(MainActivity::class.java))
        scenario.onActivity { activity ->
            activity.findViewById<View>(R.id.aboutButton).performClick()
            idle()
            val about = activity.shownDialog(AboutDialogFragment.TAG)
            val text = about.message()
            val version = activity.packageManager.getPackageInfo(activity.packageName, 0).versionName
            assertTrue(text, text.startsWith("Version $version"))
            assertTrue(text, text.contains("no internet permission"))
            assertTrue(text, text.contains("Mic Test audio is processed in memory and is not saved"))
            assertTrue(text, text.contains("Android/data/${activity.packageName}/files"))
            assertTrue(text, text.contains("include only the split interval"))
            assertTrue("rendered, not raw HTML", !text.contains("<b>") && !text.contains("<br>"))

            about.getButton(DialogInterface.BUTTON_NEUTRAL).performClick()
            idle()
            val licenses = activity.shownDialog(LicensesDialogFragment.TAG).message()
            assertTrue(licenses.contains("Material Components for Android"))
            assertTrue(licenses.contains("Apache License"))
            assertTrue(licenses.contains("Version 2.0, January 2004"))
            assertTrue(licenses.contains("END OF TERMS AND CONDITIONS"))
        }
    }

    @Test
    fun `a second tap doesn't stack another dialog, and the dialog survives recreation`() {
        val scenario = isolation.closeAfter(ActivityScenario.launch(MainActivity::class.java))
        scenario.onActivity { activity ->
            val button = activity.findViewById<View>(R.id.aboutButton)
            button.performClick()
            idle()
            button.performClick()
            idle()
            assertEquals(1, activity.supportFragmentManager.fragments.count { it is AboutDialogFragment })
        }
        scenario.recreate()
        scenario.onActivity { activity ->
            assertTrue(activity.shownDialog(AboutDialogFragment.TAG).isShowing)
        }
    }
}
