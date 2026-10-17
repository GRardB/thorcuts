package com.grardb.thorcuts.platform.coordinator

import com.grardb.thorcuts.core.config.ConfigCodec
import com.grardb.thorcuts.core.config.ConfigStore.UpdateOutcome
import com.grardb.thorcuts.core.config.ThorcutsConfig
import com.grardb.thorcuts.core.helper.HelperMessage
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
import com.grardb.thorcuts.core.recorder.TriggerRecorder
import com.grardb.thorcuts.core.rules.RuleCompiler
import com.grardb.thorcuts.platform.config.MemoryConfigFile
import com.grardb.thorcuts.platform.helper.FakeChannel
import com.grardb.thorcuts.platform.helper.HelperChannel
import com.grardb.thorcuts.platform.helper.HelperSupervisor
import com.grardb.thorcuts.platform.log.RecordingLogger
import com.grardb.thorcuts.platform.privilege.FakeGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The holder's bookkeeping on the host: what the switch, a Ready, an ack, a fire and a loss
 * do to the store, the channel, the keeper and the state. Both the privileged scope and the
 * store run Unconfined, so every call runs inline on the test thread, in order, and the
 * test reads plain lists afterwards (as ConfigStoreTest does); the real threads are the
 * device run's. An observation made inside a channel hook is recorded and asserted after:
 * a throw inside a launched body never reaches the test.
 */
class ThorcutsCoordinatorTest {

    /** BTN_NORTH — the X button on the Thor's pad. */
    private val x = Control(Device.PAD, EvdevType.KEY, 307, 1)

    /** M1's rule, HOLD 400 · AYN + X → swap the screens: the shipped default until M5 emptied
     *  it, so the file these tests start from carries it explicitly (one global rule). The
     *  construction test alone starts with no file and sees the empty default written. */
    private val swap = Shortcut(
        id = "swap-screens",
        trigger = Trigger(setOf(ModifierKey.AYN), setOf(x), TriggerStyle.HOLD, holdMs = 400),
        action = Action.SwapScreens,
    )

    private val file = MemoryConfigFile(ConfigCodec.encode(ThorcutsConfig(listOf(swap))))
    private val switch = FakeSwitch()
    private val held = mutableListOf<Boolean>()
    private val log = RecordingLogger()
    private lateinit var channel: FakeChannel

    /** The relaunch chain's clock, in milliseconds; a test moves it. */
    private var now = 0L

    private val swapOff: (com.grardb.thorcuts.core.config.ThorcutsConfig) ->
        com.grardb.thorcuts.core.config.ThorcutsConfig =
        { it.withEnabled("swap-screens", false) }

    private fun coordinator(gateway: FakeGateway = FakeGateway()) = ThorcutsCoordinator(
        gateway = gateway,
        channel = { onMessage -> FakeChannel(onMessage).also { channel = it } },
        file = file,
        switch = switch,
        keeper = { held += it },
        thorcutsMain = "com.grardb.thorcuts/.MainActivity",
        homePackages = { emptySet() },
        log = log,
        privileged = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        storeDispatcher = Dispatchers.Unconfined,
        clock = { now },
    )

    // ── construction and the switch ───────────────────────────────────────────────────

    @Test
    fun `construction loads the config once and reads the switch, starting nothing`() {
        switch.on = true
        file.text = null                                        // a first run: no file yet
        val c = coordinator()
        assertEquals(1, file.writes.size)                       // the default, written once
        assertEquals("config: 0 shortcut(s), default written", c.state.value.configState)
        assertTrue(c.config.value.shortcuts.isEmpty())          // the EMPTY default (M5)
        assertTrue(c.state.value.enabled)
        assertFalse(c.state.value.running)
        assertTrue(channel.calls.isEmpty())
        assertTrue(held.isEmpty())
        assertTrue(log.has("CONFIG_LOAD created=true shortcuts=0 dropped=0"))
    }

    @Test
    fun `ensureRunning with the switch off starts nothing and holds nothing`() {
        val c = coordinator()
        c.ensureRunning()
        assertTrue(channel.calls.isEmpty())
        assertTrue(held.isEmpty())
        assertFalse(c.state.value.running)
        assertTrue(log.has("ENSURE enabled=false running=false -> noop"))
    }

