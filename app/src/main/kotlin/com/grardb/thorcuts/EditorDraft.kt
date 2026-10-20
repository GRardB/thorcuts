package com.grardb.thorcuts

import com.grardb.thorcuts.core.model.Action
import com.grardb.thorcuts.core.model.BrightnessMode
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

/** The ten kinds of action the editor offers: eight aim at a screen, one of those opens an
 *  app chosen in the picker, the three brightness kinds also take a percent, and those three
 *  and close-all may aim at both screens at once. */
enum class ActionKind(
    val label: String,
    val takesScreen: Boolean,
    /** The three brightness kinds: a `Percent` field under the select. */
    val takesPercent: Boolean = false,
    /** The kinds that aim at both screens at once: the model refuses BOTH on the rest, so
     *  the radio is not offered for them. */
    val allowsBoth: Boolean = false,
) {
    SWAP("Swap the screens", false),
    MOVE("Move the focused app to the other screen", false),
    CLOSE("Close the app on", true),
    CLOSE_ALL("Close all apps on", true, allowsBoth = true),
    HOME("Go home on", true),
    OPEN("Open an app", true),
    SHOT("Take a screenshot of", true),
    BRIGHTER("Increase brightness by", true, takesPercent = true, allowsBoth = true),
    DIMMER("Decrease brightness by", true, takesPercent = true, allowsBoth = true),
    BRIGHTNESS("Set brightness to", true, takesPercent = true, allowsBoth = true);

    /** The editor's select: an action that aims at a screen trails an ellipsis, since its
     *  screen (and its app, or its percent) is the choice beneath it; the list's
     *  [Action.label] completes it. */
    val choiceLabel: String get() = if (takesScreen) "$label…" else label

    /** The screen radios this kind offers, in the editor's order. */
    val screens: List<ScreenTarget>
        get() = if (allowsBoth) ScreenTarget.entries else ScreenTarget.entries - ScreenTarget.BOTH

    /** The action, from the nested choices: [screen] for the kinds that aim at one, [app]
     *  (a package) for [OPEN] — refused with `Choose an app` when there is none — and
     *  [percent] for the three brightness kinds, refused with [Copy.PERCENT_NUMBER] when
     *  the draft could not read one. */
    fun action(screen: ScreenTarget, app: String?, percent: Int?): Action {
        fun pct(): Int = percent ?: throw IllegalArgumentException(Copy.PERCENT_NUMBER)
        return when (this) {
            SWAP -> Action.SwapScreens
            MOVE -> Action.MoveFocusedToOtherScreen
            CLOSE -> Action.CloseScreen(screen)
            CLOSE_ALL -> Action.CloseAllApps(screen)
            HOME -> Action.GoHome(screen)
            OPEN -> Action.OpenApp(app ?: throw IllegalArgumentException(Copy.CHOOSE_APP), screen)
            SHOT -> Action.TakeScreenshot(screen)
            BRIGHTER -> Action.Brightness(BrightnessMode.INCREASE, pct(), screen)
            DIMMER -> Action.Brightness(BrightnessMode.DECREASE, pct(), screen)
            BRIGHTNESS -> Action.Brightness(BrightnessMode.SET, pct(), screen)
        }
    }

    companion object {
        fun of(action: Action): ActionKind = when (action) {
            Action.SwapScreens -> SWAP
            Action.MoveFocusedToOtherScreen -> MOVE
            is Action.CloseScreen -> CLOSE
            is Action.CloseAllApps -> CLOSE_ALL
            is Action.GoHome -> HOME
            is Action.OpenApp -> OPEN
            is Action.TakeScreenshot -> SHOT
            is Action.Brightness -> when (action.mode) {
                BrightnessMode.INCREASE -> BRIGHTER
                BrightnessMode.DECREASE -> DIMMER
                BrightnessMode.SET -> BRIGHTNESS
            }
        }

        fun screenOf(action: Action): ScreenTarget? = when (action) {
            is Action.CloseScreen -> action.screen
            is Action.CloseAllApps -> action.screen
            is Action.GoHome -> action.screen
            is Action.OpenApp -> action.screen
            is Action.TakeScreenshot -> action.screen
            is Action.Brightness -> action.screen
            else -> null
        }

        fun appOf(action: Action): String? = (action as? Action.OpenApp)?.packageName

        fun percentOf(action: Action): Int? = (action as? Action.Brightness)?.percent
    }
}

/** Which list the picker is choosing for: the scope's apps (many, with a Done), or the one
 *  app an [ActionKind.OPEN] opens (a tap chooses and returns). */
enum class Picking { SCOPE_APPS, OPEN_APP }

/** The screen an action aims at, as the list names it. */
fun ScreenTarget.label(): String = when (this) {
    ScreenTarget.TOP -> "the top screen"
    ScreenTarget.BOTTOM -> "the bottom screen"
    ScreenTarget.FOCUSED -> "the focused screen"
    ScreenTarget.BOTH -> "both screens"
}

/** The same, as the editor's nested choice names it — sentence case, FOCUSED explained once,
 *  where it is chosen. */
fun ScreenTarget.choiceLabel(): String = when (this) {
    ScreenTarget.TOP -> "Top screen"
    ScreenTarget.BOTTOM -> "Bottom screen"
    ScreenTarget.FOCUSED -> FOCUSED_EXPLAINED
    ScreenTarget.BOTH -> "Both screens"
}

