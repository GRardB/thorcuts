package com.grardb.thorcuts

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

/**
 * The keep-alive notification's channel. Android requires a notification while
 * [HelperService] holds the process (parent §7: the price of the holder), and it shows a
 * foreground service's notification at LOW importance whatever the channel asks (MEASURED
 * 2026-10-17 on the Thor: a MIN channel still drew the status-bar icon, and the record read
 * importance=2). So the channel asks for LOW — silent, in the shade's silent section; an
 * existing MIN one stays MIN, the posted row is LOW regardless — and the only quieter state
 * is the user's own: a long-press on the row turns the channel off, the
 * notification goes, and the service keeps running (MEASURED: still foreground, the helper
 * still firing). Android does not re-post the notification when the channel is turned back
 * on; the row returns when the service next posts, at the next switch OFF → ON or a reboot.
 *
 * Created at every process start (two binder calls, idempotent), under its own id: the piece first
 * aimed at MIN, which needs a new id (a channel's importance is fixed when it is first
 * created, and a deleted id recreated comes back with its old settings), and the id stayed
 * once shipped; the holder piece's `status` channel is deleted.
 */
object KeepAliveChannel {
    const val ID = "running"

    /** The holder piece's channel. Deleting an absent channel is a no-op. */
    private const val OLD_ID = "status"

    fun create(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.deleteNotificationChannel(OLD_ID)
        manager.createNotificationChannel(
            NotificationChannel(
                ID,
                context.getString(R.string.running_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = context.getString(R.string.running_channel_description) }
        )
    }
}
