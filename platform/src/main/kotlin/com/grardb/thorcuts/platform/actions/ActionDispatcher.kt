package com.grardb.thorcuts.platform.actions

import com.grardb.thorcuts.core.model.Action
import com.grardb.thorcuts.platform.tasks.TaskActions

/**
 * Turns a fired Shortcut's Action into the privileged call that performs it.
 *
 * Call on Privilege.dispatcher: every branch that does anything blocks on pservice.
 * Returns the outcome LINE to show, verbatim — the swap tool's own result line, which
 * distinguishes a one-sided move from a partial failure (parent spec §9), or a
 * "not implemented yet" for the five actions whose mechanisms are still unverified.
 * The `when` is exhaustive on purpose: a new Action variant is a compile error here.
 */
class ActionDispatcher(private val tasks: TaskActions) {

    fun dispatch(action: Action): String = when (action) {
        Action.SwapScreens -> tasks.swapScreens()
        Action.MoveFocusedToOtherScreen -> notYet("MoveFocusedToOtherScreen")
        is Action.CloseScreen -> notYet("CloseScreen(${action.screen})")
        is Action.GoHome -> notYet("GoHome(${action.screen})")
        is Action.OpenThorcuts -> notYet("OpenThorcuts(${action.screen})")
    }

    private fun notYet(what: String): String = "$what: not implemented yet"
}
