package com.grardb.thorcuts.core.model

/**
 * M1's one rule, as a Shortcut, until the config layer exists.
 *
 * It compiles to fixtures/ruletable/m1-swap.bin at generation 1, so the bootstrap
 * and the golden fixture pin each other. This object dies with the config layer.
 */
object Bootstrap {
    /** BTN_NORTH — the X button on the Thor's pad (M1 findings, control table). */
    val X = Control(Device.PAD, EvdevType.KEY, 307, 1)

    val SHORTCUTS: List<Shortcut> = listOf(
        Shortcut(
            id = "m1-swap-screens",
            trigger = Trigger(
                modifiers = setOf(ModifierKey.AYN),
                buttons = setOf(X),
                style = TriggerStyle.HOLD,
                holdMs = 400,   // TC_HOLD_MS in matcher.h
            ),
            action = Action.SwapScreens,
            scope = Scope.Global(),
        )
    )
}
