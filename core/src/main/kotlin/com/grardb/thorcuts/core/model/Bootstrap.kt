package com.grardb.thorcuts.core.model

/**
 * The shortcuts the app pushes until the config layer exists. Compiled at generation 1
 * this equals fixtures/ruletable/bootstrap.bin, so the list and the golden bytes pin
 * each other. This object dies with the config layer.
 *
 * Rules 1 and 2 are TEMPORARY, for the matcher rewrite's device run: an axis button and
 * a bare modifier, each dispatching an action that reports "not implemented yet" so the
 * fire is visible on screen. Rule 2 replaces AYN's bare-tap job for as long as it ships.
 */
object Bootstrap {
    /** BTN_NORTH — the X button on the Thor's pad (M1 findings, control table). */
    val X = Control(Device.PAD, EvdevType.KEY, 307, 1)

    /** ABS_HAT0X at -1 — D-pad left. The D-pad emits no key events (M1 findings). */
    val DPAD_LEFT = Control(Device.PAD, EvdevType.ABS, 16, -1)

    val SHORTCUTS: List<Shortcut> = listOf(
        Shortcut(
            id = "m1-swap-screens",
            trigger = Trigger(
                modifiers = setOf(ModifierKey.AYN),
                buttons = setOf(X),
                style = TriggerStyle.HOLD,
                holdMs = 400,
            ),
            action = Action.SwapScreens,
        ),
        Shortcut(
            id = "dpad-left-closes",
            trigger = Trigger(modifiers = setOf(ModifierKey.AYN), buttons = setOf(DPAD_LEFT)),
            action = Action.CloseScreen(ScreenTarget.FOCUSED),
        ),
        Shortcut(
            id = "tap-ayn-opens",
            trigger = Trigger(modifiers = setOf(ModifierKey.AYN)),
            action = Action.OpenThorcuts(ScreenTarget.TOP),
        ),
    )
}