    @Test
    fun `ensureRunning with the switch on holds and starts exactly once`() {
        switch.on = true
        val c = coordinator()
        c.ensureRunning()
        c.ensureRunning()
        assertEquals(listOf("start"), channel.calls)
        assertEquals(listOf(true), held)
        assertTrue(c.state.value.running)
        assertNull(c.state.value.transition)
        assertTrue(log.has("ENSURE enabled=true running=false -> start"))
        assertTrue(log.has("ENSURE enabled=true running=true -> noop"))
        assertTrue(log.has("HELPER_START outcome=STARTED"))
    }

    @Test
    fun `setEnabled on persists, holds and starts, and off stops, releases and persists`() {
        val c = coordinator()
        c.setEnabled(true)
        assertEquals(listOf(true), switch.writes)
        assertEquals(listOf("start"), channel.calls)
        assertEquals(listOf(true), held)
        assertTrue(c.state.value.enabled)
        assertTrue(c.state.value.running)
        assertTrue(log.has("SWITCH on=true by=ui"))

        c.setEnabled(false)
        assertEquals(listOf(true, false), switch.writes)
        assertEquals(listOf("start", "stop"), channel.calls)
        assertEquals(listOf(true, false), held)
        assertFalse(c.state.value.enabled)
        assertFalse(c.state.value.running)
        assertNull(c.state.value.transition)
        assertTrue(log.has("HELPER_STOP done"))

        // The store outlives the helper: a write with none live persists and pushes nothing.
        assertIs<UpdateOutcome.Applied>(runBlocking { c.update(swapOff) })
        assertEquals(1, file.writes.size)                       // the seeded file was read
        assertTrue(channel.pushed.isEmpty())
    }

    @Test
    fun `a start that cannot bind releases and says so, leaving the switch on`() {
        val c = coordinator()
        channel.outcome = HelperChannel.Start.NOT_BOUND
        c.setEnabled(true)
        assertFalse(c.state.value.running)
        assertEquals("could not bind the helper socket", c.state.value.helperState)
        assertEquals(listOf(true, false), held)
        assertTrue(c.state.value.enabled)
        assertNull(c.state.value.transition)
        assertTrue(log.has("HELPER_START outcome=NOT_BOUND"))
    }

    @Test
    fun `a start that finds the previous session still winding down is not a start`() {
        val c = coordinator()
        channel.outcome = HelperChannel.Start.ALREADY_RUNNING
        c.setEnabled(true)
        assertFalse(c.state.value.running)
        assertEquals(
            "the previous helper session was still winding down; start again",
            c.state.value.helperState,
        )
        assertEquals(listOf(true, false), held)
        assertTrue(c.state.value.enabled)
        assertNull(c.state.value.transition)
        assertTrue(log.has("HELPER_START outcome=ALREADY_RUNNING"))
        channel.outcome = HelperChannel.Start.STARTED
        c.ensureRunning()                                       // the next open starts again
        assertEquals(listOf("start", "start"), channel.calls)
        assertEquals(listOf(true, false, true), held)
        assertTrue(c.state.value.running)
    }

    @Test
    fun `a helper lost during the start is relaunched by a second attempt`() {
        val c = coordinator()
        var starts = 0
        channel.onStart = {
            if (++starts == 1) channel.emit(HelperMessage.Log(HelperSupervisor.DISCONNECTED))
        }
        c.setEnabled(true)
        assertEquals(listOf("start", "start"), channel.calls)
        assertTrue(c.state.value.running)
        assertFalse(c.state.value.helperLive)
        assertEquals("helper disconnected", c.state.value.helperState)
        assertEquals(listOf(true), held)                        // never released
        assertNull(c.state.value.transition)
        assertTrue(log.has("RELAUNCH after helper disconnected"))
    }

    @Test
    fun `the transition is owned while a start blocks, and a tap during it is ignored`() {
        val c = coordinator()
        var seenTransition: String? = null
        var seenRunning = false
        channel.onStart = {
            seenTransition = c.state.value.transition
            seenRunning = c.state.value.running
            c.setEnabled(false)                     // the button is disabled on a transition;
        }                                           // any other caller gets a line
        c.setEnabled(true)
        assertEquals("starting…", seenTransition)
        assertTrue(seenRunning)
        assertTrue(c.state.value.running)
        assertTrue(c.state.value.enabled)
        assertNull(c.state.value.transition)
        assertEquals(listOf("start"), channel.calls)            // no stop was queued
        assertEquals(listOf(true), switch.writes)
        assertTrue(log.has("SWITCH on=false ignored: starting…"))
    }

