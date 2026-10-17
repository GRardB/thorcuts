package com.grardb.thorcuts

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * The keep-alive notification's channel. Android requires a notification while
 * [HelperService] holds the process (parent §7: the price of the holder); what an app may
 * choose is how loud, and MIN is the floor — no status-bar icon, one collapsed row at the
 * bottom of the shade. Only the user can go further, with the toggle on the channel's own
 * Settings page, which [settings] opens; the service keeps running without it (Android 13
 * lists it in the shade's active-apps chip instead).
 *
 * Created at every process start, not in the service. A channel's importance is fixed when
 * it is first created (a later create lowers it only for a user who never touched it, and a
 * deleted id comes back with its old settings), so the quieter channel has a NEW id and the
 * holder piece's `status` channel is deleted — and [settings] then always has a page to open,
 * even before the switch was ever on.
 */
object KeepAliveChannel {
    const val ID = "running"

    /** The holder piece's channel, IMPORTANCE_LOW. Deleting an absent channel is a no-op. */
    private const val OLD_ID = "status"

    fun create(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.deleteNotificationChannel(OLD_ID)
        manager.createNotificationChannel(
            NotificationChannel(
                ID,
                context.getString(R.string.running_channel),
                NotificationManager.IMPORTANCE_MIN,
            ).apply { description = context.getString(R.string.running_channel_description) }
        )
    }

    /** The channel's own page in Settings. NEW_TASK: started from a notification action and
     *  from a composition context, neither an Activity result. */
    fun settings(context: Context): Intent =
        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, ID)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
