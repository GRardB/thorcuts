package com.grardb.thorcuts.platform.coordinator

import com.grardb.thorcuts.core.helper.HelperMessage
import com.grardb.thorcuts.platform.config.MemoryConfigFile
import com.grardb.thorcuts.platform.helper.FakeChannel
import com.grardb.thorcuts.platform.helper.HelperChannel
import com.grardb.thorcuts.platform.helper.HelperSupervisor
import com.grardb.thorcuts.platform.log.RecordingLogger
import com.grardb.thorcuts.platform.privilege.FakeGateway
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.jupiter.api.assertTimeoutPreemptively
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The holder with its privileged work QUEUED: a [StandardTestDispatcher] stands in for
 * Privilege.scope, so every `privileged.launch` waits until the test runs the scheduler, and
 * the orderings the inline suite cannot show — a switch write queued at an epoch the panic
 * then passes, a relaunch queued behind the start whose helper was lost, two capture sends in
 * a row — are observable between calls. The store stays Unconfined: its thread is not the one
 * under test. The holder's H3-4 and the editor's E4-5.
 */
class ThorcutsCoordinatorQueuedTest {

    private val scheduler = TestCoroutineScheduler()

    /** A throw in a drained body reaches here, not the test, unless every test asserts its
     *  effects — so [noUnhandledFailures] backs that with the scope itself (R3-5). */
    private val failures = mutableListOf<Throwable>()
    private val privileged = CoroutineScope(
        SupervisorJob() + StandardTestDispatcher(scheduler) +
            CoroutineExceptionHandler { _, t -> failures += t },
    )

    /** The store's own scheduler, for the one test that parks an attempt at store.load(). */
    private val storeScheduler = TestCoroutineScheduler()
    private val file = MemoryConfigFile()
    private val switch = FakeSwitch()
    private val held = mutableListOf<Boolean>()
    private val log = RecordingLogger()
    private lateinit var channel: FakeChannel

    /** The relaunch chain's clock, in milliseconds, so no test here depends on the wall clock. */
    private var now = 0L

    private fun coordinator(
        storeDispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
    ) = ThorcutsCoordinator(
        gateway = FakeGateway(),
        channel = { onMessage -> FakeChannel(onMessage).also { channel = it } },
        file = file,
        switch = switch,
        keeper = { held += it },
        homePackages = { emptySet() },
        launchComponent = { null },
        log = log,
        privileged = privileged,
        storeDispatcher = storeDispatcher,
        clock = { now },
    )

    /** No drained body reached the scope's handler as an unhandled throw (R3-5). */
    @AfterTest
    fun noUnhandledFailures() {
        assertTrue(failures.isEmpty(), "unhandled in a drained body: $failures")
    }

    /** Run everything queued on the privileged thread, including what that work queues.
     *  Bounded, so a body that re-queues itself fails the test instead of hanging it (R3-4). */
    private fun drain() = assertTimeoutPreemptively(Duration.ofSeconds(5)) {
        scheduler.runCurrent()
    }

    /** Both schedulers to quiescence — the privileged work and the store hops it awaits, no
     *  virtual time advanced — for the test whose store is on [storeScheduler]. Bounded. */
    private fun settle() = assertTimeoutPreemptively(Duration.ofSeconds(5)) {
        repeat(50) { scheduler.runCurrent(); storeScheduler.runCurrent() }
    }

    @Test
    fun `the switch write precedes the start on the queue, and the hold precedes both`() {
        val c = coordinator()
        c.setEnabled(true)
        assertEquals(emptyList<Boolean>(), switch.writes)       // nothing ran yet
        assertEquals(listOf(true), held)                        // the hold is immediate
        assertEquals("starting…", c.state.value.transition)
        drain()
        val write = log.lines.indexOfFirst { "SWITCH on=true by=ui" in it && "stale" !in it }
        val start = log.lines.indexOfFirst { "HELPER_START" in it }
        assertTrue(write in 0 until start)
        assertEquals(listOf(true), switch.writes)
        assertEquals(listOf("start"), channel.calls)
        assertNull(c.state.value.transition)
    }

    @Test
    fun `a switch write queued at an epoch the panic has passed is stale and not written`() {
        val c = coordinator()
        c.setEnabled(true)
        drain()                                                 // loaded, ON written, started
        channel.emit(HelperMessage.Ready(1))
        assertEquals(listOf(true), switch.writes)
        c.setEnabled(true)                                      // a NOOP whose write still queues
        channel.emit(HelperMessage.Exiting("panic combo"))      // OFF written on the reader thread
        assertEquals(listOf(true, false), switch.writes)
        drain()                                                 // the queued ON lands — stale
        assertEquals(listOf(true, false), switch.writes)
        assertFalse(switch.on)
        assertTrue(log.has("SWITCH on=true by=ui stale, not written"))
    }

