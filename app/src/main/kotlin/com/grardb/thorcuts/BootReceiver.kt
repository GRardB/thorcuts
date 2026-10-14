package com.grardb.thorcuts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Boot: if the switch is on, the helper comes up before the user touches anything. Returns
 * at once — the keeper starts the service synchronously inside ensureRunning (a
 * BOOT_COMPLETED receiver is exempt from the background start restriction), and the
 * privileged start that follows runs on its own thread in a process the service now holds.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Log.i(TAG, "BOOT_COMPLETED")
        ThorcutsApp.of(context).coordinator.ensureRunning()
    }

    private companion object {
        const val TAG = "ThorcutsApp"
    }
}
