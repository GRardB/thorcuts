package com.grardb.thorcuts.core.model

/** How a trigger completes. [wire] is the rule table's style byte. */
enum class TriggerStyle(val wire: Int) { PRESS(0), HOLD(1) }

/**
 * A trigger starts with one or more modifiers and may add up to four buttons
 * (parent spec §6). A bare modifier — no buttons — is valid: `tap AYN`, `hold AYN 500ms`.
 *
 * Every invariant the rule table's parser enforces is enforced here first, so a
 * Trigger that exists is one the helper will accept. What the APP refuses — a bare tap on
 * Home or Back — is [Shortcut]'s rule, not this type's: the format's fixtures encode every
 * bare modifier, and nothing reaches the helper except through a shortcut.
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

/**
 * One rule here is the app's, not the wire's: a bare PRESS on Home alone or on Back alone
 * cannot be a shortcut. It would fire on the release instead of synthesising the tap, so the
 * system would never see that button again wherever the shortcut applies — the user stranded
 * by accident (conflict-detector spec, Rule 1). A timed bare Home still goes home on a quick
 * tap, and Home with a button or with another modifier leaves a lone Home intact; those bind.
 * The rule is enforced on the shortcut and not on [Trigger] because a trigger is the wire's
 * unit (the format's fixtures encode each bare modifier) and nothing reaches the helper
 * except through a shortcut — the codec's read, the editor's save and the store's `update`
 * all build one.
 */
data class Shortcut(
    val id: String,
    val trigger: Trigger,
    val action: Action,
    val scope: Scope = Scope.Global(),
    val enabled: Boolean = true,
) {
    init {
        require(id.isNotBlank()) { "a shortcut needs an id" }
        val t = trigger
        if (t.style == TriggerStyle.PRESS && t.buttons.isEmpty() && t.modifiers.size == 1) {
            val only = t.modifiers.single()
            require(only !in NAVIGATION) {
                "tap ${only.name} alone is not bindable: it would no longer ${NAVIGATION[only]}"
            }
        }
    }

    private companion object {
        /** The modifiers whose bare tap must keep its job, and what that job is. */
        val NAVIGATION = mapOf(ModifierKey.HOME to "go home", ModifierKey.BACK to "go back")
    }
}
