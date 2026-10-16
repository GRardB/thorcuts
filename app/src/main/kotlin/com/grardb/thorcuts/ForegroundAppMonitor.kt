package com.grardb.thorcuts

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * The foreground-app monitor (parent spec §12): an accessibility service the user enables
 * once in Settings. Android reports every window change, per screen; this names the app that
 * holds input focus and hands it to the coordinator, which recompiles the rule table for it
 * (M4). It lives in :app, not :platform, because it reaches [ThorcutsApp]'s coordinator —
 * the keep-alive service set the precedent.
 *
 * A window's package comes from the TYPE_WINDOW_STATE_CHANGED event that brought it to front
 * (cheap, on the main thread); the focused window's root is asked only when no event has
 * named it yet (an app already up when the service connects), which is an IPC into that app
 * and so is avoided on the common path. The callbacks hand off at once — the store
 * recompiles on its own thread (§8).
 */
class ForegroundAppMonitor : AccessibilityService() {

    private val coordinator get() = ThorcutsApp.of(this).coordinator

    /** Window id -> package, from the state-change events; pruned to the windows that exist. */
    private val packages = mutableMapOf<Int, String>()

    override fun onServiceConnected() {
        Log.i(TAG, "MONITOR_CONNECTED")
        coordinator.monitor(true)
        report()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            event.packageName?.let { packages[event.windowId] = it.toString() }
        }
        report()
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        Log.i(TAG, "MONITOR_DESTROYED")
        coordinator.foreground(null)
        coordinator.monitor(false)
        super.onDestroy()
    }

    private fun report() {
        val windows = windowsOnAllDisplays.let { all ->
            (0 until all.size()).flatMap { all.valueAt(it) }
        }
        // The window the controller's input reaches: the ACTIVE one. On the Thor each
        // screen keeps its own focused window (MEASURED 2026-10-16: with an app on the
        // bottom and Thorcuts on the top, both reported isFocused=true), so isFocused
        // alone always picked the top screen; isActive is true for exactly the window on
        // the top-focused display.
        val focused = windows.firstOrNull { it.isActive } ?: windows.firstOrNull { it.isFocused }
        val known = FocusedWindow.pick(windows.map { it.id }, focused?.id, packages)
        // A window already up when the service connected has emitted no event yet: ask it
        // once (an IPC into that app), and remember the answer.
        val pkg = known ?: focused?.let { w ->
            w.root?.packageName?.toString()?.also { packages[w.id] = it }
        }
        coordinator.foreground(pkg)
    }

    private companion object {
        const val TAG = "ThorcutsApp"
    }
}
