package com.grardb.thorcuts

import com.grardb.thorcuts.core.model.Action
import com.grardb.thorcuts.core.model.Control
import com.grardb.thorcuts.core.model.ModifierKey
import com.grardb.thorcuts.core.model.Scope
import com.grardb.thorcuts.core.model.ScreenTarget
import com.grardb.thorcuts.core.model.Shortcut
import com.grardb.thorcuts.core.model.ThorControls
import com.grardb.thorcuts.core.model.Trigger
import com.grardb.thorcuts.core.model.TriggerStyle

/** Where a shortcut applies, as the editor's three radios offer it. */
enum class ScopeKind(val label: String) {
    EVERYWHERE("Everywhere"),
    EXCEPT("Everywhere except…"),
    ONLY("Only in…");
}

/** The five kinds of action the editor offers; three of them aim at a screen. */
enum class ActionKind(val label: String, val takesScreen: Boolean) {
    SWAP("Swap the screens", false),
    MOVE("Move the focused app to the other screen", false),
    CLOSE("Close the app on", true),
    HOME("Go home on", true),
    OPEN("Open Thorcuts on", true);

    fun action(screen: ScreenTarget): Action = when (this) {
        SWAP -> Action.SwapScreens
        MOVE -> Action.MoveFocusedToOtherScreen
        CLOSE -> Action.CloseScreen(screen)
        HOME -> Action.GoHome(screen)
        OPEN -> Action.OpenThorcuts(screen)
    }

    companion object {
        fun of(action: Action): ActionKind = when (action) {
            Action.SwapScreens -> SWAP
            Action.MoveFocusedToOtherScreen -> MOVE
            is Action.CloseScreen -> CLOSE
            is Action.GoHome -> HOME
            is Action.OpenThorcuts -> OPEN
        }

        fun screenOf(action: Action): ScreenTarget? = when (action) {
            is Action.CloseScreen -> action.screen
            is Action.GoHome -> action.screen
            is Action.OpenThorcuts -> action.screen
            else -> null
        }
    }
}

/** The screen an action aims at, as the list names it. */
fun ScreenTarget.label(): String = when (this) {
    ScreenTarget.TOP -> "the top screen"
    ScreenTarget.BOTTOM -> "the bottom screen"
    ScreenTarget.FOCUSED -> "the focused screen"
}

/** The same, as the editor's nested choice names it — sentence case, FOCUSED explained once,
 *  where it is chosen. */
fun ScreenTarget.choiceLabel(): String = when (this) {
    ScreenTarget.TOP -> "Top screen"
    ScreenTarget.BOTTOM -> "Bottom screen"
    ScreenTarget.FOCUSED -> FOCUSED_EXPLAINED
}

/** An action as the list names it: "Close the app on the focused screen". */
fun Action.label(): String {
    val kind = ActionKind.of(this)
    val screen = ActionKind.screenOf(this) ?: return kind.label
    return "${kind.label} ${screen.label()}"
}

/** One line for a scope that is not a plain Global(), null for one. Shown, never edited
 *  here: M4's app picker is where a scope is set. */
fun Scope.line(): String? = when (this) {
    is Scope.Global ->
        if (excluded.isEmpty()) null
        else "Everywhere except: ${excluded.sorted().joinToString(", ")}"
    is Scope.Apps -> "Applies only in: ${included.sorted().joinToString(", ")}"
}

const val FOCUSED_EXPLAINED =
    "Focused screen — the one you last touched or that last took input"

/** The editor's copy, pinned by the spec. */
object Copy {
    const val RECORDING =
        "Press the combo now — nothing you press reaches the device until you let go"
    const val NOT_RECORDED = "Not recorded yet"
    const val RECORD = "Record"
    const val RECORD_OFF = "Turn shortcuts on to record"
    const val NEEDS_MODIFIER = "A shortcut must start with AYN, Home, Back, Start or Select."
    const val MODIFIER_NOTE = "A shortcut on Start or Select makes a plain tap of that button " +
        "reach the game only when it is released."
    const val NO_APPS = "No apps chosen yet"
    const val LATE = "With a timer, this combo reaches the game only after the timer or the " +
        "release — it will feel late in that game."
}