    @Test
    fun `a helper that exits during a requested stop is released once, by the stop`() {
        val c = coordinator()
        c.setEnabled(true)
        channel.onStop = { channel.emit(HelperMessage.Exiting("stop requested")) }
        c.setEnabled(false)
        assertEquals(listOf(true, false), held)
        assertFalse(c.state.value.running)
        assertEquals("exited: stop requested", c.state.value.helperState)
        assertNull(c.state.value.transition)
        assertTrue(log.has("HELPER_STOP done"))
        assertEquals(listOf("start", "stop"), channel.calls)    // no relaunch: the stop owns it
        assertFalse(log.has("RELAUNCH"))
    }

    // ── the helper's messages ─────────────────────────────────────────────────────────

    @Test
    fun `Ready pushes the loaded config, and an update with the helper live pushes the next`() {
        val c = coordinator()
        c.setEnabled(true)
        channel.emit(HelperMessage.Ready(3))
        assertEquals("ready, 3 devices", c.state.value.helperState)
        assertEquals(listOf(1), channel.pushed.map { it.generation })
        assertEquals(listOf(1), channel.pushed.map { it.ruleCount })
        channel.emit(HelperMessage.TableAccepted(1, 1, installed = true))
        assertEquals("table gen 1: 1 rule(s), installed", c.state.value.tableState)
        assertEquals("TABLE gen 1 ok, 1 rule(s), installed", c.state.value.eventLog.first())
        assertTrue(log.has("TABLE_ACK gen=1 rules=1 installed"))

        // F2's scenario through the holder: an edit reaches the helper that is live.
        assertIs<UpdateOutcome.Applied>(runBlocking { c.update(swapOff) })
        assertEquals(listOf(1, 2), channel.pushed.map { it.generation })
        assertEquals(0, channel.pushed.last().ruleCount)
        assertEquals("config: 1 shortcut(s), written", c.state.value.configState)
    }

    @Test
    fun `a fire dispatches through the gateway and records the outcome`() {
        val c = coordinator(FakeGateway("OK swapped 12@0 <-> 7@4"))
        c.setEnabled(true)
        channel.emit(HelperMessage.Ready(1))
        channel.emit(HelperMessage.Fired(1, 0))
        assertEquals("OK swapped 12@0 <-> 7@4 (swap-screens)", c.state.value.lastOutcome)
        assertEquals(
            "FIRED gen 1 #0 swap-screens -> OK swapped 12@0 <-> 7@4",
            c.state.value.eventLog.first(),
        )
        assertTrue(log.has("FIRED gen=1 index=0 shortcut=swap-screens"))
        val outcome = "FIRE_OUTCOME gen=1 index=0 shortcut=swap-screens " +
            "outcome=OK swapped 12@0 <-> 7@4"
        assertTrue(log.has(outcome))
    }

    @Test
    fun `run records the Swap button's outcome the same way`() {
        val c = coordinator(FakeGateway("ERR no app to swap on display 0 or 4"))
        assertEquals("ERR no app to swap on display 0 or 4", c.run(Action.SwapScreens, "swap"))
        assertEquals("ERR no app to swap on display 0 or 4 (swap)", c.state.value.lastOutcome)
    }

    @Test
    fun `the panic combo turns the switch off and releases, and nothing restarts it`() {
        val c = coordinator()
        c.setEnabled(true)
        channel.emit(HelperMessage.Ready(1))
        channel.emit(HelperMessage.Exiting("panic combo"))
        assertFalse(switch.on)
        assertEquals(listOf(true, false), switch.writes)
        assertFalse(c.state.value.enabled)
        assertFalse(c.state.value.running)
        assertEquals("exited: panic combo", c.state.value.helperState)
        assertEquals("no table (helper not running)", c.state.value.tableState)
        assertEquals(listOf(true, false), held)
        assertTrue(log.has("SWITCH on=false by=panic"))
        channel.emit(HelperMessage.Fired(1, 0))                 // the store cleared its tables
        assertTrue(log.has("FIRE_UNRESOLVED gen=1"))
        c.ensureRunning()                                       // off: nothing starts
        assertEquals(listOf("start"), channel.calls)
        assertFalse(log.has("RELAUNCH"))
    }

