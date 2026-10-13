package com.grardb.thorcuts.core.config

import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class ConfigMergeTest {

    private fun shortcut(id: String) = Defaults.SWAP_SCREENS.copy(id = id)
    private fun extras(vararg pairs: Pair<String, String>) =
        pairs.associate { (k, v) -> k to JsonPrimitive(v) }

    private val current = ThorcutsConfig(
        listOf(shortcut("a"), shortcut("b")),
        UnknownFields(
            top = extras("theme" to "dark"),
            perShortcut = mapOf("b" to extras("note" to "mine")),
        ),
    )
    private val imported = DecodeResult.Ok(
        config = ThorcutsConfig(
            listOf(shortcut("b"), shortcut("c")),
            UnknownFields(
                top = extras("theme" to "light", "author" to "someone"),
                perShortcut = mapOf(
                    "b" to extras("note" to "theirs"),
                    "c" to extras("note" to "new"),
                ),
            ),
        ),
        dropped = listOf(Dropped(2, "d", "unknown action kind 'Teleport'")),
        migratedFrom = null,
    )

    @Test
    fun `new ids are added in import order, present ids are skipped, drops pass through`() {
        val r = ConfigMerge.merge(current, imported)
        assertEquals(listOf("a", "b", "c"), r.config.shortcuts.map { it.id })
        assertEquals(listOf("c"), r.added)
        assertEquals(listOf("b"), r.skipped)
        assertEquals(imported.dropped, r.dropped)
    }

    @Test
    fun `the current file's top-level unknowns survive and the import's do not`() {
        val r = ConfigMerge.merge(current, imported)
        assertEquals(extras("theme" to "dark"), r.config.unknown.top)
    }

    @Test
    fun `an added shortcut brings its unknowns, a skipped one leaves the current ones alone`() {
        val r = ConfigMerge.merge(current, imported)
        assertEquals(
            mapOf("b" to extras("note" to "mine"), "c" to extras("note" to "new")),
            r.config.unknown.perShortcut,
        )
    }

    @Test
    fun `importing into an empty config takes every shortcut and no top-level unknowns`() {
        val r = ConfigMerge.merge(ThorcutsConfig(emptyList()), imported)
        assertEquals(listOf("b", "c"), r.config.shortcuts.map { it.id })
        assertEquals(listOf("b", "c"), r.added)
        assertEquals(emptyList(), r.skipped)
        val expected = UnknownFields(emptyMap(), imported.config.unknown.perShortcut)
        assertEquals(expected, r.config.unknown)
    }
}