/**
 * What the editor holds while a shortcut is being added or edited. The model's rules are not
 * repeated here: [trigger] and [toShortcut] build the real values and throw the model's own
 * messages, and they are called only where a throw is caught — inside the edit lambda the
 * ViewModel hands the coordinator (a `Rejected(reason)` under Save) and under `runCatching`
 * for the warnings.
 */
data class EditorDraft(
    /** The shortcut being edited; null when adding. Its enabled flag is kept; its scope is
     *  edited here (M4). */
    val original: Shortcut? = null,
    val id: String = "",
    val modifiers: Set<ModifierKey> = emptySet(),
    val buttons: Set<Control> = emptySet(),
    val timer: Boolean = false,
    val holdMs: String = "400",
    val action: ActionKind = ActionKind.SWAP,
    val screen: ScreenTarget = ScreenTarget.FOCUSED,
    val scope: ScopeKind = ScopeKind.EVERYWHERE,
    /** The packages the scope names: the exclusions for [ScopeKind.EXCEPT], the inclusions
     *  for [ScopeKind.ONLY], ignored for [ScopeKind.EVERYWHERE]. */
    val apps: Set<String> = emptySet(),
    /** The app picker is open over the editor. */
    val picker: Boolean = false,
    /** The last Rejected reason, shown under Save until the next change. */
    val error: String? = null,
) {
    val recorded: Boolean get() = modifiers.isNotEmpty() || buttons.isNotEmpty()

    /** The trigger the draft describes, or a throw with the model's message. */
    fun trigger(): Trigger {
        val ms = if (!timer) {
            null
        } else {
            holdMs.trim().toIntOrNull()
                ?: throw IllegalArgumentException("The timer must be a number of milliseconds")
        }
        val style = if (timer) TriggerStyle.HOLD else TriggerStyle.PRESS
        return Trigger(modifiers, buttons, style, ms)
    }

    /** The shortcut the draft describes; the scope comes from the radios and the picked
     *  apps, the original's enabled flag is kept. */
    fun toShortcut(): Shortcut = Shortcut(
        id = id.trim(),
        trigger = trigger(),
        action = action.action(screen),
        scope = toScope(),
        enabled = original?.enabled ?: true,
    )

    /** The scope the draft describes, or a throw with the draft's own message when an app
     *  list is empty (the model's "an Apps scope names at least one package" names a type;
     *  this names what to do). */
    private fun toScope(): Scope = when (scope) {
        ScopeKind.EVERYWHERE -> Scope.Global()
        ScopeKind.EXCEPT -> { requireApps(); Scope.Global(apps) }
        ScopeKind.ONLY -> { requireApps(); Scope.Apps(apps) }
    }

    private fun requireApps() = require(apps.isNotEmpty()) { "Choose at least one app" }

    /** The recorded combo as text: the trigger's description when it is one, else the names
     *  pressed so far (no modifier yet), else "Not recorded yet". */
    val triggerText: String
        get() {
            if (!recorded) return Copy.NOT_RECORDED
            runCatching { trigger() }.getOrNull()?.let { return ThorControls.describe(it) }
            val names = modifiers.map { ThorControls.name(it) } +
                buttons.map { ThorControls.name(it) }
            return names.joinToString(" + ")
        }

    companion object {
        fun of(s: Shortcut) = EditorDraft(
            original = s,
            id = s.id,
            modifiers = s.trigger.modifiers,
            buttons = s.trigger.buttons,
            timer = s.trigger.style == TriggerStyle.HOLD,
            holdMs = (s.trigger.holdMs ?: 400).toString(),
            action = ActionKind.of(s.action),
            screen = ActionKind.screenOf(s.action) ?: ScreenTarget.FOCUSED,
            scope = scopeKindOf(s.scope),
            apps = appsOf(s.scope),
        )

        private fun scopeKindOf(scope: Scope): ScopeKind = when (scope) {
            is Scope.Global -> if (scope.excluded.isEmpty()) ScopeKind.EVERYWHERE
            else ScopeKind.EXCEPT
            is Scope.Apps -> ScopeKind.ONLY
        }

        private fun appsOf(scope: Scope): Set<String> = when (scope) {
            is Scope.Global -> scope.excluded
            is Scope.Apps -> scope.included
        }
    }
}