    @Test
    fun `a first unasked loss relaunches without releasing`() {
        val c = coordinator()
        c.setEnabled(true)
        channel.emit(HelperMessage.Ready(1))
        var seen: String? = null
        channel.onStart = { seen = c.state.value.transition }   // the relaunch's start
        channel.emit(HelperMessage.Exiting("signal"))
        assertTrue(switch.on)
        assertTrue(c.state.value.enabled)
        assertTrue(c.state.value.running)                       // the switch reads ON
        assertFalse(c.state.value.helperLive)
        assertEquals("exited: signal", c.state.value.helperState)
        assertEquals("no table (helper not running)", c.state.value.tableState)
        assertEquals(listOf(true), held)                        // never released
        assertEquals(listOf("start", "start"), channel.calls)
        assertEquals("restarting…", seen)
        assertNull(c.state.value.transition)
        assertTrue(log.has("RELAUNCH after exited: signal"))
        assertEquals("RELAUNCH after exited: signal", c.state.value.eventLog.first())
        c.ensureRunning()                                       // nothing to do: running
        assertEquals(listOf("start", "start"), channel.calls)
        channel.emit(HelperMessage.Ready(1))                    // the relaunched helper
        assertTrue(c.state.value.helperLive)
        assertEquals(2, channel.pushed.last().generation)       // a fresh generation
    }

    @Test
    fun `a second loss within a minute gives up - the switch goes off with the reason`() {
        val c = coordinator()
        c.setEnabled(true)
        now = 1_000
        channel.emit(HelperMessage.Exiting("signal"))          // relaunched
        now = 60_000
        channel.emit(HelperMessage.Exiting("signal"))          // 59 s later: the chain
        assertFalse(switch.on)
        assertEquals(listOf(true, false), switch.writes)
        assertFalse(c.state.value.enabled)
        assertFalse(c.state.value.running)
        assertFalse(c.state.value.helperLive)
        assertEquals("gave up: exited: signal, twice in a minute", c.state.value.helperState)
        assertEquals(listOf(true, false), held)
        assertEquals(listOf("start", "start"), channel.calls)
        assertNull(c.state.value.transition)
        assertTrue(log.has("RELAUNCH gave up: exited: signal"))
        assertTrue(log.has("SWITCH on=false by=gave-up"))
        assertEquals("GAVE UP: exited: signal", c.state.value.eventLog.first())
        val off = log.lines.indexOfFirst { "SWITCH on=false by=gave-up" in it }
        val release = log.lines.indexOfFirst { "KEEPER release" in it }
        assertTrue(off in 0 until release)                      // persisted before the release
        c.ensureRunning()                                       // off: nothing starts
        assertEquals(listOf("start", "start"), channel.calls)
    }

    @Test
    fun `a loss after a quiet minute is a new chain`() {
        val c = coordinator()
        c.setEnabled(true)
        now = 1_000
        channel.emit(HelperMessage.Exiting("signal"))
        now = 61_000                                            // exactly a minute later
        channel.emit(HelperMessage.Exiting("signal"))
        assertTrue(switch.on)
        assertTrue(c.state.value.running)
        assertEquals(listOf("start", "start", "start"), channel.calls)
        assertEquals(listOf(true), held)
    }

    @Test
    fun `a user-driven start resets the chain`() {
        val c = coordinator()
        c.setEnabled(true)
        now = 1_000
        channel.emit(HelperMessage.Exiting("signal"))          // relaunched
        now = 30_000
        channel.emit(HelperMessage.Exiting("signal"))          // gave up: off
        assertFalse(c.state.value.enabled)
        now = 31_000
        c.setEnabled(true)                                      // the user tries again
        assertEquals(listOf("start", "start", "start"), channel.calls)
        now = 32_000
        channel.emit(HelperMessage.Exiting("signal"))          // 2 s after the last loss
        assertTrue(c.state.value.enabled)                       // relaunched, not given up
        assertTrue(c.state.value.running)
        assertEquals(listOf("start", "start", "start", "start"), channel.calls)
        assertEquals(listOf(true, false, true), held)
    }

