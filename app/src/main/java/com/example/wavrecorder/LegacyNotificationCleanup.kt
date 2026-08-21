package com.example.wavrecorder

import android.app.NotificationManager
import android.content.Context
import android.os.Build

/**
 * Retracts the retired "terminal recording outcome" notification/channel this app used to raise
 * whenever a recording ended with no screen attached to show the result live. That outcome is
 * still durably persisted and delivered in-app on next launch (see [PendingOutcomeStore],
 * [RecordingService.consumePendingOutcome]) -- only the system notification/launcher badge it used
 * to *also* raise has been removed. An app update alone can't retract an already-posted
 * notification or delete an already-created channel; both are OS-side state this app itself
 * created, so an explicit call is the only way to actually undo them.
 *
 * The legacy identifiers are centralized here, not scattered as duplicate literals anywhere else,
 * because nothing else in the app has any remaining reason to know them once
 * [RecordingService] no longer creates the channel or posts to it -- this is the one and only
 * place they still need to exist, purely to name what to retract.
 *
 * Deliberately cheap and idempotent (a plain [NotificationManager.cancel]/
 * [NotificationManager.deleteNotificationChannel] pair, both safe no-ops once there's nothing left
 * to clean up), so it's safe -- and intended -- to call unconditionally on every app launch rather
 * than gating it behind an "already ran once" flag.
 */
internal object LegacyNotificationCleanup {
    // The exact identifiers the old terminal-outcome notification/channel used to use --
    // preserved here purely so they can be retracted, never reused for anything new.
    private const val RESULT_CHANNEL_ID = "recording_result_channel"
    private const val RESULT_NOTIFICATION_ID = 1002

    /** Cancels the obsolete notification (if still showing -- e.g. this app was already installed
     * from a build predating this cleanup) and deletes its channel on Android 8.0+ (channels don't
     * exist before that). Call from a normal application-launch path -- see
     * [MainActivity.onCreate] -- so this runs regardless of whether the user ever starts a new
     * recording; it must not depend on [RecordingService] running at all. */
    fun run(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.cancel(RESULT_NOTIFICATION_ID)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.deleteNotificationChannel(RESULT_CHANNEL_ID)
        }
    }
}
