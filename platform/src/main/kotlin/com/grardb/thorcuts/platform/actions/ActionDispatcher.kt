package com.grardb.thorcuts.platform.actions

import com.grardb.thorcuts.core.model.Action
import com.grardb.thorcuts.platform.tasks.TaskActions

/**
 * Turns a fired Shortcut's Action into the privileged call that performs it.
 *
 * Call on Privilege.dispatcher: every branch blocks on pservice. Returns the outcome
 * LINE to show, verbatim — the tool's own result line, or `input`'s / `am`'s folded
 * output — which distinguishes a one-sided move from a partial failure (parent spec
 * §9), and "no app to close" from a mechanism that failed. The `when` is exhaustive on
 * purpose: a new Action variant is a compile error here.
 */
class ActionDispatcher(private val tasks: TaskActions) {

    fun dispatch(action: Action): String = when (action) {
        Action.SwapScreens -> tasks.swapScreens()
        Action.MoveFocusedToOtherScreen -> tasks.moveFocusedToOtherScreen()
        is Action.CloseScreen -> tasks.closeScreen(action.screen)
        is Action.GoHome -> tasks.goHome(action.screen)
        is Action.OpenApp -> tasks.openApp(action.packageName, action.screen)
        is Action.TakeScreenshot -> tasks.takeScreenshot(action.screen)
        is Action.Brightness ->
            tasks.changeBrightness(action.mode, action.percent, action.screen)
    }
}
