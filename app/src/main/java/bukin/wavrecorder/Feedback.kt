package bukin.wavrecorder

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.core.content.pm.PackageInfoCompat

/**
 * "Send feedback", from About & privacy and the What's new card: opens the user's own email app
 * with a message to the developer -- addressed, with a subject, and with the app version, Android
 * version and phone model filled in below room for the user's own words. The app itself sends
 * nothing (it has no internet access): the user sees the whole message in their email app and
 * decides whether to send it.
 */
internal object Feedback {

    const val ADDRESS = "dev.mechanics998877@gmail.com"

    /**
     * The email to the developer. Subject and body travel both in the mailto: URI and as extras:
     * email apps differ in which of the two they read. Started directly rather than checked with
     * resolveActivity() first, which on Android 11+ would need a <queries> entry to see email apps.
     */
    fun intent(context: Context): Intent {
        val info = context.ownPackageInfo()
        val version = info.versionName.orEmpty()
        val subject = context.getString(R.string.feedback_subject, version)
        val body = context.getString(
            R.string.feedback_body,
            version,
            PackageInfoCompat.getLongVersionCode(info),
            Build.VERSION.RELEASE,
            Build.VERSION.SDK_INT,
            phoneModel(Build.MANUFACTURER, Build.MODEL)
        )
        val uri = Uri.parse("mailto:$ADDRESS?subject=${Uri.encode(subject)}&body=${Uri.encode(body)}")
        return Intent(Intent.ACTION_SENDTO, uri)
            .putExtra(Intent.EXTRA_EMAIL, arrayOf(ADDRESS))
            .putExtra(Intent.EXTRA_SUBJECT, subject)
            .putExtra(Intent.EXTRA_TEXT, body)
    }

    /** Opens the email, or says how to write instead when no email app is installed. */
    fun send(activity: Activity) {
        try {
            activity.startActivity(intent(activity))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(activity, activity.getString(R.string.feedback_no_email_app, ADDRESS), Toast.LENGTH_LONG).show()
        }
    }

    /** "samsung SM-A556E", or just the model where it already starts with the maker ("Google Pixel"). */
    internal fun phoneModel(manufacturer: String, model: String): String =
        if (model.startsWith(manufacturer, ignoreCase = true)) model else "$manufacturer $model"
}

/** This app's own [PackageInfo]: its version, and when it was first installed and last updated. */
internal fun Context.ownPackageInfo(): PackageInfo =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(packageName, 0)
    }
