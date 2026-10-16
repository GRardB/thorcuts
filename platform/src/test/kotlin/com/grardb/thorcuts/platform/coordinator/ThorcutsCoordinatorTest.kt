package com.grardb.thorcuts.platform.coordinator

import com.grardb.thorcuts.core.config.ConfigStore.UpdateOutcome
import com.grardb.thorcuts.core.config.Defaults
import com.grardb.thorcuts.core.helper.HelperMessage
import com.grardb.thorcuts.core.model.Action
import com.grardb.thorcuts.core.model.EvdevType
import com.grardb.thorcuts.core.model.ModifierKey
import com.grardb.thorcuts.core.recorder.TriggerRecorder
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

    private val file = MemoryConfigFile()
    private val switch = FakeSwitch()
    private val held = mutableListOf<Boolean>()
    private val log = RecordingLogger()
    private lateinit var channel: FakeChannel

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
    )

    // ── construction and the switch ───────────────────────────────────────────────────

    @Test
    fun `construction loads the config once and reads the switch, starting nothing`() {
        switch.on = true
        val c = coordinator()
        assertEquals(1, file.writes.size)                       // the default, written once
        assertEquals("config: 1 shortcut(s), default written", c.state.value.configState)
        assertEquals("swap-screens", c.config.value.shortcuts.single().id)
        assertTrue(c.state.value.enabled)
        assertFalse(c.state.value.running)
        assertTrue(channel.calls.isEmpty())
        assertTrue(held.isEmpty())
        assertTrue(log.has("CONFIG_LOAD created=true shortcuts=1 dropped=0"))
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
        assertEquals(2, file.writes.size)
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
    }

    @Test
    fun `a helper lost during the start stays lost when the start returns`() {
        val c = coordinator()
        channel.onStart = { channel.emit(HelperMessage.Log(HelperSupervisor.DISCONNECTED)) }
        c.setEnabled(true)
        assertFalse(c.state.value.running)
        assertEquals("helper disconnected", c.state.value.helperState)
        assertEquals(listOf(true, false), held)
        assertNull(c.state.value.transition)
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
    }

    @Test
    fun `any other loss releases and leaves the switch on for the next start`() {
        val c = coordinator()
        c.setEnabled(true)
        channel.emit(HelperMessage.Exiting("signal"))
        assertTrue(switch.on)
        assertTrue(c.state.value.enabled)
        assertFalse(c.state.value.running)
        assertEquals("exited: signal", c.state.value.helperState)
        assertEquals(listOf(true, false), held)
        c.ensureRunning()                                       // the next open starts it
        assertEquals(listOf("start", "start"), channel.calls)
        assertEquals(listOf(true, false, true), held)
    }

    @Test
    fun `a loss is logged and shown`() {
        val c = coordinator()
        c.setEnabled(true)
        channel.emit(HelperMessage.Exiting("signal"))
        assertTrue(log.has("HELPER_LOST exited: signal"))
        assertEquals("HELPER exited: signal", c.state.value.eventLog.first())
    }

    @Test
    fun `a rejected write is shown and logged`() {
        val c = coordinator()
        val outcome = runBlocking { c.update { throw IllegalArgumentException("nope") } }
        assertIs<UpdateOutcome.Rejected>(outcome)
        assertEquals("config: write rejected: nope", c.state.value.configState)
        assertTrue(log.has("CONFIG_WRITE_REJECTED nope"))
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
        assertEquals(setOf(ModifierKey.AYN.control, Defaults.X), c.recording.value!!.held)
        channel.emit(key(0, 307, 0))
        channel.emit(key(1, 194, 0))
        val r = assertNotNull(c.recording.value)
        assertTrue(r.complete)
        assertEquals(setOf(ModifierKey.AYN), r.modifiers)
        assertEquals(setOf(Defaults.X), r.buttons)
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
            assertEquals(listOf("start", "capture on"), channel.calls)    // no "capture off"
            c.stopRecording()                                   // the UI's backstop: nothing
            assertEquals(listOf("start", "capture on"), channel.calls)
        }
    }
}
