package com.grardb.thorcuts.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ControlTest {

    @Test
    fun `a key control is bound on its press`() {
        Control(Device.PAD, EvdevType.KEY, 304, 1)
        assertFailsWith<IllegalArgumentException> { Control(Device.PAD, EvdevType.KEY, 304, 0) }
        assertFailsWith<IllegalArgumentException> { Control(Device.PAD, EvdevType.KEY, 304, 2) }
    }

    @Test
    fun `an axis control may carry a negative value`() {
        // ABS_HAT0X at -1 is as bindable as BTN_SOUTH at 1 (parent spec §6).
        val left = Control(Device.PAD, EvdevType.ABS, 16, -1)
        assertEquals(-1, left.value)
    }

    @Test
    fun `an axis control needs a non-zero threshold`() {
        // A threshold of 0 would be "always pressed"; the parser rejects it as `value` too.
        assertFailsWith<IllegalArgumentException> { Control(Device.PAD, EvdevType.ABS, 16, 0) }
        Control(Device.PAD, EvdevType.ABS, 16, 1)
        Control(Device.PAD, EvdevType.ABS, 9, 16384)
    }

    @Test
    fun `only EV_KEY and EV_ABS are controls`() {
        assertFailsWith<IllegalArgumentException> { Control(Device.PAD, 2, 0, 1) }   // EV_REL
        assertFailsWith<IllegalArgumentException> { Control(Device.PAD, 0, 0, 1) }   // EV_SYN
    }

    @Test
    fun `code and value must fit the wire`() {
        assertFailsWith<IllegalArgumentException> {
            Control(Device.PAD, EvdevType.ABS, 0x10000, 0)
        }
        assertFailsWith<IllegalArgumentException> { Control(Device.PAD, EvdevType.ABS, -1, 0) }
        assertFailsWith<IllegalArgumentException> { Control(Device.PAD, EvdevType.ABS, 16, 32768) }
        assertFailsWith<IllegalArgumentException> {
            Control(Device.PAD, EvdevType.ABS, 16, -32769)
        }
        Control(Device.PAD, EvdevType.ABS, 0xFFFF, 32767)
        Control(Device.PAD, EvdevType.ABS, 0, -32768)
    }

    @Test
    fun `the five modifiers carry the measured codes and bits`() {
        // Codes are from the M1 device recon's control table; bits are the wire contract
        // and must match TC_MOD_* in native/src/ruletable.h.
        assertEquals(Control(Device.GPIO, EvdevType.KEY, 194, 1), ModifierKey.AYN.control)
        assertEquals(Control(Device.PAD, EvdevType.KEY, 102, 1), ModifierKey.HOME.control)
        assertEquals(Control(Device.PAD, EvdevType.KEY, 158, 1), ModifierKey.BACK.control)
        assertEquals(Control(Device.PAD, EvdevType.KEY, 315, 1), ModifierKey.START.control)
        assertEquals(Control(Device.PAD, EvdevType.KEY, 314, 1), ModifierKey.SELECT.control)
        assertEquals(listOf(0, 1, 2, 3, 4), ModifierKey.entries.map { it.bit })
    }

    @Test
    fun `isModifier recognises exactly the five`() {
        for (m in ModifierKey.entries) assertTrue(ModifierKey.isModifier(m.control), m.name)
        assertFalse(ModifierKey.isModifier(Control(Device.PAD, EvdevType.KEY, 307, 1)))   // X
        val homeCodeOnGpio = Control(Device.GPIO, EvdevType.KEY, 102, 1)
        assertFalse(ModifierKey.isModifier(homeCodeOnGpio))   // same code, wrong device
    }
}
