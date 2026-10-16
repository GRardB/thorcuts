package com.grardb.thorcuts.core.config

import com.grardb.thorcuts.core.config.ConfigStore.Event
import com.grardb.thorcuts.core.config.ConfigStore.UpdateOutcome
import com.grardb.thorcuts.core.model.Scope
import com.grardb.thorcuts.core.rules.CompiledTable
import com.grardb.thorcuts.core.rules.FireResolution
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfigStoreTest {

    private val file = InMemoryConfigFile()
    private val pushed = mutableListOf<CompiledTable>()
    private val events = mutableListOf<Event>()

    /** Unconfined: every call runs inline on the test thread, in order, so the test reads
     *  plain lists afterwards. The real dispatcher gets its own test at the end. */
    private fun store(default: ThorcutsConfig = Defaults.CONFIG) =
        ConfigStore(file, pushed::add, events::add, default, Dispatchers.Unconfined)

    private inline fun <reified E : Event> only(): E = events.filterIsInstance<E>().single()
    private fun pushedGens() = pushed.map { it.generation }
    private val defaultText = configFixture("default.json")
    private val swapOff: (ThorcutsConfig) -> ThorcutsConfig =
        { it.withEnabled("swap-screens", false) }
    private val swapOn: (ThorcutsConfig) -> ThorcutsConfig =
        { it.withEnabled("swap-screens", true) }

    /** [n] distinct global shortcuts, each on its own button. */
    private fun many(n: Int) = ThorcutsConfig((0 until n).map { i ->
        Defaults.SWAP_SCREENS.copy(
            id = "s$i",
            trigger = Defaults.SWAP_SCREENS.trigger.copy(buttons = setOf(padKey(512 + i))),
        )
    })

    // ── load ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `a first run writes the default and reports created`() {
        val s = store()
        assertTrue(s.config.value.shortcuts.isEmpty())
        runBlocking { s.load() }
        assertEquals(listOf(defaultText), file.writes)
        assertEquals(Defaults.CONFIG, s.config.value)
        val loaded = Event.Loaded(Defaults.CONFIG, true, emptyList(), null, null)
        assertEquals(loaded, only<Event.Loaded>())
        assertTrue(pushed.isEmpty())
    }

    @Test
    fun `a second load reads nothing, writes nothing and reports nothing`() {
        val s = store()
        runBlocking { s.load() }
        file.text = null                       // as if the file had vanished: a naive reload
        runBlocking { s.load() }               // would write the default a second time
        assertEquals(1, file.writes.size)
        assertEquals(1, events.size)
        assertEquals(Defaults.CONFIG, s.config.value)
    }

    @Test
    fun `a default that cannot be written is reported with the reason`() {
        file.writeFailure = IOException("ENOSPC")
        val s = store()
        runBlocking { s.load() }
        val loaded = only<Event.Loaded>()
        assertFalse(loaded.created)
        assertEquals("could not write the default: ENOSPC", loaded.failure)
        assertEquals(Defaults.CONFIG, s.config.value)   // memory is published either way
        assertTrue(file.writes.isEmpty())
    }

    @Test
    fun `a readable file loads without writing`() {
        file.text = configFixture("kitchen-sink.json")
        val s = store()
        runBlocking { s.load() }
        assertTrue(file.writes.isEmpty())
        assertEquals(KITCHEN_SINK, s.config.value)
        val loaded = Event.Loaded(KITCHEN_SINK, false, emptyList(), null, null)
        assertEquals(loaded, only<Event.Loaded>())
    }

    @Test
    fun `a file with a bad entry loads the rest, reports the drop, and is not rewritten`() {
        val swap = """"action":{"kind":"SwapScreens"}"""
        val text = """{"schemaVersion":1,"shortcuts":[""" +
            """{"id":"bad","trigger":{"modifiers":[],"style":"PRESS"},$swap},""" +
            """{"id":"ok","trigger":{"modifiers":["AYN"],"style":"PRESS"},$swap}]}"""
        file.text = text
        val s = store()
        runBlocking { s.load() }
        assertEquals(listOf("ok"), s.config.value.shortcuts.map { it.id })
        val loaded = only<Event.Loaded>()
        assertFalse(loaded.created)
        assertEquals(listOf(0), loaded.dropped.map { it.index })
        assertEquals("bad", loaded.dropped.single().id)
        assertTrue(file.writes.isEmpty())
        assertEquals(text, file.text)
    }

    @Test
    fun `a corrupt file is set aside and the default written in its place`() {
        file.text = "nope"
        val s = store()
        runBlocking { s.load() }
        assertEquals(listOf("nope"), file.setAsides)
        assertEquals(listOf(defaultText), file.writes)
        assertEquals(defaultText, file.text)
        assertEquals(Defaults.CONFIG, s.config.value)
        val loaded = only<Event.Loaded>()
        assertTrue(loaded.created)
        assertEquals("config.json.bad-1", loaded.setAside)
        assertTrue(loaded.failure != null && "not an object" in loaded.failure, loaded.failure)
    }

    @Test
    fun `a file from a newer schema is set aside, never overwritten in place`() {
        val newer = """{"schemaVersion":2,"shortcuts":[]}"""
        file.text = newer
        runBlocking { store().load() }
        assertEquals(listOf(newer), file.setAsides)
        val loaded = only<Event.Loaded>()
        assertEquals("config.json.bad-1", loaded.setAside)
        assertTrue("newer" in loaded.failure!!, loaded.failure)
    }

    @Test
    fun `when the file cannot be set aside nothing is written and memory alone changes`() {
        val stuck = object : InMemoryConfigFile("nope") {
            override fun setAside(): String? = throw IOException("read-only")
        }
        val s = ConfigStore(stuck, pushed::add, events::add, dispatcher = Dispatchers.Unconfined)
        runBlocking { s.load() }
        assertEquals("nope", stuck.text)
        assertTrue(stuck.writes.isEmpty())
        assertEquals(Defaults.CONFIG, s.config.value)
        val loaded = only<Event.Loaded>()
        assertFalse(loaded.created)
        assertNull(loaded.setAside)
        assertTrue("could not set the file aside: read-only" in loaded.failure!!, loaded.failure)
    }

    @Test
    fun `an unreadable file counts as unusable`() {
        file.text = defaultText
        file.readFailure = IOException("EIO")
        runBlocking { store().load() }
        val loaded = only<Event.Loaded>()
        assertTrue("could not read: EIO" in loaded.failure!!, loaded.failure)
        assertEquals("config.json.bad-1", loaded.setAside)
    }

    // ── update ────────────────────────────────────────────────────────────────────────

    @Test
    fun `an update before load is rejected and touches nothing`() {
        file.text = configFixture("kitchen-sink.json")
        val s = store()
        val outcome = runBlocking { s.update(swapOff) }
        assertEquals(UpdateOutcome.Rejected("config not loaded"), outcome)
        assertTrue(file.writes.isEmpty())
        assertTrue(events.isEmpty())
        runBlocking { s.load() }
        assertEquals(KITCHEN_SINK, s.config.value)
    }

    @Test
    fun `update writes, publishes, then pushes at the next generation while the helper is live`() {
        val s = store()
        runBlocking { s.load() }
        s.helperReady()
        assertEquals(listOf(1), pushedGens())
        val outcome = runBlocking { s.update(swapOff) }
        val expected = UpdateOutcome.Applied(Defaults.CONFIG.withEnabled("swap-screens", false))
        assertEquals(expected, outcome)
        assertFalse(s.config.value.shortcut("swap-screens")!!.enabled)
        assertTrue("\"enabled\": false" in file.text!!)
        assertEquals(listOf(1, 2), pushedGens())
        assertEquals(listOf(1, 0), pushed.map { it.ruleCount })
        // Written lands before the push it caused.
        assertEquals(
            listOf("Loaded", "TablePushed", "Written", "TablePushed"),
            events.map { it::class.simpleName },
        )
    }

    @Test
    fun `update while no helper is live persists and publishes but pushes nothing`() {
        val s = store()
        runBlocking { s.load() }
        assertIs<UpdateOutcome.Applied>(runBlocking { s.update(swapOff) })
        assertTrue(pushed.isEmpty())
        assertEquals(listOf("Loaded", "Written"), events.map { it::class.simpleName })
        // The next Ready pushes the NEW config at the first generation.
        s.helperReady()
        assertEquals(listOf(1), pushedGens())
        assertEquals(0, pushed.single().ruleCount)
    }

    @Test
    fun `a rejected update changes neither file, memory, nor generation`() {
        val s = store()
        runBlocking { s.load() }
        s.helperReady()
        val before = s.config.value
        fun assertNothingChanged() {
            assertEquals(before, s.config.value)
            assertEquals(defaultText, file.text)
            assertEquals(listOf(1), pushedGens())
            assertTrue(events.none { it is Event.Written })
        }
        // (a) the compiler refuses: 65 global rules
        val tooMany = assertIs<UpdateOutcome.Rejected>(runBlocking { s.update { many(65) } })
        assertTrue("64" in tooMany.reason, tooMany.reason)
        assertNothingChanged()
        // (b) the edit itself throws
        val boom = assertIs<UpdateOutcome.Rejected>(
            runBlocking { s.update { throw IllegalArgumentException("boom") } }
        )
        assertEquals("boom", boom.reason)
        assertNothingChanged()
        // (c) the write fails
        file.writeFailure = IOException("ENOSPC")
        val disk = assertIs<UpdateOutcome.Rejected>(runBlocking { s.update(swapOff) })
        assertEquals("could not write: ENOSPC", disk.reason)
        assertNothingChanged()
        file.writeFailure = null
        // and the next write still lands at generation 2
        assertIs<UpdateOutcome.Applied>(runBlocking { s.update(swapOff) })
        assertEquals(listOf(1, 2), pushedGens())
    }

    // ── the helper side ───────────────────────────────────────────────────────────────

    @Test
    fun `every Ready pushes the current config at the next generation`() {
        val s = store()
        runBlocking { s.load() }
        s.helperReady()
        s.helperReady()
        assertEquals(listOf(1, 2), pushedGens())
        assertEquals(listOf(Defaults.SWAP_SCREENS), pushed.last().shortcuts)
    }

    @Test
    fun `a config over the limit on disk loads, and Ready reports the push failure`() {
        file.text = ConfigCodec.encode(many(65))
        val s = store()
        runBlocking { s.load() }
        assertEquals(65, s.config.value.shortcuts.size)
        s.helperReady()
        assertTrue(pushed.isEmpty())
        val failed = only<Event.PushFailed>()
        assertEquals(1, failed.generation)
        assertTrue("64" in failed.reason, failed.reason)
        // A write that brings it under the limit pushes at the NEXT generation: 1 was consumed.
        val trimmed = runBlocking { s.update { c -> ThorcutsConfig(c.shortcuts.take(64)) } }
        assertIs<UpdateOutcome.Applied>(trimmed)
        assertEquals(listOf(2), pushedGens())
    }

    @Test
    fun `an installed ack prunes older tables and a deferred ack keeps them`() {
        val s = store()
        runBlocking { s.load() }
        repeat(3) { s.helperReady() }
        assertEquals(listOf(1, 2, 3), pushedGens())
        s.tableAccepted(2, installed = false)
        s.fired(1, 0)
        assertIs<FireResolution.Hit>(only<Event.Fire>().resolution)
        events.clear()
        s.tableAccepted(2, installed = true)
        s.fired(1, 0)
        assertEquals(FireResolution.Stale(1), only<Event.Fire>().resolution)
        events.clear()
        s.fired(2, 0)
        assertIs<FireResolution.Hit>(only<Event.Fire>().resolution)
        events.clear()
        s.fired(3, 0)
        assertIs<FireResolution.Hit>(only<Event.Fire>().resolution)
        assertTrue(events.none { it is Event.UnexpectedAck })
    }

    @Test
    fun `an ack for a generation never pushed is reported and prunes nothing`() {
        val s = store()
        runBlocking { s.load() }
        s.helperReady()
        s.tableAccepted(7, installed = true)
        assertEquals(Event.UnexpectedAck(7), only<Event.UnexpectedAck>())
        s.fired(1, 0)
        assertIs<FireResolution.Hit>(only<Event.Fire>().resolution)
    }

    @Test
    fun `an ack for a table the retention cap dropped is real and changes nothing`() {
        val s = store()
        runBlocking { s.load() }
        repeat(9) { s.helperReady() }          // gens 1..9; the cap keeps 2..9
        s.tableAccepted(1, installed = true)   // pushed once, dropped since: not a fault
        assertTrue(events.none { it is Event.UnexpectedAck })
        s.fired(2, 0)
        assertIs<FireResolution.Hit>(only<Event.Fire>().resolution)   // nothing pruned
        events.clear()
        s.tableAccepted(10, installed = true)  // above the counter: never pushed
        assertEquals(Event.UnexpectedAck(10), only<Event.UnexpectedAck>())
    }

    @Test
    fun `an ack for a generation a failed compile consumed is unexpected`() {
        file.text = ConfigCodec.encode(many(65))
        val s = store()
        runBlocking { s.load() }
        s.helperReady()                                   // gen 1 consumed, nothing sent
        assertEquals(1, only<Event.PushFailed>().generation)
        s.tableAccepted(1, installed = true)              // at the counter, never pushed
        assertEquals(Event.UnexpectedAck(1), only<Event.UnexpectedAck>())
        events.clear()
        runBlocking { s.update { c -> ThorcutsConfig(c.shortcuts.take(64)) } }   // gen 2 sent
        s.tableAccepted(1, installed = true)              // below the counter now: still a fault
        assertEquals(Event.UnexpectedAck(1), only<Event.UnexpectedAck>())
        s.fired(2, 0)
        assertIs<FireResolution.Hit>(only<Event.Fire>().resolution)
    }

    @Test
    fun `a fire resolves to a hit, out of range, or stale`() {
        val s = store()
        runBlocking { s.load() }
        s.helperReady()
        s.fired(1, 0)
        val hit = assertIs<FireResolution.Hit>(only<Event.Fire>().resolution)
        assertEquals("swap-screens", hit.shortcut.id)
        assertEquals(0, hit.index)
        assertEquals(1, hit.table.generation)
        events.clear()
        s.fired(1, 5)
        assertEquals(FireResolution.OutOfRange(1, 5, 1), only<Event.Fire>().resolution)
        events.clear()
        s.fired(9, 0)
        assertEquals(FireResolution.Stale(9), only<Event.Fire>().resolution)
    }

    @Test
    fun `a helper live before the load gets the loaded config pushed next`() {
        val s = store()
        s.helperReady()                                  // before load: the empty placeholder
        assertEquals(listOf(0), pushed.map { it.ruleCount })
        runBlocking { s.load() }
        assertEquals(listOf(1, 2), pushedGens())         // the file's config follows at once
        assertEquals(listOf(0, 1), pushed.map { it.ruleCount })
        assertEquals(
            listOf("TablePushed", "Loaded", "TablePushed"),
            events.map { it::class.simpleName },
        )
    }

    @Test
    fun `when the helper goes, retention is cleared and the counter is not`() {
        val s = store()
        runBlocking { s.load() }
        s.helperReady()
        s.helperGone()
        s.fired(1, 0)
        assertEquals(FireResolution.Stale(1), only<Event.Fire>().resolution)
        assertIs<UpdateOutcome.Applied>(runBlocking { s.update(swapOff) })
        assertEquals(listOf(1), pushedGens())          // not live: no push
        s.helperReady()
        assertEquals(listOf(1, 2), pushedGens())       // the counter carried on
        assertEquals(0, pushed.last().ruleCount)
    }

    @Test
    fun `an update that enables a parked duplicate is rejected, and nothing moves`() {
        val parked = Defaults.SWAP_SCREENS.copy(id = "parked", enabled = false)
        val s = store(default = ThorcutsConfig(listOf(Defaults.SWAP_SCREENS, parked)))
        runBlocking { s.load() }
        s.helperReady()
        val before = s.config.value
        val outcome = runBlocking { s.update { it.withEnabled("parked", true) } }
        val reason = "'parked' repeats the trigger of 'swap-screens' everywhere"
        assertEquals(UpdateOutcome.Rejected(reason), outcome)
        assertEquals(before, s.config.value)
        assertEquals(1, file.writes.size)          // the default at load; nothing since
        assertEquals(listOf(1), pushedGens())      // helperReady's push; nothing since
        assertTrue(events.none { it is Event.Written })
    }

    @Test
    fun `the default is whatever the store was given`() {
        val mine = Defaults.SWAP_SCREENS.copy(id = "mine", scope = Scope.Global(setOf(RETROARCH)))
        val custom = ThorcutsConfig(listOf(mine))
        val s = store(default = custom)
        runBlocking { s.load() }
        assertEquals(custom, s.config.value)
        assertEquals(ConfigCodec.encode(custom), file.text)
    }

    // ── failures are reported, never fatal ────────────────────────────────────────────

    @Test
    fun `a push that throws is reported as Failed and the store stays usable`() {
        val s = ConfigStore(
            file, push = { throw IllegalStateException("boom") }, onEvent = events::add,
            dispatcher = Dispatchers.Unconfined,
        )
        runBlocking { s.load() }
        s.helperReady()
        assertEquals("boom", only<Event.Failed>().error.message)
        assertTrue(events.none { it is Event.TablePushed })
        // The table was retained before the push threw, as a sent one would be.
        s.fired(1, 0)
        assertIs<FireResolution.Hit>(only<Event.Fire>().resolution)
        // The same from update's caller thread: Applied, plus a Failed, no throw.
        events.clear()
        assertIs<UpdateOutcome.Applied>(runBlocking { s.update(swapOff) })
        assertEquals("boom", only<Event.Failed>().error.message)
    }

    @Test
    fun `a throwing event handler inside a launched body is reported, not fatal`() {
        val s = ConfigStore(
            file, pushed::add,
            onEvent = { e ->
                events += e
                if (e is Event.TablePushed) throw IllegalStateException("handler boom")
            },
            dispatcher = Dispatchers.Unconfined,
        )
        runBlocking { s.load() }
        s.helperReady()
        assertEquals(
            listOf("Loaded", "TablePushed", "Failed"),
            events.map { it::class.simpleName },
        )
        assertEquals("handler boom", only<Event.Failed>().error.message)
        assertEquals(listOf(1), pushedGens())
    }

    // ── the app in front (M4) ─────────────────────────────────────────────────────────

    /** A shortcut on AYN + [code] that applies only in Dolphin. */
    private fun onlyDolphin(id: String, code: Int = 304) = Defaults.SWAP_SCREENS.copy(
        id = id,
        trigger = Defaults.SWAP_SCREENS.trigger.copy(buttons = setOf(padKey(code))),
        scope = Scope.Apps(setOf(DOLPHIN)),
    )

    /** The default's swap plus one shortcut only in Dolphin: one rule unscoped, two there. */
    private val scoped = ThorcutsConfig(listOf(Defaults.SWAP_SCREENS, onlyDolphin("only-d")))

    /** Sixty global shortcuts and five only in Dolphin: fits everywhere but there. */
    private val overInDolphin =
        ThorcutsConfig(many(60).shortcuts + (0 until 5).map { onlyDolphin("d$it", 600 + it) })

    @Test
    fun `foreground before Ready pushes nothing and consumes no generation`() {
        val s = store(default = scoped)
        runBlocking { s.load() }
        s.foreground(DOLPHIN)
        assertTrue(pushed.isEmpty())
        s.helperReady()
        assertEquals(listOf(1), pushedGens())                   // nothing was consumed
        assertEquals(listOf("swap-screens", "only-d"), pushed.single().shortcuts.map { it.id })
    }

    @Test
    fun `a foreground change with a live helper pushes that app's list`() {
        val s = store(default = scoped)
        runBlocking { s.load() }
        s.helperReady()                                         // in front: unknown
        assertEquals(listOf(1), pushed.map { it.ruleCount })
        s.foreground(DOLPHIN)
        assertEquals(listOf(1, 2), pushedGens())
        assertEquals(listOf(1, 2), pushed.map { it.ruleCount })
        s.foreground(null)                                      // unknown again: the global list
        assertEquals(listOf(1, 2, 1), pushed.map { it.ruleCount })
    }

    @Test
    fun `a foreground change whose list is the same sends nothing`() {
        val s = store(default = scoped)
        runBlocking { s.load() }
        s.helperReady()
        s.foreground(RETROARCH)                                 // unnamed: the global list again
        s.foreground(RETROARCH)                                 // and no change at all
        assertEquals(listOf(1), pushedGens())
        s.foreground(DOLPHIN)
        assertEquals(listOf(1, 2), pushedGens())                // the next real push is gen 2
    }

    @Test
    fun `a write that changes nothing selected sends nothing but reports written`() {
        val s = store(default = scoped)
        runBlocking { s.load() }
        s.helperReady()
        s.foreground(RETROARCH)
        val outcome = runBlocking { s.update { it.withEnabled("only-d", false) } }
        assertIs<UpdateOutcome.Applied>(outcome)
        assertEquals(2, file.writes.size)
        assertEquals(1, events.count { it is Event.Written })
        assertEquals(listOf(1), pushedGens())                   // not selected here: no push
        s.foreground(DOLPHIN)                                   // disabled: the same list there
        assertEquals(listOf(1), pushedGens())
    }

    @Test
    fun `a write is refused when a named app would overflow`() {
        val s = store(default = scoped)
        runBlocking { s.load() }
        s.helperReady()
        val more = (0 until 63).map { onlyDolphin("d$it", 512 + it) }
        val outcome = runBlocking { s.update { c -> ThorcutsConfig(c.shortcuts + more) } }
        val reason = "65 shortcuts apply in $DOLPHIN; the table holds at most 64"
        assertEquals(UpdateOutcome.Rejected(reason), outcome)
        assertEquals(1, file.writes.size)                       // the default at load; no more
        assertEquals(listOf(1), pushedGens())
    }

    @Test
    fun `an app in front whose list overflows keeps the helper's table and reports`() {
        file.text = ConfigCodec.encode(overInDolphin)           // only a file can hold one
        val s = store()
        runBlocking { s.load() }
        s.helperReady()
        assertEquals(listOf(60), pushed.map { it.ruleCount })
        s.foreground(DOLPHIN)
        val failed = only<Event.PushFailed>()
        assertEquals(2, failed.generation)
        assertEquals("65 shortcuts apply here; the table holds at most 64", failed.reason)
        assertEquals(listOf(1), pushedGens())                   // the helper keeps gen 1
        s.foreground(RETROARCH)                                 // the global list: what it holds
        assertEquals(listOf(1), pushedGens())
        s.tableAccepted(2, installed = true)                    // the failed one: a fault
        assertEquals(Event.UnexpectedAck(2), only<Event.UnexpectedAck>())
    }

    @Test
    fun `an installed ack below a later failed generation keeps that failure a fault`() {
        file.text = ConfigCodec.encode(overInDolphin)
        val s = store()
        runBlocking { s.load() }
        s.helperReady()                                         // gen 1 sent
        s.foreground(DOLPHIN)                                   // gen 2 consumed, nothing sent
        assertEquals(2, only<Event.PushFailed>().generation)
        s.tableAccepted(1, installed = true)                    // prunes nothing above it
        s.tableAccepted(2, installed = true)
        assertEquals(Event.UnexpectedAck(2), only<Event.UnexpectedAck>())
        s.fired(1, 0)
        assertIs<FireResolution.Hit>(only<Event.Fire>().resolution)
    }

    @Test
    fun `an installed ack prunes the failed generations below it`() {
        file.text = ConfigCodec.encode(many(65))
        val s = store()
        runBlocking { s.load() }
        s.helperReady()                                         // gen 1 consumed, nothing sent
        runBlocking { s.update { c -> ThorcutsConfig(c.shortcuts.take(64)) } }   // gen 2 sent
        events.clear()
        s.tableAccepted(2, installed = true)                    // 1 is settled
        s.tableAccepted(1, installed = true)
        assertTrue(events.none { it is Event.UnexpectedAck })
    }

    @Test
    fun `a loss clears sent, so a reconnect whose own first push fails still pushes`() {
        file.text = ConfigCodec.encode(overInDolphin)
        val s = store()
        runBlocking { s.load() }
        s.helperReady()                                   // gen 1: the global list, 60 rules
        s.foreground(DOLPHIN)                             // gen 2 overflows; the helper keeps 60
        s.helperGone()                                    // the loss clears sent
        s.helperReady()                                   // reconnect, Dolphin: gen 3 overflows
        assertEquals(listOf(60), pushed.map { it.ruleCount })       // fresh helper holds nothing
        assertEquals(
            listOf(2, 3),
            events.filterIsInstance<Event.PushFailed>().map { it.generation },
        )
        s.foreground(null)                                // back to the global list
        assertEquals(listOf(60, 60), pushed.map { it.ruleCount })   // pushed, not skipped
    }

    // ── the real dispatcher ───────────────────────────────────────────────────────────

    @Test
    fun `calls from different threads are applied in call order on the real dispatcher`() {
        val liveEvents = CopyOnWriteArrayList<Event>()
        val livePushed = CopyOnWriteArrayList<CompiledTable>()
        val s = ConfigStore(InMemoryConfigFile(), livePushed::add, liveEvents::add)
        runBlocking { s.load() }
        s.helperReady()                                   // gen 1, 1 rule
        runBlocking { s.update(swapOff) }                 // gen 2, 0 rules
        s.helperReady()                                   // gen 3, 0 rules
        runBlocking { s.update(swapOn) }                  // gen 4, 1 rule
        val deadline = System.currentTimeMillis() + 5_000
        while (livePushed.size < 4 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(listOf(1, 2, 3, 4), livePushed.map { it.generation })
        assertEquals(listOf(1, 0, 0, 1), livePushed.map { it.ruleCount })
        assertTrue(liveEvents.first() is Event.Loaded)
        assertTrue(Thread.currentThread().name != "thorcuts-config")
    }
}
