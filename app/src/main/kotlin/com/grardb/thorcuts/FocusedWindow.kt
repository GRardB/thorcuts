package com.grardb.thorcuts

/**
 * The monitor's bookkeeping, pure and host-testable (the :app side's logic lives here, not in
 * the AccessibilityService): forget the windows that are gone, and name the focused one's
 * package when a window-state-change event has already told us it. The map is the caller's,
 * mutated in place — [ForegroundAppMonitor] holds one for the process.
 */
object FocusedWindow {
    fun pick(
        windowIds: Collection<Int>,
        focusedId: Int?,
        packages: MutableMap<Int, String>,
    ): String? {
        packages.keys.retainAll(windowIds.toSet())
        return focusedId?.let(packages::get)
    }
}
