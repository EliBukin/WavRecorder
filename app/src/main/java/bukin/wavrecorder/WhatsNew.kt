package bukin.wavrecorder

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * The one-time "What's new" card after an update. [RecordFragment] offers it once its
 * RecordingService connection shows the real recording state (see
 * [RecordFragment.showWhatsNewIfDue]); whether it then appears is decided by [decide].
 */
internal object WhatsNew {

    /** The versionCode of the release the card describes (1.2). A later release with a new card
     * raises this, along with the card's text. */
    const val CARD_VERSION = 3

    // Not in backups (see backup_rules.xml): a restore goes with a fresh install, which never
    // shows the card anyway. internal for testing.
    internal const val PREFS_NAME = "wav_recorder_whats_new"
    internal const val KEY_SEEN_VERSION = "seen_card_version"

    enum class Decision {
        /** Show the card, and remember it was shown. */
        SHOW,
        /** A fresh install: nothing is new to this user, so remember the card as seen, unshown. */
        MARK_SEEN,
        /** Something is going on: show nothing, store nothing, and ask again on a later visit. */
        LATER,
        /** Already seen. */
        NONE,
    }

    /**
     * [seenVersion] is the last card shown (0 if none), [isUpdate] whether the installed app
     * replaced an earlier version rather than being installed fresh, and [busy] whether anything
     * is going on the card must not cover: a recording starting, running or finishing, a Mic Test,
     * a permission request, or another dialog.
     */
    fun decide(seenVersion: Int, isUpdate: Boolean, busy: Boolean): Decision = when {
        seenVersion >= CARD_VERSION -> Decision.NONE
        !isUpdate -> Decision.MARK_SEEN
        busy -> Decision.LATER
        else -> Decision.SHOW
    }

    /** Applies [decide] for the app as it is now, showing the card in [fragmentManager] if due. */
    fun showIfDue(context: Context, fragmentManager: FragmentManager, busy: Boolean) {
        if (fragmentManager.isStateSaved) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val otherDialog = fragmentManager.findFragmentByTag(AboutDialogFragment.TAG) != null ||
            fragmentManager.findFragmentByTag(LicensesDialogFragment.TAG) != null ||
            fragmentManager.findFragmentByTag(WhatsNewDialogFragment.TAG) != null
        when (decide(prefs.getInt(KEY_SEEN_VERSION, 0), isUpdate(context), busy || otherDialog)) {
            Decision.SHOW -> {
                // Stored as it is shown: once on screen, the card never comes back, even if the
                // app is closed while it is still open.
                prefs.edit().putInt(KEY_SEEN_VERSION, CARD_VERSION).apply()
                WhatsNewDialogFragment().show(fragmentManager, WhatsNewDialogFragment.TAG)
            }
            Decision.MARK_SEEN -> prefs.edit().putInt(KEY_SEEN_VERSION, CARD_VERSION).apply()
            Decision.LATER, Decision.NONE -> Unit
        }
    }

    /** Android sets both times at install, and moves only lastUpdateTime on each update. */
    private fun isUpdate(context: Context): Boolean {
        val info = context.ownPackageInfo()
        return info.lastUpdateTime > info.firstInstallTime
    }
}

/** The card itself: what 1.2 brings, and the same Send feedback as About & privacy. A
 * DialogFragment, so it survives the screen being recreated. */
class WhatsNewDialogFragment : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.whats_new_title)
            .setMessage(R.string.whats_new_message)
            .setPositiveButton(R.string.whats_new_close, null)
            .setNegativeButton(R.string.feedback_button) { _, _ -> Feedback.send(requireActivity()) }
            .create()

    companion object {
        const val TAG = "whats_new"
    }
}
