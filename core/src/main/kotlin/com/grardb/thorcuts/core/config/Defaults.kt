package com.grardb.thorcuts.core.config

import com.grardb.thorcuts.core.model.Action
import com.grardb.thorcuts.core.model.Control
import com.grardb.thorcuts.core.model.Device
import com.grardb.thorcuts.core.model.EvdevType
import com.grardb.thorcuts.core.model.ModifierKey
import com.grardb.thorcuts.core.model.Shortcut
import com.grardb.thorcuts.core.model.Trigger
import com.grardb.thorcuts.core.model.TriggerStyle

/**
 * The first-run config: what a user has before they have edited anything. ONE shortcut,
 * M1's rule, and deliberately nothing else (carried finding F1): AYN is a modifier here, so
 * a bare AYN tap opens a session, finds no bare rule, and is synthesised down-then-up on
 * release — the quick-settings overlay still appears, exactly as §6 promises for a modifier
 * with a job of its own. The temporary bootstrap rules that bound that tap went with
 * `Bootstrap` itself.
 *
 * Compiled at generation 1 this equals fixtures/ruletable/m1-swap.bin (ids are not
 * encoded), so an existing golden fixture pins the default.
 */
object Defaults {
    /** BTN_NORTH — the X button on the Thor's pad (M1 findings, control table). */
    val X = Control(Device.PAD, EvdevType.KEY, 307, 1)

    val SWAP_SCREENS = Shortcut(
        id = "swap-screens",
        trigger = Trigger(
            modifiers = setOf(ModifierKey.AYN),
            buttons = setOf(X),
            style = TriggerStyle.HOLD,
            holdMs = 400,
        ),
        action = Action.SwapScreens,
    )

    val CONFIG = ThorcutsConfig(listOf(SWAP_SCREENS))
}
