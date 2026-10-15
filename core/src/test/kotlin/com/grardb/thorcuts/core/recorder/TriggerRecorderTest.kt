package com.grardb.thorcuts.core.recorder

import com.grardb.thorcuts.core.helper.HelperMessage
import com.grardb.thorcuts.core.model.Control
import com.grardb.thorcuts.core.model.Device
import com.grardb.thorcuts.core.model.EvdevType
import com.grardb.thorcuts.core.model.ModifierKey
import com.grardb.thorcuts.core.model.ThorControls
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TriggerRecorderTest {

    private fun key(code: Int, value: Int, device: Device = Device.PAD) =
        HelperMessage.Event(device.slot, EvdevType.KEY, code, value)
    private fun hat(code: Int, value: Int) =
        HelperMessage.Event(Device.PAD.slot, EvdevType.ABS, code, value)
    private fun feed(vararg events: HelperMessage.Event) =
        events.fold(TriggerRecorder()) { r, e -> r.feed(e) }

    private val x = Control(Device.PAD, EvdevType.KEY, 307, 1)
    private val ayn = ModifierKey.AYN.control
    private val aynDown = key(194, 1, Device.GPIO)
    private val aynUp = key(194, 0, Device.GPIO)
    private val left = Control(Device.PAD, EvdevType.ABS, ThorControls.HAT0X, -1)
    private val right = Control(Device.PAD, EvdevType.ABS, ThorControls.HAT0X, 1)

    @Test
    fun `nothing pressed is not complete`() {
        val r = TriggerRecorder()
        assertFalse(r.complete)
        assertTrue(r.held.isEmpty() && r.seen.isEmpty())
    }

    @Test
    fun `a key down then up completes with that key as a button`() {
        val r = feed(key(307, 1), key(307, 0))
        assertTrue(r.complete)
        assertEquals(setOf(x), r.seen)
        assertEquals(setOf(x), r.buttons)
        assertTrue(r.modifiers.isEmpty())
    }

    @Test
    fun `a modifier and a button, released in either order, split into the two sets`() {
        for (r in listOf(
            feed(aynDown, key(307, 1), key(307, 0), aynUp),
            feed(aynDown, key(307, 1), aynUp, key(307, 0)),
        )) {
            assertTrue(r.complete)
            assertEquals(setOf(ModifierKey.AYN), r.modifiers)
            assertEquals(setOf(x), r.buttons)
        }
    }

    @Test
    fun `the modifiers come out in the fixed order whatever the press order`() {
        val r = feed(key(314, 1), aynDown, key(314, 0), aynUp)                 // Select, AYN
        assertEquals(listOf(ModifierKey.AYN, ModifierKey.SELECT), r.modifiers.toList())
    }

    @Test
    fun `while something is held the recorder is not complete and shows what is down`() {
        val r = feed(aynDown, key(307, 1))
        assertFalse(r.complete)
        assertEquals(setOf(ayn, x), r.held)
        assertEquals(setOf(ayn, x), feed(aynDown, key(307, 1), key(307, 0), key(307, 1)).held)
    }

    @Test
    fun `the D-pad left then centre completes with HAT0X at minus one`() {
        val r = feed(hat(ThorControls.HAT0X, -1), hat(ThorControls.HAT0X, 0))
        assertTrue(r.complete)
        assertEquals(setOf(left), r.buttons)
    }

    @Test
    fun `the D-pad left then right with no centre releases left and holds right`() {
        val r = feed(hat(ThorControls.HAT0X, -1), hat(ThorControls.HAT0X, 1))
        assertFalse(r.complete)
        assertEquals(setOf(right), r.held)
        assertEquals(setOf(left, right), r.seen)
        assertTrue(r.feed(hat(ThorControls.HAT0X, 0)).complete)
    }

    @Test
    fun `sticks, trigger ramps and autorepeats change nothing`() {
        val stick = hat(0, 30000)
        val ramp = hat(9, 20000)
        assertEquals(TriggerRecorder(), feed(stick, ramp, key(307, 2)))
        assertEquals(feed(aynDown), feed(aynDown, stick, ramp, key(307, 2)))
    }

    @Test
    fun `an up for a control never seen is ignored`() {
        assertEquals(TriggerRecorder(), feed(key(307, 0)))
        assertEquals(feed(aynDown), feed(aynDown, key(307, 0)))
    }

    @Test
    fun `events after completion change nothing`() {
        val done = feed(key(307, 1), key(307, 0))
        assertEquals(done, done.feed(key(308, 1)))
        assertEquals(done, done.feed(aynDown))
    }

    @Test
    fun `Volume + on gpio-keys is a button`() {
        val r = feed(aynDown, key(115, 1, Device.GPIO), key(115, 0, Device.GPIO), aynUp)
        assertEquals(setOf(ModifierKey.AYN), r.modifiers)
        assertEquals(setOf(Control(Device.GPIO, EvdevType.KEY, 115, 1)), r.buttons)
    }

    @Test
    fun `an event from a device slot the model does not know changes nothing`() {
        assertEquals(TriggerRecorder(), feed(HelperMessage.Event(7, EvdevType.KEY, 307, 1)))
    }
}
