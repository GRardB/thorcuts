package com.grardb.thorcuts.core.config

import com.grardb.thorcuts.core.model.Action
import com.grardb.thorcuts.core.model.Control
import com.grardb.thorcuts.core.model.Device
import com.grardb.thorcuts.core.model.EvdevType
import com.grardb.thorcuts.core.model.ModifierKey
import com.grardb.thorcuts.core.model.Scope
import com.grardb.thorcuts.core.model.ScreenTarget
import com.grardb.thorcuts.core.model.Shortcut
import com.grardb.thorcuts.core.model.Trigger
import com.grardb.thorcuts.core.model.TriggerStyle
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A golden fixture from fixtures/config/, as text. */
internal fun configFixture(name: String): String {
    val loader = Thread.currentThread().contextClassLoader
    val stream = checkNotNull(loader.getResourceAsStream("config/$name")) {
        "fixture config/$name is missing from the test classpath; is ../fixtures a resource dir?"
    }
    return stream.use { it.readBytes().toString(Charsets.UTF_8) }
}

internal const val DOLPHIN = "org.dolphinemu.dolphinemu"
internal const val RETROARCH = "com.retroarch.aarch64"

internal fun padKey(code: Int) = Control(Device.PAD, EvdevType.KEY, code, 1)

/** A PRESS shortcut on AYN + one button, enabled, global: the actions run's shape. */
private fun press(id: String, button: Control, action: Action) =
    Shortcut(id, Trigger(setOf(ModifierKey.AYN), setOf(button)), action)

/**
 * What fixtures/config/actions-run.json means: the actions device run's input. Every
 * trigger is AYN + one button (the swap keeps its HOLD); every action kind, and every
 * screen target that run exercises. The actions spec's table is the source; this is it
 * as code, and the file is this value as the encoder writes it.
 */
internal val ACTIONS_RUN = ThorcutsConfig(
    shortcuts = listOf(
        Defaults.SWAP_SCREENS,
        press("close-focused", padKey(304), Action.CloseScreen(ScreenTarget.FOCUSED)),
        press("close-top", padKey(310), Action.CloseScreen(ScreenTarget.TOP)),
        press("close-bottom", padKey(311), Action.CloseScreen(ScreenTarget.BOTTOM)),
        press("home-focused", padKey(305), Action.GoHome(ScreenTarget.FOCUSED)),
        press("home-bottom", padKey(318), Action.GoHome(ScreenTarget.BOTTOM)),
        press("open-bottom", padKey(308), Action.OpenThorcuts(ScreenTarget.BOTTOM)),
        press("open-top", padKey(317), Action.OpenThorcuts(ScreenTarget.TOP)),
        press(
            "move-focused",
            Control(Device.PAD, EvdevType.ABS, 17, -1),   // D-pad up
            Action.MoveFocusedToOtherScreen,
        ),
    ),
)

/**
 * What fixtures/config/kitchen-sink.json means. Every action kind and screen target, both
 * scopes, a bare modifier, four buttons, an ABS control at −1, two modifiers, a disabled
 * shortcut, and an unknown member at each preserved level.
 */
internal val KITCHEN_SINK = ThorcutsConfig(
    shortcuts = listOf(
        Shortcut(
            id = "close-in-emulators",
            trigger = Trigger(
                modifiers = setOf(ModifierKey.START, ModifierKey.SELECT),
                buttons = setOf(
                    Control(Device.PAD, EvdevType.ABS, 16, -1),
                    Control(Device.GPIO, EvdevType.KEY, 115, 1),
                ),
            ),
            action = Action.CloseScreen(ScreenTarget.FOCUSED),
            scope = Scope.Apps(setOf(DOLPHIN, RETROARCH)),
            enabled = false,
        ),
        Shortcut(
            id = "home-hold",
            trigger = Trigger(setOf(ModifierKey.HOME), style = TriggerStyle.HOLD, holdMs = 500),
            action = Action.GoHome(ScreenTarget.TOP),
        ),
        Shortcut(
            id = "four-buttons",
            trigger = Trigger(
                setOf(ModifierKey.AYN),
                setOf(padKey(304), padKey(305), padKey(310), padKey(311)),
            ),
            action = Action.MoveFocusedToOtherScreen,
            scope = Scope.Global(excluded = setOf(RETROARCH)),
        ),
        Shortcut(
            id = "open-bottom",
            trigger = Trigger(setOf(ModifierKey.SELECT)),
            action = Action.OpenThorcuts(ScreenTarget.BOTTOM),
        ),
        Shortcut(
            id = "swap",
            trigger = Trigger(
                setOf(ModifierKey.AYN), setOf(padKey(307)), TriggerStyle.HOLD, holdMs = 400,
            ),
            action = Action.SwapScreens,
        ),
    ),
    unknown = UnknownFields(
        top = mapOf("theme" to buildJsonObject { put("dark", true) }),
        perShortcut = mapOf(
            "close-in-emulators" to mapOf("note" to JsonPrimitive("an unknown shortcut member")),
        ),
    ),
)
