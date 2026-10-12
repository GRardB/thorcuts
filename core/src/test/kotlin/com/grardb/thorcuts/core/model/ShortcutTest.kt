package com.grardb.thorcuts.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ShortcutTest {

    private fun key(code: Int) = Control(Device.PAD, EvdevType.KEY, code, 1)

    @Test
    fun `a trigger needs at least one modifier`() {
        assertFailsWith<IllegalArgumentException> { Trigger(emptySet(), setOf(key(304))) }
    }

    @Test
    fun `a bare modifier is a valid trigger`() {
        val t = Trigger(setOf(ModifierKey.AYN))
        assertTrue(t.buttons.isEmpty())
        assertEquals(TriggerStyle.PRESS, t.style)
    }

    @Test
    fun `at most four buttons`() {
        Trigger(setOf(ModifierKey.AYN), setOf(key(304), key(305), key(307), key(308)))
        assertFailsWith<IllegalArgumentException> {
            val buttons = setOf(key(304), key(305), key(307), key(308), key(310))
            Trigger(setOf(ModifierKey.AYN), buttons)
        }
    }

    @Test
    fun `a modifier cannot also be a button`() {
        assertFailsWith<IllegalArgumentException> {
            Trigger(setOf(ModifierKey.AYN), setOf(ModifierKey.HOME.control))
        }
    }

    @Test
    fun `PRESS carries no hold duration`() {
        assertFailsWith<IllegalArgumentException> {
            Trigger(setOf(ModifierKey.AYN), setOf(key(304)), TriggerStyle.PRESS, holdMs = 400)
        }
    }

    @Test
    fun `HOLD needs a duration the wire can carry`() {
        assertFailsWith<IllegalArgumentException> {
            Trigger(setOf(ModifierKey.AYN), style = TriggerStyle.HOLD)
        }
        assertFailsWith<IllegalArgumentException> {
            Trigger(setOf(ModifierKey.AYN), style = TriggerStyle.HOLD, holdMs = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            Trigger(setOf(ModifierKey.AYN), style = TriggerStyle.HOLD, holdMs = 65536)
        }
        Trigger(setOf(ModifierKey.AYN), style = TriggerStyle.HOLD, holdMs = 1)
        Trigger(setOf(ModifierKey.AYN), style = TriggerStyle.HOLD, holdMs = 65535)
    }

    @Test
    fun `an Apps scope names at least one package`() {
        assertFailsWith<IllegalArgumentException> { Scope.Apps(emptySet()) }
        Scope.Apps(setOf("org.dolphinemu.dolphinemu"))
        Scope.Global()
    }

    @Test
    fun `a shortcut needs an id`() {
        val t = Trigger(setOf(ModifierKey.AYN), setOf(key(304)))
        assertFailsWith<IllegalArgumentException> { Shortcut("", t, Action.SwapScreens) }
        assertFailsWith<IllegalArgumentException> { Shortcut("   ", t, Action.SwapScreens) }
        val s = Shortcut("ok", t, Action.SwapScreens)
        assertEquals(Scope.Global(), s.scope)
        assertTrue(s.enabled)
    }

    @Test
    fun `an action can aim at the focused screen, resolved when it runs`() {
        val close = Action.CloseScreen(ScreenTarget.FOCUSED)
        assertEquals(ScreenTarget.FOCUSED, close.screen)
        assertEquals(
            setOf(ScreenTarget.TOP, ScreenTarget.BOTTOM, ScreenTarget.FOCUSED),
            ScreenTarget.entries.toSet(),
        )
    }

    @Test
    fun `the bootstrap is M1's rule and nothing else`() {
        assertEquals(1, Bootstrap.SHORTCUTS.size)
        val s = Bootstrap.SHORTCUTS[0]
        assertEquals(
            Trigger(setOf(ModifierKey.AYN), setOf(Bootstrap.X), TriggerStyle.HOLD, holdMs = 400),
            s.trigger,
        )
        assertEquals(Control(Device.PAD, EvdevType.KEY, 307, 1), Bootstrap.X)   // BTN_NORTH
        assertEquals(Action.SwapScreens, s.action)
        assertEquals(Scope.Global(), s.scope)
        assertTrue(s.enabled)
    }
}