    @Test
    fun `a relaunch whose own start fails gives up`() {
        val c = coordinator()
        c.setEnabled(true)
        channel.outcome = HelperChannel.Start.NOT_BOUND         // the relaunch's start
        channel.emit(HelperMessage.Exiting("signal"))
        assertEquals(listOf("start", "start"), channel.calls)
        assertFalse(switch.on)
        assertFalse(c.state.value.enabled)
        assertFalse(c.state.value.running)
        assertEquals("gave up: could not bind the helper socket", c.state.value.helperState)
        assertEquals(listOf(true, false), held)
        assertNull(c.state.value.transition)
        assertTrue(log.has("RELAUNCH gave up: could not bind the helper socket"))
        assertTrue(log.has("SWITCH on=false by=gave-up"))
        val off = log.lines.indexOfFirst { "SWITCH on=false by=gave-up" in it }
        val release = log.lines.indexOfFirst { "KEEPER release" in it }
        assertTrue(off in 0 until release)                      // persisted before the release
    }

    @Test
    fun `a loss that finds nothing running releases and does not relaunch`() {
        val c = coordinator()
        channel.outcome = HelperChannel.Start.ALREADY_RUNNING
        c.setEnabled(true)                                      // the start gave up waiting
        assertFalse(c.state.value.running)
        assertEquals(listOf(true, false), held)
        channel.emit(HelperMessage.Log(HelperSupervisor.DISCONNECTED))  // the old session's
        assertFalse(c.state.value.running)
        assertTrue(c.state.value.enabled)
        assertEquals("helper disconnected", c.state.value.helperState)
        assertEquals(listOf("start"), channel.calls)            // no relaunch
        assertEquals(listOf(true, false, false), held)          // released again, harmlessly
        assertFalse(log.has("RELAUNCH"))
        assertNull(c.state.value.transition)
    }

    @Test
    fun `a loss is logged and shown`() {
        val c = coordinator()
        c.setEnabled(true)
        channel.emit(HelperMessage.Exiting("signal"))
        assertTrue(log.has("HELPER_LOST exited: signal"))
        assertEquals(
            listOf("RELAUNCH after exited: signal", "HELPER exited: signal"),
            c.state.value.eventLog.take(2),
        )
    }

    @Test
    fun `a rejected write is shown and logged`() {
        val c = coordinator()
        val outcome = runBlocking { c.update { throw IllegalArgumentException("nope") } }
        assertIs<UpdateOutcome.Rejected>(outcome)
        assertEquals("config: write rejected: nope", c.state.value.configState)
        assertTrue(log.has("CONFIG_WRITE_REJECTED nope"))
    }

    // ── the app in front (M4) ─────────────────────────────────────────────────────────

    private val dolphin = "org.dolphinemu.dolphinemu"

    /** A coordinator whose config is the swap (global) plus one shortcut only in Dolphin:
     *  the global list is one rule, Dolphin's is two. */
    private fun scopedCoordinator(): ThorcutsCoordinator {
        val onlyDolphin = swap.copy(
            id = "only-d",
            trigger = swap.trigger.copy(
                buttons = setOf(Control(Device.PAD, EvdevType.KEY, 304, 1)),
            ),
            scope = Scope.Apps(setOf(dolphin)),
        )
        file.text = ConfigCodec.encode(ThorcutsConfig(listOf(swap, onlyDolphin)))
        return coordinator()
    }

    @Test
    fun `foreground is logged once per change, shown, and pushes that app's list`() {
        val c = scopedCoordinator()
        c.setEnabled(true)
        channel.emit(HelperMessage.Ready(1))
        assertEquals(listOf(1), channel.pushed.map { it.ruleCount })    // global: one rule
        c.foreground(dolphin)
        c.foreground(dolphin)                                           // no change
        assertEquals(dolphin, c.state.value.foreground)
        assertEquals(1, log.lines.count { "FOREGROUND $dolphin" in it })
        assertEquals(listOf(1, 2), channel.pushed.map { it.ruleCount })
        c.foreground(null)
        assertTrue(log.has("FOREGROUND unknown"))
        assertEquals(listOf(1, 2, 1), channel.pushed.map { it.ruleCount })
    }

    @Test
    fun `monitor connected and disconnected are logged and shown`() {
        val c = coordinator()
        c.monitor(true)
        assertTrue(c.state.value.monitor)
        assertTrue(log.has("MONITOR connected"))
        c.monitor(false)
        assertFalse(c.state.value.monitor)
        assertTrue(log.has("MONITOR disconnected"))
    }

