package bukin.wavrecorder

import android.app.Dialog
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import androidx.core.text.HtmlCompat
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * "About & privacy", opened from the info button in the app header: the version and the privacy
 * policy -- the in-app copy of docs/privacy-policy.md, readable offline since the app has no
 * internet access -- with the open-source notices one tap away. DialogFragments, so both survive
 * the screen being recreated; the alert dialog's message area scrolls on its own.
 */
class AboutDialogFragment : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val policy = HtmlCompat.fromHtml(
            getString(R.string.privacy_policy_html, context.packageName),
            HtmlCompat.FROM_HTML_MODE_LEGACY
        )
        return MaterialAlertDialogBuilder(context)
            .setTitle(R.string.about_title)
            .setMessage(TextUtils.concat(getString(R.string.about_version, versionName()), "\n\n", policy))
            .setPositiveButton(R.string.about_close, null)
            .setNeutralButton(R.string.about_licenses) { _, _ ->
                LicensesDialogFragment().show(parentFragmentManager, LicensesDialogFragment.TAG)
            }
            .create()
    }

    private fun versionName(): String {
        val context = requireContext()
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
        return info.versionName.orEmpty()
    }

    companion object {
        const val TAG = "about"
    }
}

/** The open-source notices: every library the release build ships, and the Apache License 2.0
 * they are all distributed under (none ships a NOTICE file -- see THIRD_PARTY_NOTICES.md). */
class LicensesDialogFragment : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val license = resources.openRawResource(R.raw.apache_license_2_0).bufferedReader().use { it.readText() }
        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.about_licenses)
            .setMessage(getString(R.string.licenses_intro) + license)
            .setPositiveButton(R.string.about_close, null)
            .create()
    }

    companion object {
        const val TAG = "licenses"
    }
}