    @Test
    fun `the capture sends keep their order on the queue`() {
        val c = coordinator()
        c.setEnabled(true)
        drain()
        channel.emit(HelperMessage.Ready(1))
        c.startRecording()
        c.stopRecording()
        assertEquals(listOf("start"), channel.calls)            // both sends still queued
        drain()
        assertEquals(listOf("start", "capture on", "capture off"), channel.calls)
    }

    @Test
    fun `a relaunch owed during a start is queued behind it and inherits its transition`() {
        val c = coordinator()
        var starts = 0
        var seen: String? = null
        channel.onStart = {
            when (++starts) {
                1 -> channel.emit(HelperMessage.Log(HelperSupervisor.DISCONNECTED))
                2 -> seen = c.state.value.transition
            }
        }
        c.setEnabled(true)
        assertEquals(emptyList<String>(), channel.calls)        // the start is queued
        drain()
        assertEquals(listOf("start", "start"), channel.calls)
        assertEquals("starting…", seen)                         // kept by the first attempt
        assertEquals(listOf(true), held)
        assertTrue(c.state.value.running)
        assertNull(c.state.value.transition)
        assertTrue(log.has("RELAUNCH after helper disconnected"))
    }

    @Test
    fun `a loss during a start whose start then fails withdraws the relaunch`() {
        val c = coordinator()
        channel.onStart = {
            channel.emit(HelperMessage.Log(HelperSupervisor.DISCONNECTED))
            channel.outcome = HelperChannel.Start.NOT_BOUND
        }
        c.setEnabled(true)
        drain()
        assertEquals(listOf("start"), channel.calls)            // the relaunch never ran
        assertFalse(c.state.value.running)
        assertNull(c.state.value.transition)
        assertTrue(switch.on)                                   // a normal failed start
        assertEquals("could not bind the helper socket", c.state.value.helperState)
        assertEquals(listOf(true, false), held)
    }

    @Test
    fun `a give-up while a relaunch is queued ends the transition and never starts`() {
        val c = coordinator()
        c.setEnabled(true)
        drain()
        channel.emit(HelperMessage.Ready(1))
        now = 1_000
        channel.emit(HelperMessage.Exiting("signal"))           // a relaunch owed, queued
        assertEquals("restarting…", c.state.value.transition)
        assertEquals(listOf("start"), channel.calls)
        now = 2_000
        channel.emit(HelperMessage.Exiting("signal"))           // a second loss: gave up
        assertNull(c.state.value.transition)                    // nobody else would end it
        assertFalse(c.state.value.enabled)
        assertEquals(listOf(true, false), switch.writes)
        assertEquals(listOf(true, false), held)
        drain()                                                 // the queued relaunch runs
        assertEquals(listOf("start"), channel.calls)            // and returns at takeOwed()
        assertEquals(listOf(true, false), held)                 // releasing nothing
        assertNull(c.state.value.transition)
        c.setEnabled(true)                                      // the switch works again
        assertEquals("starting…", c.state.value.transition)
    }

    @Test
    fun `the panic while a relaunch is queued cancels it`() {
        val c = coordinator()
        c.setEnabled(true)
        drain()
        channel.emit(HelperMessage.Ready(1))
        channel.emit(HelperMessage.Exiting("signal"))           // a relaunch owed, queued
        channel.emit(HelperMessage.Exiting("panic combo"))      // the user wants the pad back
        assertFalse(switch.on)
        assertEquals(listOf(true, false), switch.writes)
        assertNull(c.state.value.transition)
        assertEquals(listOf(true, false), held)
        drain()
        assertEquals(listOf("start"), channel.calls)            // no start after the OFF
        assertEquals(listOf(true, false), held)                 // and no second release
        assertFalse(c.state.value.running)
    }

    @Test
    fun `a relaunch queued while an attempt parks at load runs interleaved`() {
        // The store on its own scheduler, so an attempt parks at store.load() where a loss
        // can be injected between the schedulers — the production interleaving the Unconfined
        // suites cannot show (R2-3; the final review's test gap).
        val c = coordinator(StandardTestDispatcher(storeScheduler))
        c.setEnabled(true)
        settle()                                                // a session started and ready
        channel.emit(HelperMessage.Ready(1))
        storeScheduler.runCurrent()                             // Ready's push
        now = 0L
        channel.emit(HelperMessage.Exiting("signal"))           // a relaunch owed, "restarting…"
        scheduler.runCurrent()                                  // the 2nd attempt parks at load
        assertEquals("restarting…", c.state.value.transition)
        assertEquals(listOf("start"), channel.calls)            // not started yet
        now = 61_000L
        channel.emit(HelperMessage.Exiting("signal"))           // a new chain: a 3rd attempt
        assertEquals("restarting…", c.state.value.transition)   // still owed by the 3rd
        settle()
        assertEquals(listOf("start", "start", "start"), channel.calls)
        assertEquals(listOf(true), held)                        // relaunches hold nothing
        assertNull(c.state.value.transition)                    // ended by the last attempt
        assertTrue(switch.on)
        assertTrue(c.state.value.running)
    }
}
