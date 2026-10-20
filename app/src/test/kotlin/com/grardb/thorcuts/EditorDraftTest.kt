package com.grardb.thorcuts

import com.grardb.thorcuts.core.model.Action
import com.grardb.thorcuts.core.model.BrightnessMode
import com.grardb.thorcuts.core.model.Control
import com.grardb.thorcuts.core.model.Device
import com.grardb.thorcuts.core.model.EvdevType
import com.grardb.thorcuts.core.model.ModifierKey
import com.grardb.thorcuts.core.model.Scope
import com.grardb.thorcuts.core.model.ScreenTarget
import com.grardb.thorcuts.core.model.Shortcut
import com.grardb.thorcuts.core.model.Trigger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class EditorDraftTest {

    private val x = Control(Device.PAD, EvdevType.KEY, 307, 1)

    private fun base(scope: ScopeKind = ScopeKind.EVERYWHERE, apps: Set<String> = emptySet()) =
        EditorDraft(
            id = "s",
            modifiers = setOf(ModifierKey.AYN),
            buttons = setOf(x),
            scope = scope,
            apps = apps,
        )

    @Test
    fun `everywhere round-trips to a plain global`() {
        val s = base().toShortcut()
        assertEquals(Scope.Global(), s.scope)
        assertEquals(ScopeKind.EVERYWHERE, EditorDraft.of(s).scope)
    }

    @Test
    fun `except round-trips to a global with those exclusions`() {
        val s = base(ScopeKind.EXCEPT, setOf("com.b", "com.a")).toShortcut()
        assertEquals(Scope.Global(setOf("com.a", "com.b")), s.scope)
        val d = EditorDraft.of(s)
        assertEquals(ScopeKind.EXCEPT, d.scope)
        assertEquals(setOf("com.a", "com.b"), d.apps)
    }

    @Test
    fun `only-in round-trips to an app list`() {
        val s = base(ScopeKind.ONLY, setOf("com.a")).toShortcut()
        assertEquals(Scope.Apps(setOf("com.a")), s.scope)
        val d = EditorDraft.of(s)
        assertEquals(ScopeKind.ONLY, d.scope)
        assertEquals(setOf("com.a"), d.apps)
    }

    @Test
    fun `except or only with no app chosen is refused`() {
        for (kind in listOf(ScopeKind.EXCEPT, ScopeKind.ONLY)) {
            val e = assertFailsWith<IllegalArgumentException> { base(kind).toShortcut() }
            assertEquals("Choose at least one app", e.message)
        }
    }

    @Test
    fun `the enabled flag is kept and the id trimmed`() {
        val original = Shortcut(
            "keep", Trigger(setOf(ModifierKey.AYN), setOf(x)), Action.SwapScreens, enabled = false,
        )
        val s = EditorDraft.of(original).copy(id = "  keep  ").toShortcut()
        assertEquals("keep", s.id)
        assertFalse(s.enabled)
    }

    @Test
    fun `a non-numeric timer throws its message`() {
        val e = assertFailsWith<IllegalArgumentException> {
            base().copy(timer = true, holdMs = "x").toShortcut()
        }
        assertEquals("The timer must be a number of milliseconds", e.message)
    }

    @Test
    fun `the screen choices read in sentence case, the focused one explained`() {
        assertEquals("Top screen", ScreenTarget.TOP.choiceLabel())
        assertEquals("Bottom screen", ScreenTarget.BOTTOM.choiceLabel())
        assertEquals(
            "Focused screen — the one you last touched or that last took input",
            ScreenTarget.FOCUSED.choiceLabel(),
        )
        assertEquals("the focused screen", ScreenTarget.FOCUSED.label())   // mid-sentence
    }

    @Test
    fun `the model's own messages pass through`() {
        val noMod = assertFailsWith<IllegalArgumentException> {
            base().copy(modifiers = emptySet()).toShortcut()
        }
        assertEquals("A trigger must start with a modifier", noMod.message)
        val bareHome = base().copy(modifiers = setOf(ModifierKey.HOME), buttons = emptySet())
        val home = assertFailsWith<IllegalArgumentException> { bareHome.toShortcut() }
        assertEquals("Home alone cannot be a shortcut: it would no longer go home", home.message)
    }

    @Test
    fun `the scope line reads for the three shapes`() {
        assertNull(Scope.Global().line())
        assertEquals(
            "Everywhere except: com.a, com.b",
            Scope.Global(setOf("com.b", "com.a")).line(),
        )
        assertEquals("Applies only in: com.a", Scope.Apps(setOf("com.a")).line())
    }

    @Test
    fun `the scope line names the apps it knows and keeps the id of one it does not`() {
        val labels = mapOf("com.b" to "Bitwarden", "com.a" to "Zelda")
        assertEquals(
            "Everywhere except: Bitwarden, Zelda",
            Scope.Global(setOf("com.b", "com.a")).line(labels),
        )
        assertEquals(
            "Applies only in: Bitwarden, com.zzz",
            Scope.Apps(setOf("com.zzz", "com.b")).line(labels),
        )
    }

    @Test
    fun `an action that aims at a screen trails an ellipsis in the editor, not in the list`() {
        assertEquals("Close the app on…", ActionKind.CLOSE.choiceLabel)
        assertEquals("Go home on…", ActionKind.HOME.choiceLabel)
        assertEquals("Open an app…", ActionKind.OPEN.choiceLabel)
        assertEquals("Swap the screens", ActionKind.SWAP.choiceLabel)
        assertEquals(
            "Close the app on the top screen",
            Action.CloseScreen(ScreenTarget.TOP).label(),
        )
    }

    @Test
    fun `an open-app action round-trips with its package and screen`() {
        val d = base().copy(
            action = ActionKind.OPEN, screen = ScreenTarget.BOTTOM, openApp = "com.a",
        )
        val s = d.toShortcut()
        assertEquals(Action.OpenApp("com.a", ScreenTarget.BOTTOM), s.action)
        val back = EditorDraft.of(s)
        assertEquals(ActionKind.OPEN, back.action)
        assertEquals(ScreenTarget.BOTTOM, back.screen)
        assertEquals("com.a", back.openApp)
        assertNull(EditorDraft.of(base().toShortcut()).openApp)      // the swap names none
    }

    @Test
    fun `an open-app action with no app chosen is refused`() {
        val e = assertFailsWith<IllegalArgumentException> {
            base().copy(action = ActionKind.OPEN).toShortcut()
        }
        assertEquals("Choose an app", e.message)
    }

    @Test
    fun `a screenshot action round-trips with its screen, names no app, and reads in both places`() {
        val s = base().copy(action = ActionKind.SHOT, screen = ScreenTarget.BOTTOM).toShortcut()
        assertEquals(Action.TakeScreenshot(ScreenTarget.BOTTOM), s.action)
        val back = EditorDraft.of(s)
        assertEquals(ActionKind.SHOT, back.action)
        assertEquals(ScreenTarget.BOTTOM, back.screen)
        assertNull(back.openApp)
        assertEquals("Take a screenshot of…", ActionKind.SHOT.choiceLabel)
        assertEquals("Take a screenshot of the bottom screen", s.action.label())
        assertEquals(6, ActionKind.entries.indexOf(ActionKind.SHOT))   // seventh in the editor
    }

    @Test
    fun `the list names the app an action opens, by label when it knows one`() {
        val open = Action.OpenApp("com.a", ScreenTarget.TOP)
        assertEquals("Open Zelda on the top screen", open.label(mapOf("com.a" to "Zelda")))
        assertEquals("Open com.a on the top screen", open.label())
        assertEquals("Go home on the bottom screen", Action.GoHome(ScreenTarget.BOTTOM).label())
    }

    @Test
    fun `both screens is named in the list and the editor, and only the brightness kinds and close-all offer it`() {
        assertEquals("both screens", ScreenTarget.BOTH.label())
        assertEquals("Both screens", ScreenTarget.BOTH.choiceLabel())
        val bright = setOf(ActionKind.BRIGHTER, ActionKind.DIMMER, ActionKind.BRIGHTNESS)
        val both = bright + ActionKind.CLOSE_ALL
        for (kind in ActionKind.entries) {
            assertEquals(kind in bright, kind.takesPercent, kind.name)
            assertEquals(kind in both, kind.allowsBoth, kind.name)
            assertEquals(kind in both, ScreenTarget.BOTH in kind.screens, kind.name)
            assertEquals(
                listOf(ScreenTarget.TOP, ScreenTarget.BOTTOM, ScreenTarget.FOCUSED),
                kind.screens.take(3),
                kind.name,
            )
        }
        assertEquals(
            listOf(ActionKind.BRIGHTER, ActionKind.DIMMER, ActionKind.BRIGHTNESS),
            ActionKind.entries.takeLast(3),
        )
        assertEquals("Increase brightness by…", ActionKind.BRIGHTER.choiceLabel)
        assertEquals("Decrease brightness by…", ActionKind.DIMMER.choiceLabel)
        assertEquals("Set brightness to…", ActionKind.BRIGHTNESS.choiceLabel)
    }

    @Test
    fun `a close-all action round-trips with its screen, both included, names no app, and sits after the single close`() {
        for (screen in ScreenTarget.entries) {
            val s = base().copy(action = ActionKind.CLOSE_ALL, screen = screen).toShortcut()
            assertEquals(Action.CloseAllApps(screen), s.action)
            val back = EditorDraft.of(s)
            assertEquals(ActionKind.CLOSE_ALL, back.action)
            assertEquals(screen, back.screen)
            assertNull(back.openApp)
            assertEquals("10", back.percent)
        }
        assertEquals("Close all apps on…", ActionKind.CLOSE_ALL.choiceLabel)
        assertEquals(
            "Close all apps on both screens",
            Action.CloseAllApps(ScreenTarget.BOTH).label(),
        )
        assertEquals(
            "Close all apps on the focused screen",
            Action.CloseAllApps(ScreenTarget.FOCUSED).label(),
        )
        assertEquals(
            ActionKind.entries.indexOf(ActionKind.CLOSE) + 1,
            ActionKind.entries.indexOf(ActionKind.CLOSE_ALL),
        )
    }

    @Test
    fun `a brightness action round-trips with its mode, percent and screen, and names no app`() {
        val cases = mapOf(
            ActionKind.BRIGHTER to BrightnessMode.INCREASE,
            ActionKind.DIMMER to BrightnessMode.DECREASE,
            ActionKind.BRIGHTNESS to BrightnessMode.SET,
        )
        for ((kind, mode) in cases) {
            val s = base().copy(action = kind, screen = ScreenTarget.BOTH, percent = " 25 ")
                .toShortcut()
            assertEquals(Action.Brightness(mode, 25, ScreenTarget.BOTH), s.action)
            val back = EditorDraft.of(s)
            assertEquals(kind, back.action)
            assertEquals(ScreenTarget.BOTH, back.screen)
            assertEquals("25", back.percent)
            assertNull(back.openApp)
        }
        assertEquals("10", EditorDraft.of(base().toShortcut()).percent)   // the swap keeps the default
    }

    @Test
    fun `a percent that is not a whole number is refused, and the model's ranges come through`() {
        val e = assertFailsWith<IllegalArgumentException> {
            base().copy(action = ActionKind.BRIGHTNESS, percent = "half").toShortcut()
        }
        assertEquals("The percent must be a whole number", e.message)
        val big = assertFailsWith<IllegalArgumentException> {
            base().copy(action = ActionKind.BRIGHTER, percent = "150").toShortcut()
        }
        assertEquals("The step must be between 1 and 100 (got 150)", big.message)
        // A kind that takes no percent ignores whatever the field holds.
        assertEquals(Action.SwapScreens, base().copy(percent = "half").toShortcut().action)
    }

    @Test
    fun `the list reads a brightness action with its percent and screen`() {
        assertEquals(
            "Increase brightness by 10% on both screens",
            Action.Brightness(BrightnessMode.INCREASE, 10, ScreenTarget.BOTH).label(),
        )
        assertEquals(
            "Set brightness to 50% on the top screen",
            Action.Brightness(BrightnessMode.SET, 50, ScreenTarget.TOP).label(),
        )
        assertEquals(
            "Decrease brightness by 20% on the focused screen",
            Action.Brightness(BrightnessMode.DECREASE, 20, ScreenTarget.FOCUSED).label(),
        )
    }

    @Test
    fun `choosing a kind that cannot aim at both screens moves the choice back to the focused one`() {
        val both = base().copy(action = ActionKind.BRIGHTER, screen = ScreenTarget.BOTH)
        assertEquals(ScreenTarget.BOTH, both.withAction(ActionKind.DIMMER).screen)
        assertEquals(ScreenTarget.FOCUSED, both.withAction(ActionKind.CLOSE).screen)
        assertEquals(ActionKind.CLOSE, both.withAction(ActionKind.CLOSE).action)
        val top = base().copy(action = ActionKind.BRIGHTER, screen = ScreenTarget.TOP)
        assertEquals(ScreenTarget.TOP, top.withAction(ActionKind.HOME).screen)
        val all = base().copy(action = ActionKind.CLOSE_ALL, screen = ScreenTarget.BOTH)
        assertEquals(ScreenTarget.BOTH, all.withAction(ActionKind.BRIGHTER).screen)
        assertEquals(ScreenTarget.FOCUSED, all.withAction(ActionKind.CLOSE).screen)
        assertEquals(ScreenTarget.BOTH, both.withAction(ActionKind.CLOSE_ALL).screen)
        assertEquals("stale", base().copy(error = "stale").withAction(ActionKind.CLOSE).error)
    }

    @Test
    fun `trigger text describes a recorded combo, or says there is none`() {
        assertEquals("AYN + X", base().triggerText)
        assertEquals(
            Copy.NOT_RECORDED,
            base().copy(modifiers = emptySet(), buttons = emptySet()).triggerText,
        )
    }
}
