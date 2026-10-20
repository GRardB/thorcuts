package com.grardb.thorcuts.core.model

import com.grardb.thorcuts.core.safety.CommandSafety

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
    // The messages are what the editor shows under Save (every refusal comes back as one
    // `Rejected(reason)`), so each is a sentence that says what must change.
    init {
        require(modifiers.isNotEmpty()) { "A trigger must start with a modifier" }
        require(buttons.size <= MAX_BUTTONS) {
            "A trigger can have at most $MAX_BUTTONS buttons (got ${buttons.size})"
        }
        require(buttons.none(ModifierKey::isModifier)) { "A modifier cannot also be a button" }
        when (style) {
            TriggerStyle.PRESS -> require(holdMs == null) { "A tap has no timer" }
            TriggerStyle.HOLD -> require(holdMs != null && holdMs in MIN_HOLD_MS..MAX_HOLD_MS) {
                "The timer must be between $MIN_HOLD_MS and $MAX_HOLD_MS ms (got $holdMs)"
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
 * BOTH is both screens, each on its own; only an action that says so takes it — the four
 * screen actions below refuse it in their `init`, so a hand-written file cannot aim them at
 * both (brightness spec, 2026-10-20).
 */
enum class ScreenTarget { TOP, BOTTOM, FOCUSED, BOTH }

/** How a brightness action treats its percent: a step up, a step down, or the level itself. */
enum class BrightnessMode { INCREASE, DECREASE, SET }

/** The rule the four screen actions share: one screen, or the focused one — never BOTH. The
 *  message is a drop reason only; the editor never offers the radio to them. */
private fun requireOne(kind: String, screen: ScreenTarget) =
    require(screen != ScreenTarget.BOTH) { "$kind cannot aim at both screens" }

/**
 * Parent spec §9's actions, plus M7's screenshot, as data only. Nothing in :core dispatches
 * them; the app resolves a fired rule back to its Shortcut and acts from there. [OpenApp]
 * generalises what was `OpenThorcuts(screen)` (the follow-ups spec, 2026-10-18): any installed app,
 * by package, on a screen.
 */
sealed interface Action {
    data object SwapScreens : Action
    data object MoveFocusedToOtherScreen : Action
    data class CloseScreen(val screen: ScreenTarget) : Action {
        init { requireOne("CloseScreen", screen) }
    }
    data class GoHome(val screen: ScreenTarget) : Action {
        init { requireOne("GoHome", screen) }
    }

    /** Take a screenshot of [screen]: Android's own for the top screen, a root capture filed
     *  into the gallery for the bottom (M7, 2026-10-19). Nothing to validate: no value of
     *  this reaches a shell. */
    data class TakeScreenshot(val screen: ScreenTarget) : Action {
        init { requireOne("TakeScreenshot", screen) }
    }

    /** Open [packageName]'s launch activity on [screen]. The name is validated here because
     *  it reaches a root shell (`am start`); the codec's drop reason is this message. */
    data class OpenApp(val packageName: String, val screen: ScreenTarget) : Action {
        init {
            require(CommandSafety.isPackage(packageName)) {
                "The app must be named by its package name (got '$packageName')"
            }
            requireOne("OpenApp", screen)
        }
    }
}

/** Where a shortcut applies (parent spec §10). */
sealed interface Scope {
    /** Everywhere except [excluded]. */
    data class Global(val excluded: Set<String> = emptySet()) : Scope

    /** Only in [included] — "these four emulators" is a real case. */
    data class Apps(val included: Set<String>) : Scope {
        init { require(included.isNotEmpty()) { "Choose at least one app" } }
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
        require(id.isNotBlank()) { "A shortcut must have a name" }
        val t = trigger
        if (t.style == TriggerStyle.PRESS && t.buttons.isEmpty() && t.modifiers.size == 1) {
            val only = t.modifiers.single()
            require(only !in NAVIGATION) {
                "${ThorControls.name(only)} alone cannot be a shortcut: " +
                    "it would no longer ${NAVIGATION[only]}"
            }
        }
    }

    private companion object {
        /** The modifiers whose bare tap must keep its job, and what that job is. */
        val NAVIGATION = mapOf(ModifierKey.HOME to "go home", ModifierKey.BACK to "go back")
    }
}
