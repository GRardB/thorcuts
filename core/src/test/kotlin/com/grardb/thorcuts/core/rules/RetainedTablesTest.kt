package com.grardb.thorcuts.core.rules

import com.grardb.thorcuts.core.model.Action
import com.grardb.thorcuts.core.model.Control
import com.grardb.thorcuts.core.model.Device
import com.grardb.thorcuts.core.model.EvdevType
import com.grardb.thorcuts.core.model.ModifierKey
import com.grardb.thorcuts.core.model.Shortcut
import com.grardb.thorcuts.core.model.Trigger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RetainedTablesTest {

    private fun shortcut(id: String, code: Int) = Shortcut(
        id = id,
        trigger = Trigger(
            setOf(ModifierKey.AYN),
            setOf(Control(Device.PAD, EvdevType.KEY, code, 1)),
        ),
        action = Action.SwapScreens,
    )

    private fun table(generation: Int, vararg ids: String): CompiledTable =
        RuleCompiler.compile(ids.mapIndexed { i, id -> shortcut(id, 304 + i) }, null, generation)

    @Test
    fun `push appends, newest last`() {
        val r = RetainedTables().push(table(1, "a")).push(table(2, "b"))
        assertEquals(listOf(1, 2), r.tables.map { it.generation })
    }

    @Test
    fun `installed prunes everything older than that generation and keeps it`() {
        val r = RetainedTables().push(table(1, "a")).push(table(2, "b")).push(table(3, "c"))
        assertEquals(listOf(2, 3), r.installed(2).tables.map { it.generation })
        assertEquals(listOf(3), r.installed(3).tables.map { it.generation })
    }

    @Test
    fun `resolve hits any retained generation`() {
        val t1 = table(1, "a", "b")
        val t2 = table(2, "c")
        val r = RetainedTables().push(t1).push(t2)
        val hit = assertIs<FireResolution.Hit>(r.resolve(1, 1))
        assertEquals("b", hit.shortcut.id)
        assertSame(t1, hit.table)
        assertEquals(1, hit.index)
        assertEquals("c", assertIs<FireResolution.Hit>(r.resolve(2, 0)).shortcut.id)
    }

    @Test
    fun `a pruned or unknown generation is stale`() {
        val r = RetainedTables().push(table(1, "a")).push(table(2, "b")).installed(2)
        assertEquals(FireResolution.Stale(1), r.resolve(1, 0))
        assertEquals(FireResolution.Stale(9), r.resolve(9, 0))
        assertEquals(FireResolution.Stale(1), RetainedTables().resolve(1, 0))
    }

    @Test
    fun `an index past the table is out of range, not a crash`() {
        val r = RetainedTables().push(table(1, "a"))
        assertEquals(FireResolution.OutOfRange(1, 1, 1), r.resolve(1, 1))
        assertEquals(FireResolution.OutOfRange(1, -1, 1), r.resolve(1, -1))
    }

    @Test
    fun `the cap drops the oldest`() {
        var r = RetainedTables()
        for (g in 1..10) r = r.push(table(g, "s$g"))
        assertEquals(RetainedTables.MAX_RETAINED, r.tables.size)
        assertEquals((3..10).toList(), r.tables.map { it.generation })
        assertTrue(r.resolve(2, 0) is FireResolution.Stale)
    }
}
