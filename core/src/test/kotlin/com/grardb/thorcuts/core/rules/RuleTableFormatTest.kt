package com.grardb.thorcuts.core.rules

import com.grardb.thorcuts.core.model.Control
import com.grardb.thorcuts.core.model.Device
import com.grardb.thorcuts.core.model.EvdevType
import com.grardb.thorcuts.core.model.ModifierKey
import com.grardb.thorcuts.core.model.Trigger
import com.grardb.thorcuts.core.model.TriggerStyle
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RuleTableFormatTest {

    private fun key(code: Int, device: Device = Device.PAD) =
        Control(device, EvdevType.KEY, code, 1)
    private fun abs(code: Int, value: Int) = Control(Device.PAD, EvdevType.ABS, code, value)

    @Test
    fun `an empty table is a header alone`() {
        val bytes = RuleTableFormat.encode(emptyList(), generation = 7)
        assertContentEquals(fixture("empty.bin"), bytes)
    }

    @Test
    fun `the M1 rule encodes to the m1-swap fixture`() {
        val t = Trigger(setOf(ModifierKey.AYN), setOf(key(307)), TriggerStyle.HOLD, holdMs = 400)
        assertContentEquals(fixture("m1-swap.bin"), RuleTableFormat.encode(listOf(t), 1))
    }

    @Test
    fun `the kitchen sink encodes to its fixture`() {
        val rules = listOf(
            Trigger(setOf(ModifierKey.SELECT, ModifierKey.START), setOf(abs(16, -1), abs(16, 1))),
            Trigger(setOf(ModifierKey.HOME), style = TriggerStyle.HOLD, holdMs = 500),
            Trigger(
                setOf(ModifierKey.AYN),
                setOf(key(304), key(310), key(311), key(115, Device.GPIO)),
            ),
        )
        assertContentEquals(
            fixture("kitchen-sink.bin"),
            RuleTableFormat.encode(rules, generation = Int.MAX_VALUE),
        )
    }

    @Test
    fun `sixty-four rules encode to the max-rules fixture`() {
        val rules = (0 until 64).map { Trigger(setOf(ModifierKey.AYN), setOf(key(512 + it))) }
        assertContentEquals(fixture("max-rules.bin"), RuleTableFormat.encode(rules, generation = 2))
    }

    @Test
    fun `every modifier bit encodes to the modifiers fixture, in bit order`() {
        // One bare-modifier rule per ModifierKey, in entries order. Swapping any two bits on
        // the Kotlin side changes these bytes; the C test pins the same file the other way.
        // Pinned BY NAME as well: reordering the declarations and renumbering `bit` to
        // match would keep the bytes identical and still disagree with TC_MOD_* in C.
        assertEquals(
            listOf(ModifierKey.AYN, ModifierKey.HOME, ModifierKey.BACK, ModifierKey.START,
                ModifierKey.SELECT),
            ModifierKey.entries.toList(),
        )
        val rules = ModifierKey.entries.map { Trigger(setOf(it)) }
        assertContentEquals(
            fixture("modifiers.bin"),
            RuleTableFormat.encode(rules, generation = 3),
        )
    }

    @Test
    fun `button order in the set does not change the bytes`() {
        val volumeUp = key(115, Device.GPIO)
        val a = Trigger(setOf(ModifierKey.AYN), linkedSetOf(key(311), key(304), volumeUp, key(310)))
        val b = Trigger(setOf(ModifierKey.AYN), linkedSetOf(volumeUp, key(310), key(311), key(304)))
        assertContentEquals(
            RuleTableFormat.encode(listOf(a), 1),
            RuleTableFormat.encode(listOf(b), 1),
        )
    }

    @Test
    fun `a negative value is a signed little-endian i16`() {
        val left = Trigger(setOf(ModifierKey.AYN), setOf(abs(16, -1)))
        val bytes = RuleTableFormat.encode(listOf(left), 0)
        // header 12 + rule offset 8 = button 0; its value is bytes 4-5 of the 6-byte control.
        assertEquals(0xFF.toByte(), bytes[12 + 8 + 4])
        assertEquals(0xFF.toByte(), bytes[12 + 8 + 5])
    }

    @Test
    fun `sixty-five rules are refused`() {
        val rules = (0 until 65).map { Trigger(setOf(ModifierKey.AYN), setOf(key(512 + it))) }
        assertFailsWith<IllegalArgumentException> { RuleTableFormat.encode(rules, 1) }
    }

    @Test
    fun `a negative generation is refused`() {
        assertFailsWith<IllegalArgumentException> { RuleTableFormat.encode(emptyList(), -1) }
    }

    @Test
    fun `hex is lowercase, two characters per byte`() {
        assertEquals("544852430100000007000000", RuleTableFormat.hex(fixture("empty.bin")))
        assertEquals("00ff7f80", RuleTableFormat.hex(byteArrayOf(0, -1, 127, -128)))
    }

    @Test
    fun `the limits are what the spec says`() {
        assertEquals(12, RuleTableFormat.HEADER_BYTES)
        assertEquals(32, RuleTableFormat.RULE_BYTES)
        assertEquals(64, RuleTableFormat.MAX_RULES)
        assertEquals(2060, RuleTableFormat.MAX_BYTES)
        assertEquals(4133, RuleTableFormat.MAX_LINE_CHARS)
    }
}
