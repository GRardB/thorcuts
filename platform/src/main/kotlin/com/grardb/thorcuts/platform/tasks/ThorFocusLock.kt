package com.grardb.thorcuts.platform.tasks

import android.content.ContentResolver
import android.provider.Settings
import com.grardb.thorcuts.core.model.ScreenTarget

/**
 * The Thor's Focus Mode: which screen the controller's buttons reach. The quick panel offers
 * Auto-lock (the most recently tapped screen), Top screen and Bottom screen, and writes the
 * choice to [KEY] as 0, 1, 2 — a `Settings.System` key any app may read, no permission
 * (measured 2026-10-20 by diffing `settings list system` across the three choices;
 * docs/superpowers/findings/2026-10-20-focus-lock-recon.md). The window manager's focused
 * root task — the tool's `focused` — still follows the last touch under a lock, which is why
 * an action aimed at FOCUSED asks here first ([TaskActions]). [read] is asked on every such
 * fire, never cached, so the quick panel is honoured by the next combo.
 */
object ThorFocusLock {
    const val KEY = "screen_focus_lock"

    /** The screen a stored value locks to: TOP for 1, BOTTOM for 2, null for Auto-lock (0) and
     *  for anything unexpected — an unknown value is not a reason to act on no screen. */
    fun of(value: Int): ScreenTarget? = when (value) {
        1 -> ScreenTarget.TOP
        2 -> ScreenTarget.BOTTOM
        else -> null
    }

    /** The lock as the device holds it now. A missing key, or one that is not an int, reads as
     *  the default 0: Auto-lock. */
    fun read(resolver: ContentResolver): ScreenTarget? =
        of(Settings.System.getInt(resolver, KEY, 0))
}
