package com.grardb.thorcuts.core.config

import com.grardb.thorcuts.core.model.Action
import com.grardb.thorcuts.core.model.Control
import com.grardb.thorcuts.core.model.Device
import com.grardb.thorcuts.core.model.EvdevType
import com.grardb.thorcuts.core.model.ModifierKey
import com.grardb.thorcuts.core.model.Scope
import com.grardb.thorcuts.core.model.TriggerStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ThorcutsConfigTest {

    private fun shortcut(id: String, enabled: Boolean = true) =
        Defaults.SWAP_SCREENS.copy(id = id, enabled = enabled)

    @Test
    fun `repeated ids are refused, naming the repeat`() {
        val e = assertFailsWith<IllegalArgumentException> {
            ThorcutsConfig(listOf(shortcut("a"), shortcut("b"), shortcut("a")))
        }
        assertTrue("a" in e.message!!, e.message)
    }

    @Test
    fun `withEnabled changes one shortcut and nothing else`() {
        val c = ThorcutsConfig(listOf(shortcut("a"), shortcut("b")))
        val off = c.withEnabled("a", false)
        assertEquals(false, off.shortcut("a")!!.enabled)
        assertEquals(true, off.shortcut("b")!!.enabled)
        assertEquals(listOf("a", "b"), off.shortcuts.map { it.id })
        assertEquals(c.unknown, off.unknown)
        assertEquals(c, off.withEnabled("a", true))
    }

    @Test
    fun `withEnabled of an absent id is the same config`() {
        val c = ThorcutsConfig(listOf(shortcut("a")))
        assertEquals(c, c.withEnabled("zzz", false))
        assertNull(c.shortcut("zzz"))
    }

    @Test
    fun `the default is one enabled global HOLD on AYN + X that swaps the screens`() {
        val only = Defaults.CONFIG.shortcuts.single()
        assertEquals("swap-screens", only.id)
        assertTrue(only.enabled)
        assertEquals(Scope.Global(), only.scope)
        assertEquals(Action.SwapScreens, only.action)
        assertEquals(setOf(ModifierKey.AYN), only.trigger.modifiers)
        assertEquals(setOf(Defaults.X), only.trigger.buttons)
        assertEquals(Control(Device.PAD, EvdevType.KEY, 307, 1), Defaults.X)   // BTN_NORTH
        assertEquals(TriggerStyle.HOLD, only.trigger.style)
        assertEquals(400, only.trigger.holdMs)
        assertTrue(Defaults.CONFIG.unknown.isEmpty)
    }
}
