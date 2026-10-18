package com.grardb.thorcuts.core.config

import com.grardb.thorcuts.core.model.Action
import com.grardb.thorcuts.core.model.ModifierKey
import com.grardb.thorcuts.core.model.Scope
import com.grardb.thorcuts.core.model.ScreenTarget
import com.grardb.thorcuts.core.model.Trigger
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfigCodecTest {

    // ── helpers ───────────────────────────────────────────────────────────────────────

    private fun file(
        vararg shortcuts: String, version: String = ConfigCodec.SCHEMA_VERSION.toString(),
    ) =
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
    fun `the actions run fixture decodes to its model with nothing dropped`() {
        val r = ok(configFixture("actions-run.json"))
        assertEquals(ACTIONS_RUN, r.config)
        assertTrue(r.dropped.isEmpty())
    }

    @Test
    fun `the actions run fixture is the encoder's own output`() {
        assertEquals(configFixture("actions-run.json"), ConfigCodec.encode(ACTIONS_RUN))
    }

    @Test
    fun `encode then decode is the identity`() {
        for (c in listOf(KITCHEN_SINK, ONE_SHORTCUT, ThorcutsConfig(emptyList()))) {
            val back = ConfigCodec.decode(ConfigCodec.encode(c))
            assertEquals(DecodeResult.Ok(c, emptyList(), null), back)
        }
    }

    @Test
    fun `the one-shortcut fixture encodes to its golden text`() {
        // The store tests compare a first-run write against this text, so the tie between
        // the value and the bytes is pinned here, once, against a literal.
        assertEquals(ONE_SHORTCUT_TEXT, ConfigCodec.encode(ONE_SHORTCUT))
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
    fun `unknown members inside trigger, action, scope and a control are NOT preserved`() {
        // A new member inside any of the four is a schema bump by convention (config spec).
        val text = file(
            swap.replace(""""style":"HOLD"""", """"repeat":3,"style":"HOLD"""")
                .replace(""""kind":"SwapScreens"""", """"kind":"SwapScreens","turbo":true""")
                .replace(""""value":1}""", """"value":1,"label":"X"}""")
                .dropLast(1) + ""","scope":{"kind":"Global","excluded":[],"strict":true}}"""
        )
        val r = ok(text)
        assertTrue(r.dropped.isEmpty())
        assertTrue(r.config.unknown.isEmpty)
        val written = ConfigCodec.encode(r.config)
        for (member in listOf("repeat", "turbo", "label", "strict")) {
            assertFalse(member in written, "$member in $written")
        }
    }

    @Test
    fun `an unknown member named like a reserved one is not written`() {
        // Reachable only through the internal constructor (decode filters): the merge and the
        // tests build UnknownFields directly, and a reserved name there must not overwrite the
        // real member on the way out, or the file would not read back (T2-3).
        val shortcut = ok(file(swap)).config.shortcuts.single()
        val reserved = UnknownFields(
            top = mapOf(
                "schemaVersion" to JsonPrimitive(9),
                "shortcuts" to JsonArray(emptyList()),
            ),
            perShortcut = mapOf("swap" to mapOf("id" to JsonPrimitive("other"))),
        )
        val written = ConfigCodec.encode(ThorcutsConfig(listOf(shortcut), reserved))
        assertTrue("\"schemaVersion\": 1" in written, written)
        val back = ConfigCodec.decode(written)
        assertEquals(DecodeResult.Ok(ThorcutsConfig(listOf(shortcut)), emptyList(), null), back)
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
            "" to "not JSON: the file is empty",
            " \n" to "not JSON: the file is empty",
            "{" to "not JSON",
            "nope" to "not an object",          // a bare word is a lenient literal: a value
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
            assertTrue(fragment in r.reason, "'$fragment' in '${r.reason}'")
        }
    }

    // ── the migration wiring (T2-2): unreachable at schema version 1, so the overload ─────

    /** A v1 file whose shortcuts sit under `rules`: what a step from 1 to 2 would rename. */
    private val underRules = """{"schemaVersion": 1, "rules": [$swap]}"""

    private val renameRules: (JsonObject) -> JsonObject =
        { d -> JsonObject(d - "rules" + ("shortcuts" to d.getValue("rules"))) }

    @Test
    fun `a migration step runs for an older file and is recorded`() {
        val chain = Migrations(mapOf(1 to renameRules))
        val r = assertIs<DecodeResult.Ok>(ConfigCodec.decode(underRules, chain, current = 2))
        assertEquals(listOf("swap"), r.config.shortcuts.map { it.id })
        assertTrue(r.dropped.isEmpty())
        assertEquals(1, r.migratedFrom)
    }

    @Test
    fun `a hole in the migration chain is a Failed naming the gap`() {
        val hole = ConfigCodec.decode(underRules, Migrations(emptyMap()), current = 2)
        val h = assertIs<DecodeResult.Failed>(hole)
        assertTrue("no migration from schema version 1 to 2" in h.reason, h.reason)
    }

    @Test
    fun `a migration step that throws is a Failed with its message`() {
        // A step's own `require` is an IllegalArgumentException: this file's failure too, not
        // a throw out of a method whose contract is "never throws on input" (T2-1).
        val boom: (JsonObject) -> JsonObject = { throw IllegalArgumentException("boom") }
        val r = ConfigCodec.decode(underRules, Migrations(mapOf(1 to boom)), current = 2)
        assertEquals(DecodeResult.Failed("boom"), r)
    }

    @Test
    fun `a migration step that throws without a message is a Failed naming its type`() {
        // A step bug (an NPE, a bad cast) must read differently from a bad file.
        val bug: (JsonObject) -> JsonObject = { throw NullPointerException() }
        val r = ConfigCodec.decode(underRules, Migrations(mapOf(1 to bug)), current = 2)
        assertEquals(DecodeResult.Failed("migration failed (NullPointerException)"), r)
    }

    @Test
    fun `a parse failure reads the parser's first line, or unparseable when it has none`() {
        val twoLines = SerializationException("Unexpected JSON token\nJSON input: nope")
        assertEquals("not JSON: Unexpected JSON token", ConfigCodec.parseFailure(twoLines))
        assertEquals("not JSON: unparseable", ConfigCodec.parseFailure(SerializationException("")))
        assertEquals("not JSON: unparseable", ConfigCodec.parseFailure(SerializationException()))
    }

    @Test
    fun `a dropped entry's line is its index, id and reason`() {
        val d = Dropped(3, "bad", "unknown action kind 'Teleport'")
        assertEquals("#3 bad: unknown action kind 'Teleport'", d.line)
        assertEquals("#0 ?: not an object", Dropped(0, null, "not an object").line)
    }

    // ── per-shortcut drops ────────────────────────────────────────────────────────────

    @Test
    fun `an entry the model refuses is dropped with the model's reason`() = assertDrops(
        listOf(
            swap.replace(oneButton, (304..308).joinToString(",", "[", "]") { button(it) })
                to "at most 4 buttons",
            swap.replace(""""code":307""", """"code":314""")
                to "A modifier cannot also be a button",
            swap.replace(""","holdMs":400""", "") to "The timer must be between",
            swap.replace(""""style":"HOLD"""", """"style":"PRESS"""")
                to "A tap has no timer",
            swap.replace(""""modifiers":["AYN"]""", """"modifiers":[]""")
                to "A trigger must start with a modifier",
            swap.replace(oneButton, """[{"device":"PAD","type":"ABS","code":16,"value":0}]""")
                to "non-zero threshold",
        )
    )

    @Test
    fun `a bare Home tap is dropped and the rest of the file loads`() {
        val homeTap = """{"id":"home-tap","trigger":{"modifiers":["HOME"],"style":"PRESS"},""" +
            """"action":{"kind":"GoHome","screen":"TOP"}}"""
        val r = ok(file(swap, homeTap))
        assertEquals(listOf("swap"), r.config.shortcuts.map { it.id })
        val d = r.dropped.single()
        assertEquals(1, d.index)
        assertEquals("home-tap", d.id)
        assertEquals("Home alone cannot be a shortcut: it would no longer go home", d.reason)
    }

    @Test
    fun `a later enabled shortcut repeating a trigger is dropped, naming the earlier one`() {
        val again = swap.replace(""""id":"swap"""", """"id":"again"""")
        val parked = swap.replace(""""id":"swap"""", """"id":"parked","enabled":false""")
        val r = ok(file(swap, again, parked))
        assertEquals(listOf("swap", "parked"), r.config.shortcuts.map { it.id })
        val reason = "'again' repeats the trigger of 'swap' everywhere"
        assertEquals(Dropped(1, "again", reason), r.dropped.single())
    }

    @Test
    fun `an Apps scope with no packages is dropped`() = assertDrops(
        listOf(
            swap.dropLast(1) + ""","scope":{"kind":"Apps","included":[]}}"""
                to "Choose at least one app",
        )
    )

    @Test
    fun `a member of the wrong shape is dropped naming the member`() = assertDrops(
        listOf(
            swap.replace(""""code":307""", """"code":307.5""") to "code is not an integer",
            swap.replace(""""code":307""", """"code":"307"""") to "code is not an integer",
            swap.replace(""""code":307""", """"code":3000000000""") to "code is out of range",
            swap.replace(""""device":"PAD"""", """"device":"MOUSE"""")
                to "device: unknown name 'MOUSE'",
            swap.replace(""""modifiers":["AYN"]""", """"modifiers":["TURBO"]""")
                to "modifiers[0]: unknown name 'TURBO'",
            swap.replace(""""modifiers":["AYN"]""", """"modifiers":["AYN","TURBO"]""")
                to "modifiers[1]: unknown name 'TURBO'",
            swap.replace(oneButton, "[1]") to "buttons[0] is not an object",
            swap.dropLast(1) + ""","scope":{"kind":"Global","excluded":[1]}}"""
                to "excluded[0] is not a string",
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
            swap.replace(""""kind":"SwapScreens"""", """"kind":"OpenApp","screen":"TOP"""")
                to "OpenApp needs a package",
            swap.replace(""""kind":"SwapScreens"""", """"kind":"SwapScreens","package":"a.b"""")
                to "SwapScreens takes no package",
            swap.replace(
                """"kind":"SwapScreens"""", """"kind":"GoHome","screen":"TOP","package":"a.b"""",
            ) to "GoHome takes no package",
            swap.replace(
                """"kind":"SwapScreens"""", """"kind":"OpenApp","package":7,"screen":"TOP"""",
            ) to "package is not a string",
            swap.replace(
                """"kind":"SwapScreens"""", """"kind":"OpenApp","package":"nope","screen":"TOP"""",
            ) to "The app must be named by its package name (got 'nope')",
        )
    )

    @Test
    fun `an OpenApp round-trips with its package and screen`() {
        val open = swap.replace(
            """"kind":"SwapScreens"""",
            """"kind":"OpenApp","package":"org.dolphinemu.dolphinemu","screen":"FOCUSED"""",
        )
        val c = ok(file(open)).config
        assertEquals(
            Action.OpenApp("org.dolphinemu.dolphinemu", ScreenTarget.FOCUSED),
            c.shortcuts.single().action,
        )
        val written = ConfigCodec.encode(c)
        assertTrue("\"package\": \"org.dolphinemu.dolphinemu\"" in written, written)
        assertEquals(DecodeResult.Ok(c, emptyList(), null), ConfigCodec.decode(written))
    }

    @Test
    fun `an entry without a usable id is dropped with a null id`() {
        val blank = dropped(swap.replace(""""id":"swap"""", """"id":"  """"))
        assertNull(blank.id)
        assertEquals("A shortcut must have a name", blank.reason)
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
