package com.grardb.thorcuts.platform.config

import android.content.Context
import com.grardb.thorcuts.platform.coordinator.EnabledSwitch

/**
 * One boolean in `shared_prefs/thorcuts.xml`, key `enabled`, default false.
 *
 * `commit()`, not `apply()`: the panic path writes OFF and then releases the foreground
 * service, after which a UI-less process may die at once, and an asynchronous write could
 * die with it — leaving the switch on and the helper restarting at the next boot, the exact
 * thing the panic combo must prevent. The write is a few bytes on the privileged or the
 * reader thread, never on main.
 */
class PrefsEnabledSwitch(context: Context) : EnabledSwitch {

    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun read(): Boolean = prefs.getBoolean(KEY, false)

    override fun write(on: Boolean) {
        prefs.edit().putBoolean(KEY, on).commit()
    }

    companion object {
        const val FILE = "thorcuts"
        const val KEY = "enabled"
    }
}