    // ── the branches no test exercised (H3-5) ─────────────────────────────────────────

    @Test
    fun `a Log line that is not the disconnect becomes the helper line`() {
        val c = coordinator()
        c.setEnabled(true)
        channel.emit(HelperMessage.Log("launching helper…"))
        assertEquals("launching helper…", c.state.value.helperState)
        assertTrue(c.state.value.running)
        assertEquals(listOf(true), held)
        assertEquals(listOf("start"), channel.calls)            // not a loss: no relaunch
        assertFalse(log.has("HELPER_LOST"))
    }

    @Test
    fun `a rejected table is shown, logged and rowed`() {
        val c = ready()
        channel.emit(HelperMessage.TableRejected(1, "bad rule"))
        assertEquals("table gen 1 REJECTED: bad rule", c.state.value.tableState)
        assertTrue(log.has("TABLE_REJECTED gen=1 reason=bad rule"))
        assertEquals("TABLE gen 1 REJECTED: bad rule", c.state.value.eventLog.first())
    }

    @Test
    fun `a config that does not compile is NOT PUSHED on Ready`() {
        // One over the table's cap: loads (nothing refuses it on disk), compiles on Ready
        // into a PushFailed. Codes clear of the five modifiers.
        val many = ThorcutsConfig((0..RuleCompiler.MAX_RULES).map { i ->
            Shortcut(
                id = "s$i",
                trigger = Trigger(
                    modifiers = setOf(ModifierKey.AYN),
                    buttons = setOf(Control(Device.PAD, EvdevType.KEY, 400 + i, 1)),
                ),
                action = Action.SwapScreens,
            )
        })
        file.text = ConfigCodec.encode(many)
        val c = coordinator()
        c.setEnabled(true)
        channel.emit(HelperMessage.Ready(1))
        assertTrue(channel.pushed.isEmpty())
        assertTrue(log.has("TABLE_PUSH_FAILED gen=1: 65 shortcuts apply here"))
        assertTrue(c.state.value.tableState.startsWith("table gen 1 NOT PUSHED: "))
        assertTrue(c.state.value.eventLog.first().startsWith("TABLE gen 1 NOT PUSHED: "))
    }

    @Test
    fun `an ack for a generation never pushed is logged as unexpected`() {
        val c = ready()                                         // gen 1 pushed
        channel.emit(HelperMessage.TableAccepted(99, 1, true))
        assertTrue(log.has("TABLE_ACK gen=99 matches no pushed table"))
    }

    @Test
    fun `a push that throws is a CONFIG_FAILURE, not a crash`() {
        val c = coordinator()
        c.setEnabled(true)
        channel.onPush = { throw IllegalStateException("socket") }
        channel.emit(HelperMessage.Ready(1))
        assertTrue(log.has("CONFIG_FAILURE: java.lang.IllegalStateException: socket"))
        assertTrue(c.state.value.eventLog.first().startsWith("CONFIG FAILURE: "))
        assertTrue(c.state.value.running)                       // the holder is unharmed
        assertTrue(c.state.value.helperLive)
        assertEquals(1, channel.pushed.size)                    // the table stays retained
    }

    @Test
    fun `an applied write is logged and shown as written`() {
        val c = coordinator()
        val outcome = runBlocking { c.update(swapOff) }
        assertIs<UpdateOutcome.Applied>(outcome)
        assertTrue(log.has("CONFIG_WRITE shortcuts=1"))
        assertEquals("config: 1 shortcut(s), written", c.state.value.configState)
    }

    // ── the recorder ──────────────────────────────────────────────────────────────────

    private fun key(device: Int, code: Int, value: Int) =
        HelperMessage.Event(device, EvdevType.KEY, code, value)

    /** A coordinator with the helper up and ready: the state Record is offered in. */
    private fun ready(): ThorcutsCoordinator {
        val c = coordinator()
        c.setEnabled(true)
        channel.emit(HelperMessage.Ready(1))
        return c
    }

    @Test
    fun `startRecording puts the helper in capture mode and stopRecording takes it out, once`() {
        val c = ready()
        assertNull(c.recording.value)
        c.startRecording()
        assertEquals(TriggerRecorder(), c.recording.value)
        assertEquals(listOf("start", "capture on"), channel.calls)
        assertTrue(log.has("CAPTURE on"))
        c.stopRecording()
        assertNull(c.recording.value)
        assertEquals(listOf("start", "capture on", "capture off"), channel.calls)
        assertTrue(log.has("CAPTURE off"))
        c.stopRecording()                                       // not recording: nothing sent
        assertEquals(listOf("start", "capture on", "capture off"), channel.calls)
    }

