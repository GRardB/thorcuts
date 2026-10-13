package com.grardb.thorcuts.core.config

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MigrationsTest {

    private val v1 = buildJsonObject { put("schemaVersion", 1); put("name", "x") }

    private fun JsonObject.version() = this["schemaVersion"]!!.jsonPrimitive.int
    private fun JsonObject.with(key: String, value: Int) =
        JsonObject(this + (key to JsonPrimitive(value)))

    @Test
    fun `the current chain is empty and the identity at the current version`() {
        assertTrue(Migrations.CURRENT.isEmpty())
        assertEquals(v1, Migrations().migrate(v1, from = 1))
    }

    @Test
    fun `an injected chain applies in order and stamps the version after each step`() {
        val seen = mutableListOf<Int>()
        val steps = mapOf<Int, (JsonObject) -> JsonObject>(
            1 to { d -> seen += d.version(); d.with("a", 1) },
            2 to { d -> seen += d.version(); d.with("b", 2) },
        )
        val out = Migrations(steps).migrate(v1, from = 1, to = 3)
        assertEquals(listOf(1, 2), seen)
        assertEquals(3, out.version())
        assertEquals(1, out["a"]!!.jsonPrimitive.int)
        assertEquals(2, out["b"]!!.jsonPrimitive.int)
        assertEquals("x", out["name"]!!.jsonPrimitive.content)
        // Starting later skips the earlier step.
        val v2 = buildJsonObject { put("schemaVersion", 2) }
        Migrations(steps).migrate(v2, from = 2, to = 3)
        assertEquals(listOf(1, 2, 2), seen)
    }

    @Test
    fun `a hole in the chain is an error naming the version`() {
        val steps = mapOf<Int, (JsonObject) -> JsonObject>(2 to { it })
        val e = assertFailsWith<IllegalStateException> {
            Migrations(steps).migrate(v1, from = 1, to = 3)
        }
        assertTrue("from schema version 1 to 2" in e.message!!, e.message)
        val m = Migrations(steps)
        assertFailsWith<IllegalArgumentException> { m.migrate(v1, from = 0, to = 3) }
        assertFailsWith<IllegalArgumentException> { m.migrate(v1, from = 4, to = 3) }
    }
}
