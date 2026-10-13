package com.grardb.thorcuts.core.config

import com.grardb.thorcuts.core.model.ModifierKey
import com.grardb.thorcuts.core.model.Scope
import com.grardb.thorcuts.core.model.Trigger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfigCodecTest {

    // ── helpers ───────────────────────────────────────────────────────────────────────

    private fun file(vararg shortcuts: String, version: String = "1") =
        """{"schemaVersion": $version, "shortcuts": [${shortcuts.joinToString(",")}]}"""

    private fun button(code: Int) = """{"device":"PAD","type":"KEY","code":$code,"value":1}"""
    private val oneButton = "[${button(307)}]"
    private val triggerObject =
        """{"modifiers":["AYN"],"buttons":$oneButton,"style":"HOLD","holdMs":400}"""

    /** One valid shortcut; the drop tests perturb one member of it at a time. */
    private val swap = """{"id":"swap","trigger":$triggerObject,"action":{"kind":"SwapScreens"}}"""

    private fun ok(text: String): DecodeResult.Ok = assertIs(ConfigCodec.decode(text))

    /** Decodes a one-entry file expected to drop its entry; returns the drop. */
    private fun dropped(entry: String): Dropped {
        val r = ok(file(entry))
        assertTrue(r.config.shortcuts.isEmpty(), "nothing should survive in $entry")
        return r.dropped.single()
    }

    private fun assertDrops(cases: List<Pair<String, String>>) {
        for ((entry, fragment) in cases) {
            val d = dropped(entry)
            assertEquals(0, d.index)
            assertTrue(fragment in d.reason, "expected '$fragment' in '${d.reason}' for $entry")
        }
    }

    // ── the fixtures ──────────────────────────────────────────────────────────────────

    @Test
    fun `the default encodes to the default fixture`() {
        assertEquals(configFixture("default.json"), ConfigCodec.encode(Defaults.CONFIG))
    }

    @Test
    fun `the kitchen sink decodes to its model with nothing dropped`() {
        val r = ok(configFixture("kitchen-sink.json"))
        assertEquals(KITCHEN_SINK, r.config)
        assertTrue(r.dropped.isEmpty())
        assertNull(r.migratedFrom)
    }

    @Test
    fun `the kitchen sink encodes canonically - ordered, sorted, unknowns appended`() {
        val canonical = configFixture("kitchen-sink.canonical.json")
        assertEquals(canonical, ConfigCodec.encode(KITCHEN_SINK))
    }

    @Test
    fun `encode then decode is the identity`() {
        for (c in listOf(KITCHEN_SINK, Defaults.CONFIG, ThorcutsConfig(emptyList()))) {
            val back = ConfigCodec.decode(ConfigCodec.encode(c))
            assertEquals(DecodeResult.Ok(c, emptyList(), null), back)
        }
    }

    // ── forgiveness ───────────────────────────────────────────────────────────────────

    @Test
    fun `enabled, scope and buttons have defaults when missing`() {
        val bare = """{"id":"t","trigger":{"modifiers":["AYN"],"style":"PRESS"},""" +
            """"action":{"kind":"SwapScreens"}}"""
        val s = ok(file(bare)).config.shortcuts.single()
        assertTrue(s.enabled)
        assertEquals(Scope.Global(), s.scope)
        assertEquals(Trigger(setOf(ModifierKey.AYN)), s.trigger)
    }

    @Test
    fun `unknown members are preserved at the top level and per shortcut`() {
        val text = """{"schemaVersion":1,"extra":[1,2],"shortcuts":[""" +
            swap.dropLast(1) + ""","colour":"red"}]}"""
        val c = ok(text).config
        assertFalse(c.unknown.isEmpty)
        val written = ConfigCodec.encode(c)
        assertTrue("\"extra\": [" in written, written)
        assertTrue("\"colour\": \"red\"" in written, written)
        assertEquals(DecodeResult.Ok(c, emptyList(), null), ConfigCodec.decode(written))
    }

    @Test
    fun `unknown members inside trigger, action and scope are NOT preserved - a schema bump`() {
        val text = file(
            swap.replace(""""style":"HOLD"""", """"repeat":3,"style":"HOLD"""")
                .replace(""""kind":"SwapScreens"""", """"kind":"SwapScreens","turbo":true""")
        )
        val r = ok(text)
        assertTrue(r.dropped.isEmpty())
        assertTrue(r.config.unknown.isEmpty)
        val written = ConfigCodec.encode(r.config)
        assertFalse("repeat" in written, written)
        assertFalse("turbo" in written, written)
    }

    @Test
    fun `encoding does not depend on set insertion order`() {
        val a = KITCHEN_SINK
        val b = ThorcutsConfig(
            a.shortcuts.map { s ->
                s.copy(
                    trigger = s.trigger.copy(
                        modifiers = s.trigger.modifiers.reversed().toSet(),
                        buttons = s.trigger.buttons.reversed().toSet(),
                    ),
                    scope = when (val sc = s.scope) {
                        is Scope.Global -> Scope.Global(sc.excluded.reversed().toSet())
                        is Scope.Apps -> Scope.Apps(sc.included.reversed().toSet())
                    },
                )
            },
            a.unknown,
        )
        assertEquals(ConfigCodec.encode(a), ConfigCodec.encode(b))
    }

    // ── whole-file failures ───────────────────────────────────────────────────────────

    @Test
    fun `a file whose frame is wrong fails as a whole`() {
        val cases = listOf(
            "" to null,
            "nope" to null,
            "[]" to "not an object",
            "{}" to "schemaVersion is missing",
            """{"schemaVersion":"1","shortcuts":[]}""" to "missing or not an integer",
            """{"schemaVersion":0,"shortcuts":[]}""" to "below 1",
            """{"schemaVersion":2,"shortcuts":[]}""" to "newer than this app understands",
            """{"schemaVersion":1,"shortcuts":{}}""" to "shortcuts is missing or not an array",
            """{"schemaVersion":1}""" to "shortcuts is missing or not an array",
        )
        for ((text, fragment) in cases) {
            val r = assertIs<DecodeResult.Failed>(ConfigCodec.decode(text), "for $text")
            if (fragment != null) assertTrue(fragment in r.reason, "'$fragment' in '${r.reason}'")
        }
    }

    // ── per-shortcut drops ────────────────────────────────────────────────────────────

    @Test
    fun `an entry the model refuses is dropped with the model's reason`() = assertDrops(
        listOf(
            swap.replace(oneButton, (304..308).joinToString(",", "[", "]") { button(it) })
                to "at most 4 buttons",
            swap.replace(""""code":307""", """"code":314""")
                to "a modifier cannot also be a button",
            swap.replace(""","holdMs":400""", "") to "HOLD needs holdMs",
            swap.replace(""""style":"HOLD"""", """"style":"PRESS"""")
                to "PRESS has no hold duration",
            swap.replace(""""modifiers":["AYN"]""", """"modifiers":[]""")
                to "a trigger starts with a modifier",
            swap.replace(oneButton, """[{"device":"PAD","type":"ABS","code":16,"value":0}]""")
                to "non-zero threshold",
        )
    )

    @Test
    fun `an Apps scope with no packages is dropped`() = assertDrops(
        listOf(
            swap.dropLast(1) + ""","scope":{"kind":"Apps","included":[]}}"""
                to "at least one package",
        )
    )

    @Test
    fun `a member of the wrong shape is dropped naming the member`() = assertDrops(
        listOf(
            swap.replace(""""code":307""", """"code":307.5""") to "code is not an integer",
            swap.replace(""""code":307""", """"code":"307"""") to "code is not an integer",
            swap.replace(""""device":"PAD"""", """"device":"MOUSE"""")
                to "device: unknown name 'MOUSE'",
            swap.replace(""""modifiers":["AYN"]""", """"modifiers":["TURBO"]""")
                to "modifiers: unknown name 'TURBO'",
            swap.replace(""""style":"HOLD"""", """"style":"TAP"""") to "style: unknown name 'TAP'",
            swap.replace(""""type":"KEY"""", """"type":"REL"""") to "type: unknown name 'REL'",
            swap.dropLast(1) + ""","enabled":"true"}""" to "enabled is not a boolean",
            swap.replace(triggerObject, "\"hold AYN+X\"") to "trigger is not an object",
            swap.replace(""""action":{"kind":"SwapScreens"}""", """"action":"Swap"""")
                to "action is not an object",
            swap.dropLast(1) + ""","scope":{"kind":"Everywhere"}}"""
                to "unknown scope kind 'Everywhere'",
        )
    )

    @Test
    fun `an action of the wrong shape is dropped`() = assertDrops(
        listOf(
            swap.replace(""""kind":"SwapScreens"""", """"kind":"Teleport"""")
                to "unknown action kind 'Teleport'",
            swap.replace(""""kind":"SwapScreens"""", """"kind":"SwapScreens","screen":"TOP"""")
                to "SwapScreens takes no screen",
            swap.replace(""""kind":"SwapScreens"""", """"kind":"CloseScreen"""")
                to "CloseScreen needs a screen",
            swap.replace(""""kind":"SwapScreens"""", """"kind":"GoHome","screen":"LEFT"""")
                to "screen: unknown name 'LEFT'",
        )
    )

    @Test
    fun `an entry without a usable id is dropped with a null id`() {
        val blank = dropped(swap.replace(""""id":"swap"""", """"id":"  """"))
        assertNull(blank.id)
        assertTrue("needs an id" in blank.reason, blank.reason)
        val missing = dropped(swap.replace(""""id":"swap",""", ""))
        assertNull(missing.id)
        assertTrue("id is missing" in missing.reason, missing.reason)
        val notAnObject = dropped("42")
        assertNull(notAnObject.id)
        assertEquals("not an object", notAnObject.reason)
    }

    @Test
    fun `a repeated id drops the later entry and keeps the first`() {
        val r = ok(file(swap, swap.replace(""""holdMs":400""", """"holdMs":900""")))
        assertEquals(listOf("swap"), r.config.shortcuts.map { it.id })
        assertEquals(400, r.config.shortcuts.single().trigger.holdMs)
        assertEquals(listOf(Dropped(1, "swap", "duplicate id")), r.dropped)
    }

    @Test
    fun `a dropped entry does not take its neighbours with it`() {
        val bad = swap.replace(""""id":"swap"""", """"id":"bad"""")
            .replace(""""kind":"SwapScreens"""", """"kind":"Teleport"""")
        val r = ok(file(bad, swap, "null"))
        assertEquals(listOf("swap"), r.config.shortcuts.map { it.id })
        assertEquals(listOf(0, 2), r.dropped.map { it.index })
        assertEquals(listOf("bad", null), r.dropped.map { it.id })
    }
}
