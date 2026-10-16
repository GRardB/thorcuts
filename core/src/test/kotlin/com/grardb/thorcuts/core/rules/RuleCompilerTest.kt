package com.grardb.thorcuts.core.rules

import com.grardb.thorcuts.core.config.Defaults
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
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RuleCompilerTest {

    private val dolphin = "org.dolphinemu.dolphinemu"
    private val retroarch = "com.retroarch.aarch64"

    private fun shortcut(id: String, scope: Scope, enabled: Boolean = true, code: Int = 304) =
        Shortcut(
            id = id,
            trigger = Trigger(
                setOf(ModifierKey.AYN),
                setOf(Control(Device.PAD, EvdevType.KEY, code, 1)),
            ),
            action = Action.SwapScreens,
            scope = scope,
            enabled = enabled,
        )

    private val global = shortcut("global", Scope.Global())
    private val notInRetroArch = shortcut("not-ra", Scope.Global(excluded = setOf(retroarch)))
    private val onlyDolphin = shortcut("dolphin", Scope.Apps(setOf(dolphin)))
    private val disabled = shortcut("off", Scope.Global(), enabled = false)
    private val all = listOf(global, notInRetroArch, onlyDolphin, disabled)

    private fun ids(selected: List<Shortcut>) = selected.map { it.id }

    @Test
    fun `in an app, global rules apply, exclusions bite, and app rules need a match`() {
        assertEquals(listOf("global", "not-ra", "dolphin"), ids(RuleCompiler.select(all, dolphin)))
        assertEquals(listOf("global"), ids(RuleCompiler.select(all, retroarch)))
    }

    @Test
    fun `with no foreground information, global rules apply and app-scoped rules do not`() {
        // There is no foreground monitor until M4; this is the cell every push hits until then.
        assertEquals(listOf("global", "not-ra"), ids(RuleCompiler.select(all, null)))
    }

    @Test
    fun `a disabled shortcut never applies`() {
        assertTrue(RuleCompiler.select(listOf(disabled), null).isEmpty())
        assertTrue(RuleCompiler.select(listOf(disabled), dolphin).isEmpty())
        // The scope is not consulted at all for a disabled shortcut, Apps included.
        val offInDolphin = shortcut("off-apps", Scope.Apps(setOf(dolphin)), enabled = false)
        assertTrue(RuleCompiler.select(listOf(offInDolphin), dolphin).isEmpty())
    }

    @Test
    fun `input order is preserved so rule indices are stable`() {
        val selected = RuleCompiler.select(listOf(onlyDolphin, global), dolphin)
        assertEquals(listOf("dolphin", "global"), ids(selected))
    }

    /**
     * The three-rule list the matcher rewrite shipped as its bootstrap. `Bootstrap` is gone
     * (the config layer replaced it), so the list lives here as test data and bootstrap.bin
     * stays pinned.
     */
    private val threeRules = listOf(
        Shortcut(
            id = "m1-swap-screens",
            trigger = Trigger(
                setOf(ModifierKey.AYN), setOf(Defaults.X), TriggerStyle.HOLD, holdMs = 400,
            ),
            action = Action.SwapScreens,
        ),
        Shortcut(
            id = "dpad-left-closes",
            trigger = Trigger(
                setOf(ModifierKey.AYN), setOf(Control(Device.PAD, EvdevType.ABS, 16, -1)),
            ),
            action = Action.CloseScreen(ScreenTarget.FOCUSED),
        ),
        Shortcut(
            id = "tap-ayn-opens",
            trigger = Trigger(setOf(ModifierKey.AYN)),
            action = Action.OpenThorcuts(ScreenTarget.TOP),
        ),
    )

    @Test
    fun `the three-rule bootstrap list compiles to the bootstrap fixture at generation 1`() {
        val table = RuleCompiler.compile(threeRules, foreground = null, generation = 1)
        assertContentEquals(fixture("bootstrap.bin"), table.bytes)
        assertEquals(1, table.generation)
        assertEquals(3, table.ruleCount)
        assertEquals(threeRules, table.shortcuts)
        assertEquals(RuleTableFormat.hex(table.bytes), table.hex)
    }

    @Test
    fun `the default config compiles to the m1-swap fixture at generation 1`() {
        // Ids are not encoded, so the one-rule default and M1's bootstrap share the bytes.
        val table = RuleCompiler.compile(Defaults.CONFIG.shortcuts, null, generation = 1)
        assertContentEquals(fixture("m1-swap.bin"), table.bytes)
        assertEquals(listOf(Defaults.SWAP_SCREENS), table.shortcuts)
    }

    @Test
    fun `nothing applicable compiles to an empty table`() {
        val table = RuleCompiler.compile(listOf(onlyDolphin, disabled), null, generation = 5)
        assertEquals(0, table.ruleCount)
        assertEquals(12, table.bytes.size)
        assertEquals(5, table.generation)
        assertTrue(table.shortcuts.isEmpty())
    }

    @Test
    fun `more than sixty-four applicable rules are refused`() {
        val many = (0 until 65).map { shortcut("s$it", Scope.Global(), code = 512 + it) }
        assertFailsWith<IllegalArgumentException> { RuleCompiler.compile(many, null, 1) }
        // Scoping can bring it under the limit: 65 shortcuts, 64 applicable, is fine.
        val one = many.take(64) + shortcut("elsewhere", Scope.Apps(setOf(dolphin)), code = 600)
        assertEquals(64, RuleCompiler.compile(one, null, 1).ruleCount)
    }

    @Test
    fun `check passes a list that fits at null and in every named app`() {
        val globals = (0 until 59).map { shortcut("g$it", Scope.Global(), code = 512 + it) } +
            shortcut("not-ra", Scope.Global(excluded = setOf(retroarch)), code = 571)
        val dolphins = (0 until 4).map {
            shortcut("d$it", Scope.Apps(setOf(dolphin)), code = 600 + it)
        }
        val list = globals + dolphins
        assertEquals(60, RuleCompiler.select(list, null).size)
        assertEquals(59, RuleCompiler.select(list, retroarch).size)
        assertEquals(64, RuleCompiler.select(list, dolphin).size)
        RuleCompiler.check(list)                                // nothing thrown
    }

    @Test
    fun `check refuses a list that overflows in a named app, naming it`() {
        val globals = (0 until 60).map { shortcut("g$it", Scope.Global(), code = 512 + it) }
        val dolphins = (0 until 5).map {
            shortcut("d$it", Scope.Apps(setOf(dolphin)), code = 600 + it)
        }
        val list = globals + dolphins
        assertEquals(60, RuleCompiler.compile(list, null, 1).ruleCount)   // fits unscoped
        val e = assertFailsWith<IllegalArgumentException> { RuleCompiler.check(list) }
        assertEquals("65 shortcuts apply in $dolphin; the table holds at most 64", e.message)
    }

    @Test
    fun `an unnamed package selects what null does`() {
        assertEquals(RuleCompiler.select(all, null), RuleCompiler.select(all, "com.example.x"))
    }
}
