package bukin.wavrecorder

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.DialogInterface
import android.content.Intent
import android.os.Build
import android.os.Looper
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.core.content.pm.PackageInfoCompat
import androidx.fragment.app.DialogFragment
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowBuild
import org.robolectric.shadows.ShadowToast

/** Send feedback, in About & privacy: the user's email app opens with a message to the developer
 * that they see in full before deciding to send it; without an email app, a short message. */
@RunWith(RobolectricTestRunner::class)
class FeedbackTest {

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
        ShadowBuild.setManufacturer("samsung")
        ShadowBuild.setModel("SM-A556E")
    }

    /** Opens About & privacy and taps its Send feedback button. */
    private fun MainActivity.tapSendFeedback() {
        findViewById<View>(R.id.aboutButton).performClick()
        idle()
        val about = (supportFragmentManager.findFragmentByTag(AboutDialogFragment.TAG) as DialogFragment).requireDialog() as AlertDialog
        val button = about.getButton(DialogInterface.BUTTON_NEGATIVE)
        assertEquals("Send feedback", button.text.toString())
        button.performClick()
        idle()
    }

    @Test
    fun `it opens an email to the developer with the version, Android version and phone model`() {
        val scenario = isolation.closeAfter(ActivityScenario.launch(MainActivity::class.java))
        scenario.onActivity { activity ->
            activity.tapSendFeedback()
            val email = shadowOf(activity).nextStartedActivity
            assertNotNull("an email app is asked to open", email)
            assertEquals(Intent.ACTION_SENDTO, email.action)
            assertEquals("mailto", email.data!!.scheme)

            val info = activity.ownPackageInfo()
            val subject = "Bukin Mic Recorder ${info.versionName} feedback"
            val body = "\n\n\n---\nApp: Bukin Mic Recorder ${info.versionName} (${PackageInfoCompat.getLongVersionCode(info)})\n" +
                "Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\nPhone: samsung SM-A556E"
            assertArrayEquals(arrayOf("dev.mechanics998877@gmail.com"), email.getStringArrayExtra(Intent.EXTRA_EMAIL))
            assertEquals(subject, email.getStringExtra(Intent.EXTRA_SUBJECT))
            assertEquals(body, email.getStringExtra(Intent.EXTRA_TEXT))
            // The same message in the mailto: URI itself, for email apps that read only that.
            assertEquals("dev.mechanics998877@gmail.com?subject=$subject&body=$body", email.data!!.schemeSpecificPart)
            assertEquals("no flags granting access to anything", 0, email.flags)
        }
    }

    @Test
    fun `without an email app it says where to write instead`() {
        shadowOf(app()).checkActivities(true) // an Intent nothing can handle now throws, as on a phone
        val scenario = isolation.closeAfter(ActivityScenario.launch(MainActivity::class.java))
        scenario.onActivity { activity ->
            activity.tapSendFeedback()
            assertNull(shadowOf(activity).nextStartedActivity)
            assertEquals(
                "No email app found. You can write to dev.mechanics998877@gmail.com",
                ShadowToast.getTextOfLatestToast()
            )
        }
    }

    @Test
    fun `the phone is named once, maker first`() {
        assertEquals("samsung SM-A556E", Feedback.phoneModel("samsung", "SM-A556E"))
        assertEquals("Google Pixel 8", Feedback.phoneModel("Google", "Google Pixel 8"))
    }
}