    @Test
    fun `events while recording become the trigger - AYN down, X down, X up, AYN up`() {
        val c = ready()
        c.startRecording()
        val before = c.state.value
        channel.emit(key(1, 194, 1))                            // AYN, on gpio-keys
        channel.emit(key(0, 307, 1))                            // X
        assertEquals(setOf(ModifierKey.AYN.control, x), c.recording.value!!.held)
        channel.emit(key(0, 307, 0))
        channel.emit(key(1, 194, 0))
        val r = assertNotNull(c.recording.value)
        assertTrue(r.complete)
        assertEquals(setOf(ModifierKey.AYN), r.modifiers)
        assertEquals(setOf(x), r.buttons)
        // The recording stays until the UI ends it; a raw event touches the recorder and
        // nothing else — not a row, not a line of the state.
        assertEquals(listOf("start", "capture on"), channel.calls)
        assertEquals(before, c.state.value)
    }

    @Test
    fun `an event while not recording changes nothing`() {
        val c = ready()
        val before = c.state.value
        channel.emit(key(0, 307, 1))
        assertNull(c.recording.value)
        assertEquals(before, c.state.value)
    }

    @Test
    fun `helperLive follows Ready and the loss, and a Ready mid-recording re-sends capture`() {
        val c = coordinator()
        assertFalse(c.state.value.helperLive)
        c.setEnabled(true)
        assertFalse(c.state.value.helperLive)                   // started, not yet ready
        channel.emit(HelperMessage.Ready(1))
        assertTrue(c.state.value.helperLive)
        c.startRecording()
        channel.emit(HelperMessage.Ready(1))                    // a session up mid-recording
        assertEquals(listOf("start", "capture on", "capture on"), channel.calls)
        assertTrue(c.state.value.helperLive)
        channel.emit(HelperMessage.Log(HelperSupervisor.DISCONNECTED))
        assertFalse(c.state.value.helperLive)
    }

    @Test
    fun `a loss clears the recording with no send, by exit and by disconnect`() {
        val losses = listOf(
            HelperMessage.Exiting("signal"),
            HelperMessage.Log(HelperSupervisor.DISCONNECTED),
        )
        for (loss in losses) {
            val c = ready()
            c.startRecording()
            channel.emit(loss)
            assertNull(c.recording.value)
            assertFalse(c.state.value.helperLive)
            // The loss relaunches (a second start) and sends no "capture off".
            assertEquals(listOf("start", "capture on", "start"), channel.calls)
            c.stopRecording()                                   // the UI's backstop: nothing
            assertEquals(listOf("start", "capture on", "start"), channel.calls)
            channel.emit(HelperMessage.Ready(1))                // the relaunched helper
            assertTrue(c.state.value.helperLive)                // Record is offered again
            assertEquals(listOf("start", "capture on", "start"), channel.calls)  // no re-send
        }
    }

    @Test
    fun `startRecording with the helper not live is ignored`() {
        val c = ready()
        channel.emit(HelperMessage.Exiting("signal"))          // relaunched, not yet ready
        c.startRecording()
        assertNull(c.recording.value)
        assertEquals(listOf("start", "start"), channel.calls)   // no "capture on"
        assertTrue(log.has("CAPTURE on ignored: helper not live"))
        channel.emit(HelperMessage.Ready(1))
        c.startRecording()                                      // live again: a recording
        assertEquals(TriggerRecorder(), c.recording.value)
        assertEquals(listOf("start", "start", "capture on"), channel.calls)
    }

    // ── import and export ─────────────────────────────────────────────────────────────

    /** BTN_WEST — the Y button; BTN_EAST — the B button. */
    private val y = Control(Device.PAD, EvdevType.KEY, 308, 1)
    private val b = Control(Device.PAD, EvdevType.KEY, 305, 1)

    /** AYN + Y, a tap → go home on the focused screen: a trigger the seeded file lacks. */
    private val home = Shortcut(
        id = "home",
        trigger = Trigger(setOf(ModifierKey.AYN), setOf(y)),
        action = Action.GoHome(ScreenTarget.FOCUSED),
    )

