package bukin.wavrecorder

import android.app.Dialog
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.text.HtmlCompat
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * "About & privacy", opened from the info button in the app header: the version and the privacy
 * policy -- the in-app copy of docs/privacy-policy.md, readable offline since the app has no
 * internet access -- with Send feedback (see [Feedback]) and the open-source notices one tap away.
 * DialogFragments, so both survive the screen being recreated; the alert dialog's message area
 * scrolls on its own.
 *
 * The notices are a link under the version rather than a third button: next to this long message,
 * three buttons that don't fit on one row (a narrow phone, a larger font) stack into a button area
 * only one row tall, hiding most of them.
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
            .setMessage(
                TextUtils.concat(getString(R.string.about_version, versionName()), "\n", licensesLink(), "\n\n", policy)
            )
            .setPositiveButton(R.string.about_close, null)
            .setNegativeButton(R.string.feedback_button) { _, _ -> Feedback.send(requireActivity()) }
            .create()
    }

    override fun onStart() {
        super.onStart()
        // The message view exists once the dialog is shown; links in it need this to be tappable.
        (dialog as AlertDialog?)?.findViewById<TextView>(android.R.id.message)?.movementMethod =
            LinkMovementMethod.getInstance()
    }

    /** "Open-source licenses", opening [LicensesDialogFragment] on top of this dialog. */
    private fun licensesLink(): CharSequence {
        val link = SpannableString(getString(R.string.about_licenses))
        link.setSpan(object : ClickableSpan() {
            override fun onClick(widget: View) {
                if (parentFragmentManager.findFragmentByTag(LicensesDialogFragment.TAG) == null) {
                    LicensesDialogFragment().show(parentFragmentManager, LicensesDialogFragment.TAG)
                }
            }
        }, 0, link.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return link
    }

    private fun versionName(): String = requireContext().ownPackageInfo().versionName.orEmpty()

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