/** An action as the list names it: "Close the app on the focused screen", "Open Dolphin on
 *  the bottom screen" ([labels]: package → label, the list's; the package when it is not
 *  installed), "Increase brightness by 10% on both screens". */
fun Action.label(labels: Map<String, String> = emptyMap()): String {
    val kind = ActionKind.of(this)
    val screen = ActionKind.screenOf(this) ?: return kind.label
    val app = ActionKind.appOf(this)?.let { labels[it] ?: it }
    val percent = ActionKind.percentOf(this)
    return when {
        app != null -> "Open $app on ${screen.label()}"
        percent != null -> "${kind.label} $percent% on ${screen.label()}"
        else -> "${kind.label} ${screen.label()}"
    }
}

/** One line for a scope that is not a plain Global(), null for one: the apps by name
 *  ([labels]: package → label, the list's), by id when not installed, sorted as shown. Shown,
 *  never edited here: the app picker is where a scope is set. */
fun Scope.line(labels: Map<String, String> = emptyMap()): String? {
    fun names(packages: Set<String>): String =
        packages.map { labels[it] ?: it }.sortedBy { it.lowercase() }.joinToString(", ")
    return when (this) {
        is Scope.Global ->
            if (excluded.isEmpty()) null else "Everywhere except: ${names(excluded)}"
        is Scope.Apps -> "Applies only in: ${names(included)}"
    }
}

const val FOCUSED_EXPLAINED =
    "Focused screen — the one Focus Mode locks to, or the last touched on Auto-lock"

/** The editor's copy, pinned by the spec. */
object Copy {
    const val RECORDING =
        "Press the combo now — nothing you press reaches the device until you let go"
    const val NOT_RECORDED = "Not recorded yet"
    const val RECORD = "Record"
    const val RECORD_OFF = "Turn shortcuts on to record"
    const val NEEDS_MODIFIER = "A shortcut must start with AYN, Home, Back, Start or Select."
    const val MODIFIER_NOTE = "A shortcut on Start or Select makes a plain tap of that button " +
        "reach the game only when it is released, and a hold only after half a second."
    const val NO_APPS = "No apps chosen yet"
    const val NO_APP = "No app chosen yet"
    const val CHOOSE_APP = "Choose an app"
    const val PERCENT_NUMBER = "The percent must be a whole number"
    const val LATE = "With a timer, this combo reaches the app in front only after the timer " +
        "or the release — it will feel late there."
}

/**
 * What the editor holds while a shortcut is being added or edited (since 2026-10-20, a brightness
 * kind's percent too). The model's rules are not repeated here: [trigger] and [toShortcut]
 * build the real values and throw the model's own
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
    /** The package an [ActionKind.OPEN] opens; null until chosen, ignored for the rest. */
    val openApp: String? = null,
    /** The percent a brightness kind takes, as typed; ignored for the rest. */
    val percent: String = DEFAULT_PERCENT,
    val scope: ScopeKind = ScopeKind.EVERYWHERE,
    /** The packages the scope names: the exclusions for [ScopeKind.EXCEPT], the inclusions
     *  for [ScopeKind.ONLY], ignored for [ScopeKind.EVERYWHERE]. */
    val apps: Set<String> = emptySet(),
    /** The app picker is open over the editor, choosing for this; null when it is not. */
    val picker: Picking? = null,
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
        action = action.action(screen, openApp, percentValue()),
        scope = toScope(),
        enabled = original?.enabled ?: true,
    )

    /** The percent the draft's brightness kind takes, or null for a kind that takes none; a
     *  throw with the editor's message when the text is not a whole number. The model's
     *  ranges are not repeated: `Brightness`'s `init` throws them. */
    private fun percentValue(): Int? {
        if (!action.takesPercent) return null
        return percent.trim().toIntOrNull()
            ?: throw IllegalArgumentException(Copy.PERCENT_NUMBER)
    }

    /** The draft with [kind] chosen. A kind that cannot aim at both screens moves a `Both
     *  screens` choice back to the focused one: its radio is hidden for that kind, and a
     *  hidden radio must not refuse a save. */
    fun withAction(kind: ActionKind): EditorDraft = copy(
        action = kind,
        screen = if (screen == ScreenTarget.BOTH && !kind.allowsBoth) ScreenTarget.FOCUSED
        else screen,
    )

    /** The scope the draft describes, or a throw when an app list is empty. The model refuses
     *  an empty `Apps` scope with the same words; this check also covers EXCEPT, which builds
     *  a `Global` the model has no rule for. */
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
        /** A new brightness shortcut's percent: a sensible step. */
        const val DEFAULT_PERCENT = "10"

        fun of(s: Shortcut) = EditorDraft(
            original = s,
            id = s.id,
            modifiers = s.trigger.modifiers,
            buttons = s.trigger.buttons,
            timer = s.trigger.style == TriggerStyle.HOLD,
            holdMs = (s.trigger.holdMs ?: 400).toString(),
            action = ActionKind.of(s.action),
            screen = ActionKind.screenOf(s.action) ?: ScreenTarget.FOCUSED,
            openApp = ActionKind.appOf(s.action),
            percent = ActionKind.percentOf(s.action)?.toString() ?: DEFAULT_PERCENT,
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