    /**
     * The merge test's import file, in this order: `home` is new; `swap-screens` repeats a
     * PRESENT id (its trigger here, AYN + B, repeats nothing in the file, so the codec accepts
     * every entry and the skip is the merge's, not a drop); `swap2` carries the CURRENT swap's
     * trigger under a new id; `bad` names an action kind that does not exist — the model would
     * refuse to build it, so it is spliced in as text, last.
     */
    private val importFile: String
        get() {
            val present = swap.copy(trigger = Trigger(setOf(ModifierKey.AYN), setOf(b)))
            val sameTrigger = swap.copy(id = "swap2")
            val valid = ConfigCodec.encode(ThorcutsConfig(listOf(home, present, sameTrigger)))
            val bad = """{"id":"bad","trigger":{"modifiers":["AYN"],"style":"PRESS"},""" +
                """"action":{"kind":"Teleport"}}"""
            return valid.replace("\n  ]\n}", ",\n$bad\n  ]\n}")
        }

    @Test
    fun `importFrom merges, reports every outcome with its reason, and pushes`() {
        val c = coordinator()
        c.setEnabled(true)
        channel.emit(HelperMessage.Ready(2))
        assertEquals(listOf(1), channel.pushed.map { it.ruleCount })
        runBlocking { c.importFrom { importFile } }
        val line = "config: 2 shortcut(s), imported: 1 added, 2 skipped (swap-screens: id " +
            "already present; swap2: 'swap2' repeats the trigger of 'swap-screens' " +
            "everywhere), 1 dropped (#3 bad: unknown action kind 'Teleport')"
        assertEquals(line, c.state.value.configState)
        assertEquals(listOf("swap-screens", "home"), c.config.value.shortcuts.map { it.id })
        assertEquals(home, c.config.value.shortcut("home"))
        assertEquals(ConfigCodec.encode(c.config.value), file.text)
        assertEquals(listOf(1, 2), channel.pushed.map { it.ruleCount })   // the merged list
        assertTrue(log.has("CONFIG_IMPORT added=1 skipped=2 dropped=1"))
    }

    @Test
    fun `an import with nothing skipped or dropped reports plain counts`() {
        val c = coordinator()
        val only = swap.copy(id = "b-only", trigger = Trigger(setOf(ModifierKey.AYN), setOf(b)))
        runBlocking { c.importFrom { ConfigCodec.encode(ThorcutsConfig(listOf(only))) } }
        assertEquals(
            "config: 2 shortcut(s), imported: 1 added, 0 skipped, 0 dropped",
            c.state.value.configState,
        )
        assertEquals(listOf("swap-screens", "b-only"), c.config.value.shortcuts.map { it.id })
    }

    @Test
    fun `an import that fails to decode writes nothing and says why`() {
        val c = coordinator()
        val before = file.text
        // "{" is what the parser refuses; a bare word is a lenient literal, "not an object".
        runBlocking { c.importFrom { "{" } }
        assertTrue(c.state.value.configState.startsWith("config: import failed: not JSON:"))
        assertEquals(before, file.text)
        assertTrue(file.writes.isEmpty())
        assertTrue(log.has("CONFIG_IMPORT_FAILED not JSON:"))
    }

    @Test
    fun `an import whose reader throws is reported, and nothing is written`() {
        val c = coordinator()
        runBlocking { c.importFrom { throw IOException("gone") } }
        assertEquals("config: import failed: could not read: gone", c.state.value.configState)
        assertTrue(file.writes.isEmpty())
        assertTrue(log.has("CONFIG_IMPORT_FAILED could not read: gone"))
    }

    @Test
    fun `exportTo hands the encoded config to the writer and says so`() {
        val c = coordinator()
        var written: String? = null
        c.exportTo { written = it }
        assertEquals(ConfigCodec.encode(ThorcutsConfig(listOf(swap))), written)
        assertEquals("config: exported 1 shortcut(s)", c.state.value.configState)
        assertTrue(log.has("CONFIG_EXPORT shortcuts=1"))
    }

    @Test
    fun `an export whose writer throws is reported, not thrown`() {
        val c = coordinator()
        c.exportTo { throw IOException("disk full") }
        assertEquals("config: export failed: disk full", c.state.value.configState)
        assertTrue(log.has("CONFIG_EXPORT_FAILED disk full"))
    }
}
