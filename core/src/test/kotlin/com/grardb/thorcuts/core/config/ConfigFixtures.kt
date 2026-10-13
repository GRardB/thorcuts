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
            trigger = Trigger(setOf(ModifierKey.BACK)),
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
