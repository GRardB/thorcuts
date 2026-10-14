package com.grardb.thorcuts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log

/**
 * The keep-alive: a foreground service that exists exactly while the switch is on, so the
 * process — and with it the helper, the store and the log the coordinator holds — survives
 * the Activity being closed, its task removed, a swipe from recents, and a system that would
 * otherwise reclaim a cached process. It holds nothing itself; the coordinator does.
 *
 * Started and stopped by the coordinator's keeper ([ThorcutsApp] maps it here). START_STICKY,
 * so a process the system killed comes back with the service, whose onStartCommand asks the
 * coordinator to start the helper again — the one path by which a kill recovers without the
 * user. The service type is the manifest's (`specialUse`): FOREGROUND_SERVICE_TYPE_MANIFEST
 * means "whatever is declared", which an API 33 ROM stores and an API 34+ ROM enforces.
 */
class HelperService : Service() {

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "SERVICE_CREATE")
        live = true
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                getString(R.string.status_channel),
                NotificationManager.IMPORTANCE_LOW,
            )
        )
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                // Resume the one instance: a tap must not stack a second MainActivity on it
                // (the framework forces NEW_TASK from here; SINGLE_TOP is the half it does not).
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_thorcuts)
            .setContentTitle(getString(R.string.status_title))
            .setContentText(getString(R.string.status_text))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST
        startForeground(NOTIFICATION_ID, notification, type)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A null intent is the system restarting a sticky service after a kill.
        Log.i(TAG, "SERVICE_START sticky=${intent == null}")
        ThorcutsApp.of(this).coordinator.ensureRunning()
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "SERVICE_DESTROY")
        live = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "ThorcutsApp"
        private const val CHANNEL = "status"
        private const val NOTIFICATION_ID = 1

        /**
         * Whether the service is up: set when it is created and when a start is issued,
         * cleared when it is destroyed and when a stop is issued. A hold on a held process
         * is then a no-op rather than a second startForegroundService — which, issued from
         * the service's own onStartCommand on a sticky restart, would re-deliver
         * onStartCommand to the service itself.
         */
        @Volatile private var live = false

        /**
         * Hold the process. Every caller is in a state Android allows a foreground-service
         * start from — the Activity in front, a BOOT_COMPLETED receiver, the service itself
         * — so a refusal is not expected. The keeper MUST NOT THROW (the coordinator's
         * transition would latch for the life of the process), so every RuntimeException —
         * the not-allowed IllegalStateException subclasses (API 31+), a SecurityException, a
         * dead system — is logged, and the helper start that follows proceeds unheld.
         */
        fun start(context: Context) {
            if (live) return
            live = true
            try {
                context.startForegroundService(Intent(context, HelperService::class.java))
            } catch (e: RuntimeException) {
                live = false
                Log.w(TAG, "SERVICE_START refused: $e")
            }
        }

        /** Release the process. Must not throw either; see [start]. */
        fun stop(context: Context) {
            live = false
            try {
                context.stopService(Intent(context, HelperService::class.java))
            } catch (e: RuntimeException) {
                Log.w(TAG, "SERVICE_STOP refused: $e")
            }
        }
    }
}
