package com.grardb.thorcuts.core.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ThorcutsConfigTest {

    /** Distinct triggers per id (the button is the id's first letter), so a list of these
     *  is a valid config; [twin] is the same trigger as [SWAP_SCREENS], for Rule 2's tests. */
    private fun shortcut(id: String, enabled: Boolean = true) = SWAP_SCREENS.copy(
        id = id,
        trigger = SWAP_SCREENS.trigger.copy(buttons = setOf(padKey(id.first().code))),
        enabled = enabled,
    )
    private fun twin(id: String, enabled: Boolean = true) =
        SWAP_SCREENS.copy(id = id, enabled = enabled)

    @Test
    fun `repeated ids are refused, naming the repeat`() {
        val e = assertFailsWith<IllegalArgumentException> {
            ThorcutsConfig(listOf(shortcut("a"), shortcut("b"), shortcut("a")))
        }
        assertEquals("Shortcut names repeat: a", e.message)
    }

    @Test
    fun `two enabled shortcuts with the same trigger where both apply are refused`() {
        val e = assertFailsWith<IllegalArgumentException> {
            ThorcutsConfig(listOf(twin("a"), twin("b")))
        }
        assertEquals("'b' repeats the trigger of 'a' everywhere", e.message)
        ThorcutsConfig(listOf(twin("a"), twin("b", enabled = false)))      // parked: fine
    }

    @Test
    fun `withEnabled onto a parked duplicate is refused while its twin is on, fine once off`() {
        val c = ThorcutsConfig(listOf(twin("a"), twin("b", enabled = false)))
        val e = assertFailsWith<IllegalArgumentException> { c.withEnabled("b", true) }
        assertEquals("'b' repeats the trigger of 'a' everywhere", e.message)
        val swapped = c.withEnabled("a", false).withEnabled("b", true)
        assertEquals(listOf(false, true), swapped.shortcuts.map { it.enabled })
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
    fun `added appends and keeps the rest, unknown members included`() {
        val c = ThorcutsConfig(listOf(shortcut("a")))
        val more = c.added(shortcut("b"))
        assertEquals(listOf("a", "b"), more.shortcuts.map { it.id })
        assertEquals(shortcut("a"), more.shortcut("a"))
        assertEquals(c.unknown, more.unknown)
    }

    @Test
    fun `added with a repeated id, or a repeated trigger where both apply, is refused`() {
        val c = ThorcutsConfig(listOf(twin("a")))
        val byId = assertFailsWith<IllegalArgumentException> { c.added(shortcut("a")) }
        assertEquals("A shortcut named 'a' already exists", byId.message)   // by name, first
        val byTrigger = assertFailsWith<IllegalArgumentException> { c.added(twin("b")) }
        assertEquals("'b' repeats the trigger of 'a' everywhere", byTrigger.message)
        c.added(twin("b", enabled = false))                                // parked: fine
    }

    @Test
    fun `replaced changes the one entry in place, and refuses an id that is not there`() {
        val c = ThorcutsConfig(listOf(shortcut("a"), shortcut("b")))
        val edited = shortcut("a", enabled = false)
        val r = c.replaced(edited)
        assertEquals(listOf(edited, shortcut("b")), r.shortcuts)
        assertEquals(c.unknown, r.unknown)
        val e = assertFailsWith<IllegalArgumentException> { c.replaced(shortcut("zzz")) }
        assertEquals("No shortcut 'zzz' to replace", e.message)
    }

    @Test
    fun `replaced under another id renames in place and refuses a name that is taken`() {
        val c = ThorcutsConfig(listOf(shortcut("a"), shortcut("b"), shortcut("c")))
        val renamed = c.replaced(shortcut("x"), replacing = "b")
        assertEquals(listOf("a", "x", "c"), renamed.shortcuts.map { it.id })   // its place kept
        assertEquals(shortcut("x"), renamed.shortcut("x"))
        assertNull(renamed.shortcut("b"))
        val taken = assertFailsWith<IllegalArgumentException> {
            c.replaced(shortcut("c"), replacing = "b")
        }
        assertEquals("A shortcut named 'c' already exists", taken.message)
        val absent = assertFailsWith<IllegalArgumentException> {
            c.replaced(shortcut("x"), replacing = "zzz")
        }
        assertEquals("No shortcut 'zzz' to replace", absent.message)
    }

    @Test
    fun `a rename carries the unknown members the file kept under the old name`() {
        val read = ConfigCodec.decode(configFixture("kitchen-sink.json"))
        val c = assertIs<DecodeResult.Ok>(read).config
        val old = c.shortcut("close-in-emulators")!!
        val renamed = c.replaced(old.copy(id = "close-in-emus"), replacing = "close-in-emulators")
        assertEquals(setOf("close-in-emus"), renamed.unknown.perShortcut.keys)
        assertEquals(
            c.unknown.perShortcut["close-in-emulators"],
            renamed.unknown.perShortcut["close-in-emus"],
        )
        assertEquals(c.unknown.top, renamed.unknown.top)
        // and the written file keeps the note under the new name
        val written = ConfigCodec.encode(renamed)
        assertTrue("an unknown shortcut member" in written, written)
        assertEquals(DecodeResult.Ok(renamed, emptyList(), null), ConfigCodec.decode(written))
    }

    @Test
    fun `without removes the entry and the unknown members the file kept under its id`() {
        val read = ConfigCodec.decode(configFixture("kitchen-sink.json"))
        val c = assertIs<DecodeResult.Ok>(read).config
        assertEquals(setOf("close-in-emulators"), c.unknown.perShortcut.keys)   // its `note`
        val less = c.without("close-in-emulators")
        assertNull(less.shortcut("close-in-emulators"))
        assertEquals(c.shortcuts.size - 1, less.shortcuts.size)
        assertTrue(less.unknown.perShortcut.isEmpty())
        assertEquals(c.unknown.top, less.unknown.top)                           // `theme` stays
    }

    @Test
    fun `without of an absent id is the same config`() {
        val c = ThorcutsConfig(listOf(shortcut("a")))
        assertEquals(c, c.without("zzz"))
    }

    @Test
    fun `the default is empty`() {
        assertEquals(ThorcutsConfig(emptyList()), Defaults.CONFIG)
        assertTrue(Defaults.CONFIG.unknown.isEmpty)
    }
}
