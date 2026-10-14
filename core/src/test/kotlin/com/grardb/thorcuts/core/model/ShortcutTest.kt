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

    /** Binds [t] to an action: Rule 1 is the shortcut's, so a trigger alone never refuses. */
    private fun bound(t: Trigger) = Shortcut("s", t, Action.GoHome(ScreenTarget.TOP))

    @Test
    fun `tap Home alone cannot be a shortcut`() {
        val tap = Trigger(setOf(ModifierKey.HOME))          // the trigger itself is fine
        val e = assertFailsWith<IllegalArgumentException> { bound(tap) }
        assertTrue("HOME" in e.message!! && "go home" in e.message!!, e.message)
    }

    @Test
    fun `tap Back alone cannot be a shortcut`() {
        val tap = Trigger(setOf(ModifierKey.BACK))
        val e = assertFailsWith<IllegalArgumentException> { bound(tap) }
        assertTrue("BACK" in e.message!! && "go back" in e.message!!, e.message)
    }

    @Test
    fun `Home with a button, another modifier or a timer, and bare Start or Select, all bind`() {
        bound(Trigger(setOf(ModifierKey.HOME), setOf(key(304))))
        bound(Trigger(setOf(ModifierKey.HOME, ModifierKey.BACK)))
        bound(Trigger(setOf(ModifierKey.HOME), style = TriggerStyle.HOLD, holdMs = 500))
        bound(Trigger(setOf(ModifierKey.START)))
        bound(Trigger(setOf(ModifierKey.SELECT)))
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
}
