package com.grardb.thorcuts.core.model

/** How a trigger completes. [wire] is the rule table's style byte. */
enum class TriggerStyle(val wire: Int) { PRESS(0), HOLD(1) }

/**
 * A trigger starts with one or more modifiers and may add up to four buttons
 * (parent spec §6). A bare modifier — no buttons — is valid: `tap AYN`, `hold AYN 500ms`.
 *
 * Every invariant the rule table's parser enforces is enforced here first, so a
 * Trigger that exists is one the helper will accept.
 */
data class Trigger(
    val modifiers: Set<ModifierKey>,
    val buttons: Set<Control> = emptySet(),
    val style: TriggerStyle = TriggerStyle.PRESS,
    val holdMs: Int? = null,
) {
    init {
        require(modifiers.isNotEmpty()) { "a trigger starts with a modifier" }
        require(buttons.size <= MAX_BUTTONS) {
            "at most $MAX_BUTTONS buttons, got ${buttons.size}"
        }
        require(buttons.none(ModifierKey::isModifier)) { "a modifier cannot also be a button" }
        when (style) {
            TriggerStyle.PRESS -> require(holdMs == null) { "PRESS has no hold duration" }
            TriggerStyle.HOLD -> require(holdMs != null && holdMs in MIN_HOLD_MS..MAX_HOLD_MS) {
                "HOLD needs holdMs in $MIN_HOLD_MS..$MAX_HOLD_MS, got $holdMs"
            }
        }
    }

    companion object {
        const val MAX_BUTTONS = 4
        const val MIN_HOLD_MS = 1
        const val MAX_HOLD_MS = 65535   // the wire's u16
    }
}

/**
 * Which screen an action aims at. FOCUSED is resolved when the action RUNS, from the
 * window state at that moment: a fired rule arrives with no screen context of its own.
 */
enum class ScreenTarget { TOP, BOTTOM, FOCUSED }

/**
 * Parent spec §9's six v1 actions, as data only. Nothing in :core dispatches them;
 * the app resolves a fired rule back to its Shortcut and acts from there.
 */
sealed interface Action {
    data object SwapScreens : Action
    data object MoveFocusedToOtherScreen : Action
    data class CloseScreen(val screen: ScreenTarget) : Action
    data class GoHome(val screen: ScreenTarget) : Action
    data class OpenThorcuts(val screen: ScreenTarget) : Action
}

/** Where a shortcut applies (parent spec §10). */
sealed interface Scope {
    /** Everywhere except [excluded]. */
    data class Global(val excluded: Set<String> = emptySet()) : Scope

    /** Only in [included] — "these four emulators" is a real case. */
    data class Apps(val included: Set<String>) : Scope {
        init { require(included.isNotEmpty()) { "an Apps scope names at least one package" } }
    }
}

data class Shortcut(
    val id: String,
    val trigger: Trigger,
    val action: Action,
    val scope: Scope = Scope.Global(),
    val enabled: Boolean = true,
) {
    init { require(id.isNotBlank()) { "a shortcut needs an id" } }
}
