package com.grardb.thorcuts.platform.tasks

import com.grardb.thorcuts.platform.privilege.PrivilegeGateway

/**
 * The app-side face of [TaskTool]. Blocking — call on Privilege.dispatcher.
 */
class TaskActions(private val gateway: PrivilegeGateway) {

    /** Every task with its display and visibility, for diagnostics. */
    fun listTasks(): String =
        gateway.runEntryPoint(ENTRY, "list") ?: "ERR no reply from pservice"

    /**
     * Swap whatever is visible on the two screens. Blocking — call on
     * [com.grardb.thorcuts.platform.privilege.Privilege.dispatcher].
     *
     * Returns the tool's own result line verbatim (e.g. "OK swapped 12@0 <-> 7@4",
     * a one-sided-move OK, or a partial-failure ERR) so the caller can show what
     * actually happened rather than a collapsed true/false. If the pservice
     * bridge returns no reply at all (e.g. the binder transaction failed), that
     * is reported the same way [listTasks] reports it: an "ERR no reply from
     * pservice" line, so callers only ever need to handle one shape of result.
     */
    fun swapScreens(): String {
        val out = gateway.runEntryPoint(
            ENTRY,
            "swap",
            ThorDisplays.TOP.toString(),
            ThorDisplays.BOTTOM.toString(),
        )
        return out ?: "ERR no reply from pservice"
    }

    private companion object {
        const val ENTRY = "com.grardb.thorcuts.platform.tasks.TaskTool"
    }
}
