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

// Shared test material (java-test-fixtures): on the test classpath of :core, :platform and
// :app. Public, since a fixtures source set is its own module to the compiler. The golden
// files under fixtures/config/ are read by :core's own tests (`configFixture`); these are the
// values those files mean.

const val DOLPHIN = "org.dolphinemu.dolphinemu"
const val RETROARCH = "com.retroarch.aarch64"

/** Thorcuts' own package: what the fixtures' open shortcuts open. */
const val THORCUTS = "com.grardb.thorcuts"

fun padKey(code: Int) = Control(Device.PAD, EvdevType.KEY, code, 1)

/** BTN_NORTH — the X button on the Thor's pad (M1 findings, control table). */
val BUTTON_X = padKey(307)

/**
 * M1's rule — HOLD 400 · AYN + X → swap the screens — the shipped default until M5 emptied
 * it. Test material now: the actions run, the merge, store, config and coordinator tests
 * derive their shortcuts from it, and `RuleCompilerTest` pins `m1-swap.bin` with it.
 */
val SWAP_SCREENS = Shortcut(
    id = "swap-screens",
    trigger = Trigger(
        modifiers = setOf(ModifierKey.AYN),
        buttons = setOf(BUTTON_X),
        style = TriggerStyle.HOLD,
        holdMs = 400,
    ),
    action = Action.SwapScreens,
)

/** A config with content — [SWAP_SCREENS] alone — for any test that needs one: the store's
 *  default, the codec's identity loop, the coordinator's seeded file. */
val ONE_SHORTCUT = ThorcutsConfig(listOf(SWAP_SCREENS))

/** [ONE_SHORTCUT] as the encoder writes it, as a literal: `ConfigCodecTest` pins the tie, and
 *  the store tests compare a first-run write against this text rather than against the
 *  encoder under test. */
val ONE_SHORTCUT_TEXT: String = """
{
  "schemaVersion": 1,
  "shortcuts": [
    {
      "id": "swap-screens",
      "enabled": true,
      "trigger": {
        "modifiers": [
          "AYN"
        ],
        "buttons": [
          {
            "device": "PAD",
            "type": "KEY",
            "code": 307,
            "value": 1
          }
        ],
        "style": "HOLD",
        "holdMs": 400
      },
      "action": {
        "kind": "SwapScreens"
      },
      "scope": {
        "kind": "Global",
        "excluded": []
      }
    }
  ]
}
""".removePrefix("\n")

/** A PRESS shortcut on AYN + one button, enabled, global: the actions run's shape. */
private fun press(id: String, button: Control, action: Action) =
    Shortcut(id, Trigger(setOf(ModifierKey.AYN), setOf(button)), action)

/**
 * What fixtures/config/actions-run.json means: the actions device run's input. Every
 * trigger is AYN + one button (the swap keeps its HOLD); every action kind, and every
 * screen target that run exercises. The actions spec's table is the source; this is it
 * as code, and the file is this value as the encoder writes it.
 */
val ACTIONS_RUN = ThorcutsConfig(
    shortcuts = listOf(
        SWAP_SCREENS,
        press("close-focused", padKey(304), Action.CloseScreen(ScreenTarget.FOCUSED)),
        press("close-top", padKey(310), Action.CloseScreen(ScreenTarget.TOP)),
        press("close-bottom", padKey(311), Action.CloseScreen(ScreenTarget.BOTTOM)),
        press("home-focused", padKey(305), Action.GoHome(ScreenTarget.FOCUSED)),
        press("home-bottom", padKey(318), Action.GoHome(ScreenTarget.BOTTOM)),
        press("open-bottom", padKey(308), Action.OpenApp(THORCUTS, ScreenTarget.BOTTOM)),
        press("open-top", padKey(317), Action.OpenApp(THORCUTS, ScreenTarget.TOP)),
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
val KITCHEN_SINK = ThorcutsConfig(
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
            action = Action.OpenApp(THORCUTS, ScreenTarget.BOTTOM),
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
