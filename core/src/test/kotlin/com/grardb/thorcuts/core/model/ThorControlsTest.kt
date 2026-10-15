package com.grardb.thorcuts.core.model

import kotlin.test.Test
import kotlin.test.assertEquals

class ThorControlsTest {

    private fun padKey(code: Int) = Control(Device.PAD, EvdevType.KEY, code, 1)
    private fun hat(code: Int, value: Int) = Control(Device.PAD, EvdevType.ABS, code, value)

    @Test
    fun `the pad's keys and the D-pad by their measured names`() {
        val keys = mapOf(
            304 to "A", 305 to "B", 307 to "X", 308 to "Y", 310 to "L1", 311 to "R1",
            312 to "L2", 313 to "R2", 317 to "L3", 318 to "R3",
        )
        for ((code, name) in keys) assertEquals(name, ThorControls.name(padKey(code)))
        assertEquals("D-pad left", ThorControls.name(hat(ThorControls.HAT0X, -1)))
        assertEquals("D-pad right", ThorControls.name(hat(ThorControls.HAT0X, 1)))
        assertEquals("D-pad up", ThorControls.name(hat(ThorControls.HAT0Y, -1)))
        assertEquals("D-pad down", ThorControls.name(hat(ThorControls.HAT0Y, 1)))
        assertEquals("Volume +", ThorControls.name(Control(Device.GPIO, EvdevType.KEY, 115, 1)))
    }

    @Test
    fun `the modifiers by name, as a modifier and as the control it is`() {
        assertEquals("AYN", ThorControls.name(ModifierKey.AYN))
        assertEquals("Home", ThorControls.name(ModifierKey.HOME))
        assertEquals("Back", ThorControls.name(ModifierKey.BACK))
        assertEquals("Start", ThorControls.name(ModifierKey.START))
        assertEquals("Select", ThorControls.name(ModifierKey.SELECT))
        assertEquals("AYN", ThorControls.name(ModifierKey.AYN.control))
        assertEquals("Select", ThorControls.name(padKey(314)))
    }

    @Test
    fun `a control the table does not know still has a name`() {
        assertEquals("key 999", ThorControls.name(padKey(999)))
        assertEquals("key 7", ThorControls.name(Control(Device.GPIO, EvdevType.KEY, 7, 1)))
        assertEquals("axis 9 at 16384", ThorControls.name(hat(9, 16384)))
    }

    @Test
    fun `describe lists modifiers in the fixed order, buttons in code order, then the timer`() {
        val swap = Trigger(setOf(ModifierKey.AYN), setOf(padKey(307)), TriggerStyle.HOLD, 400)
        assertEquals("AYN + X · timer 400 ms", ThorControls.describe(swap))
        val both = setOf(ModifierKey.SELECT, ModifierKey.START)
        val combo = Trigger(both, setOf(padKey(311), padKey(304)))
        assertEquals("Start + Select + A + R1", ThorControls.describe(combo))
        val dpad = Trigger(setOf(ModifierKey.HOME), setOf(hat(ThorControls.HAT0X, -1)))
        assertEquals("Home + D-pad left", ThorControls.describe(dpad))
        assertEquals("AYN", ThorControls.describe(Trigger(setOf(ModifierKey.AYN))))
    }
}
