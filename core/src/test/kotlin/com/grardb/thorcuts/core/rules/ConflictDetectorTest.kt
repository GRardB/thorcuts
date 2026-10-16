package com.grardb.thorcuts.core.rules

import com.grardb.thorcuts.core.model.Action
import com.grardb.thorcuts.core.model.Control
import com.grardb.thorcuts.core.model.Device
import com.grardb.thorcuts.core.model.EvdevType
import com.grardb.thorcuts.core.model.ModifierKey
import com.grardb.thorcuts.core.model.Scope
import com.grardb.thorcuts.core.model.Shortcut
import com.grardb.thorcuts.core.model.Trigger
import com.grardb.thorcuts.core.model.TriggerStyle
import com.grardb.thorcuts.core.rules.ConflictDetector.Warning
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConflictDetectorTest {

    private fun key(code: Int) = Control(Device.PAD, EvdevType.KEY, code, 1)
    private val x = key(307)
    private val y = key(308)
    private val ayn = setOf(ModifierKey.AYN)
    private val select = setOf(ModifierKey.SELECT)

    private fun press(mods: Set<ModifierKey> = ayn, vararg buttons: Control) =
        Trigger(mods, buttons.toSet())
    private fun hold(mods: Set<ModifierKey> = ayn, vararg buttons: Control, ms: Int = 400) =
        Trigger(mods, buttons.toSet(), TriggerStyle.HOLD, holdMs = ms)

    private fun shortcut(
        id: String,
        trigger: Trigger = press(ayn, x),
        scope: Scope = Scope.Global(),
        enabled: Boolean = true,
    ) = Shortcut(id, trigger, Action.SwapScreens, scope, enabled)

    private val ra = "com.retroarch.aarch64"
    private val dolphin = "org.dolphinemu.dolphinemu"
    private val eden = "dev.eden.eden_emulator"

    // ── sameTrigger: the matcher's Exact relation, plus the tie rule ─────────────────

    @Test
    fun `the same modifiers, buttons and style are the same trigger`() {
        assertTrue(ConflictDetector.sameTrigger(press(ayn, x), press(ayn, x)))
        assertTrue(ConflictDetector.sameTrigger(hold(ayn, x), hold(ayn, x)))
        assertTrue(ConflictDetector.sameTrigger(press(ayn), press(ayn)))
    }

    @Test
    fun `a PRESS and a HOLD with a button are the same trigger - the PRESS wins on the down`() {
        assertTrue(ConflictDetector.sameTrigger(press(ayn, x), hold(ayn, x)))
        assertTrue(ConflictDetector.sameTrigger(hold(ayn, x), press(ayn, x)))
    }

    @Test
    fun `a bare PRESS and a bare HOLD on the same modifiers are tap versus hold, not the same`() {
        assertFalse(ConflictDetector.sameTrigger(press(ayn), hold(ayn)))
        assertFalse(ConflictDetector.sameTrigger(hold(ayn), press(ayn)))
    }

    @Test
    fun `two HOLDs with different lengths are the same trigger - only the first ever arms`() {
        assertTrue(ConflictDetector.sameTrigger(hold(ayn, x, ms = 400), hold(ayn, x, ms = 900)))
        assertTrue(ConflictDetector.sameTrigger(hold(ayn, ms = 400), hold(ayn, ms = 900)))
    }

    @Test
    fun `different buttons or different modifiers are different triggers`() {
        assertFalse(ConflictDetector.sameTrigger(press(ayn, x), press(ayn, y)))
        assertFalse(ConflictDetector.sameTrigger(press(ayn, x), press(ayn, x, y)))
        assertFalse(ConflictDetector.sameTrigger(press(ayn, x), press(select, x)))
        assertFalse(ConflictDetector.sameTrigger(press(ayn, x), press(ayn + select, x)))
    }

    // ── overlap: where two scopes share a table ──────────────────────────────────────

    @Test
    fun `two global scopes always overlap, excluding what either excludes`() {
        assertEquals(Scope.Global(), ConflictDetector.overlap(Scope.Global(), Scope.Global()))
        assertEquals(
            Scope.Global(setOf(ra, dolphin)),
            ConflictDetector.overlap(Scope.Global(setOf(ra)), Scope.Global(setOf(dolphin))),
        )
    }

    @Test
    fun `a global and an app list overlap in the listed apps the global does not exclude`() {
        val global = Scope.Global(setOf(ra))
        val apps = Scope.Apps(setOf(ra, dolphin))
        assertEquals(Scope.Apps(setOf(dolphin)), ConflictDetector.overlap(global, apps))
        assertEquals(Scope.Apps(setOf(dolphin)), ConflictDetector.overlap(apps, global))
        assertEquals(apps, ConflictDetector.overlap(Scope.Global(), apps))
    }

    @Test
    fun `a global that excludes every listed app does not overlap it`() {
        val global = Scope.Global(setOf(ra, dolphin))
        assertNull(ConflictDetector.overlap(global, Scope.Apps(setOf(ra, dolphin))))
        assertNull(ConflictDetector.overlap(Scope.Apps(setOf(ra)), global))
    }

    @Test
    fun `two app lists overlap in the apps they share, or not at all`() {
        val raAndDolphin = Scope.Apps(setOf(ra, dolphin))
        val dolphinAndEden = Scope.Apps(setOf(dolphin, eden))
        val shared = ConflictDetector.overlap(raAndDolphin, dolphinAndEden)
        assertEquals(Scope.Apps(setOf(dolphin)), shared)
        assertNull(ConflictDetector.overlap(Scope.Apps(setOf(ra)), Scope.Apps(setOf(dolphin))))
    }

    // ── conflict / firstConflict ─────────────────────────────────────────────────────

    @Test
    fun `a later shortcut repeating an earlier trigger everywhere is a conflict naming both`() {
        val earlier = shortcut("swap")
        val later = shortcut("close")
        val c = assertNotNull(ConflictDetector.firstConflict(listOf(earlier, later)))
        assertEquals(earlier, c.earlier)
        assertEquals(later, c.later)
        assertEquals(Scope.Global(), c.where)
        assertEquals("'close' repeats the trigger of 'swap' everywhere", c.reason)
    }

    @Test
    fun `the reason names the shared apps, sorted`() {
        val earlier = shortcut("swap", scope = Scope.Global(setOf(eden)))
        val later = shortcut("close", scope = Scope.Apps(setOf(ra, dolphin, eden)))
        val c = assertNotNull(ConflictDetector.conflict(listOf(earlier), later))
        assertEquals(Scope.Apps(setOf(ra, dolphin)), c.where)
        assertEquals("'close' repeats the trigger of 'swap' in $ra, $dolphin", c.reason)
    }

    @Test
    fun `the reason names what two globals exclude, sorted`() {
        val earlier = shortcut("swap", scope = Scope.Global(setOf(ra)))
        val later = shortcut("close", scope = Scope.Global(setOf(dolphin)))
        val c = assertNotNull(ConflictDetector.conflict(listOf(earlier), later))
        assertEquals(Scope.Global(setOf(ra, dolphin)), c.where)
        val reason = "'close' repeats the trigger of 'swap' everywhere except $ra, $dolphin"
        assertEquals(reason, c.reason)
    }

    @Test
    fun `disjoint scopes, different triggers, or a disabled side are no conflict`() {
        val swap = listOf(shortcut("swap", scope = Scope.Global(setOf(ra))))
        assertNull(ConflictDetector.conflict(swap, shortcut("ra", scope = Scope.Apps(setOf(ra)))))
        assertNull(ConflictDetector.conflict(swap, shortcut("other", trigger = press(ayn, y))))
        assertNull(ConflictDetector.conflict(swap, shortcut("parked", enabled = false)))
        val off = listOf(shortcut("off", enabled = false))
        assertNull(ConflictDetector.conflict(off, shortcut("on")))
        assertNull(ConflictDetector.conflict(emptyList(), shortcut("alone")))
    }

    @Test
    fun `conflict names the first of several matching accepted entries, in list order`() {
        val accepted = listOf(shortcut("a", press(ayn, y)), shortcut("b"), shortcut("c"))
        val c = assertNotNull(ConflictDetector.conflict(accepted, shortcut("d")))
        assertEquals("b", c.earlier.id)
        assertEquals("d", c.later.id)
    }

    @Test
    fun `firstConflict is the first later shortcut that repeats any earlier one, in list order`() {
        val list = listOf(
            shortcut("a", press(ayn, x)),
            shortcut("b", press(ayn, y)),
            shortcut("c", press(ayn, y)),   // repeats b
            shortcut("d", press(ayn, x)),   // repeats a, but c comes first
        )
        val c = assertNotNull(ConflictDetector.firstConflict(list))
        assertEquals("b", c.earlier.id)
        assertEquals("c", c.later.id)
        assertNull(ConflictDetector.firstConflict(list.take(2)))
        assertNull(ConflictDetector.firstConflict(emptyList()))
    }

    // ── warnings ─────────────────────────────────────────────────────────────────────

    @Test
    fun `a timed trigger with a button on Start or Select carries the lateness warning`() {
        val expected = listOf(Warning.HOLD_ON_GAMEPLAY_MODIFIER)
        assertEquals(expected, ConflictDetector.warnings(hold(select, x)))
        assertEquals(expected, ConflictDetector.warnings(hold(setOf(ModifierKey.START), x)))
        assertEquals(expected, ConflictDetector.warnings(hold(ayn + select, x)))
    }

    @Test
    fun `a PRESS on Select, a timed AYN, and a bare timed Select carry no warning`() {
        assertEquals(emptyList(), ConflictDetector.warnings(press(select, x)))
        assertEquals(emptyList(), ConflictDetector.warnings(hold(ayn, x)))
        assertEquals(emptyList(), ConflictDetector.warnings(hold(select)))
        assertEquals(emptyList(), ConflictDetector.warnings(hold(setOf(ModifierKey.HOME), x)))
    }
}
