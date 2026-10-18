package com.grardb.thorcuts

/**
 * The monitor's bookkeeping, pure and host-testable (the :app side's logic lives here, not in
 * the AccessibilityService): which window the controller's input reaches, forget the windows
 * that are gone, and name the focused one's package when a window-state-change event has
 * already told us it. The map is the caller's, mutated in place — [ForegroundAppMonitor]
 * holds one for the process.
 */
object FocusedWindow {

    /** One window as the service lists it: [isActive] is true for exactly the window on the
     *  top-focused display; [isFocused] for the focused window of EACH display. */
    data class Candidate(val id: Int, val isActive: Boolean, val isFocused: Boolean)

    /**
     * The window the controller's input reaches: the active one, else the first focused one,
     * else null. MEASURED 2026-10-16 on the Thor: with an app on the bottom screen and
     * Thorcuts on the top, both windows reported isFocused=true, so isFocused alone always
     * picked the top screen; isActive names the one (`mTopFocusedDisplayId` in dumpsys).
     * The device run found that; this function is what keeps it found.
     */
    fun choose(windows: List<Candidate>): Int? =
        (windows.firstOrNull { it.isActive } ?: windows.firstOrNull { it.isFocused })?.id

    fun pick(
        windowIds: Collection<Int>,
        focusedId: Int?,
        packages: MutableMap<Int, String>,
    ): String? {
        packages.keys.retainAll(windowIds.toSet())
        return focusedId?.let(packages::get)
    }
}
