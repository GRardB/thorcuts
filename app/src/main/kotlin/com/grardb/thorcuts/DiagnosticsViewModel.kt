package com.grardb.thorcuts

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.grardb.thorcuts.core.config.ConfigStore
import com.grardb.thorcuts.core.helper.HelperMessage
import com.grardb.thorcuts.core.rules.FireResolution
import com.grardb.thorcuts.platform.actions.ActionDispatcher
import com.grardb.thorcuts.platform.config.AndroidConfigFile
import com.grardb.thorcuts.platform.helper.HelperSupervisor
import com.grardb.thorcuts.platform.privilege.Privilege
import com.grardb.thorcuts.platform.privilege.PserviceGateway
import com.grardb.thorcuts.platform.tasks.TaskActions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

private const val NO_TABLE = "no table"
private const val NO_TABLE_NO_HELPER = "no table (helper not running)"

/** The default shortcut's id (Defaults.SWAP_SCREENS); the one the diagnostics toggle flips. */
private const val SWAP_ID = "swap-screens"

data class DiagnosticsState(
    val gatewayName: String = "",
    val privilege: String = "not probed",
    val idOutput: String = "",
    val taskList: String = "",
    val lastSwap: String = "",
    val busy: Boolean = false,
    val helperState: String = "not started",
    /** Its own slot: an ack lands milliseconds after "ready, N devices" and must not
     *  overwrite it. */
    val tableState: String = NO_TABLE,
    /** What the config store last said: a count, or the load warning. Set only from the
     *  store's Loaded and Written events, so there is one source of truth on one thread. */
    val configState: String = "config: loading",
    /** The swap shortcut's enabled flag; null while loading or when the config has none. */
    val swapEnabled: Boolean? = null,
    val eventLog: List<String> = emptyList(),
)

class DiagnosticsViewModel(app: Application) : AndroidViewModel(app) {

    private val gateway = PserviceGateway(app)
    private val tasks = TaskActions(gateway)
    private val dispatcher = ActionDispatcher(tasks)
    private val helper = HelperSupervisor(app, gateway, ::onHelperMessage)

    /**
     * Parent spec §8's serialising actor: the owner of the config, the generation counter,
     * the retained tables, recompile-on-write, and the Ready / ack / fire bookkeeping. This
     * ViewModel forwards the helper's messages to it and shows its events; it holds no
     * generation and no table of its own any more.
     *
     * Built here beside the supervisor, and like the supervisor it lives and dies with this
     * ViewModel; both should move to a process-lifetime holder with F2's fix. The store's
     * thread is its own, so nothing here waits behind a privileged call.
     */
    private val store = ConfigStore(
        file = AndroidConfigFile(app),
        push = helper::pushTable,
        onEvent = ::onConfigEvent,
    )

    private val _state = MutableStateFlow(DiagnosticsState(gatewayName = gateway.name))
    val state: StateFlow<DiagnosticsState> = _state.asStateFlow()

    /** UI-initiated privileged calls not yet finished. See [privileged]. */
    private val uiCallsInFlight = AtomicInteger(0)

    init {
        // Probe on open rather than waiting for a tap: a diagnostics screen should
        // already show its answer when you look at it, and it makes this milestone's
        // gate verifiable from `adb logcat` without anyone touching the device.
        probePrivilege()
        // Load the config now. load() hops to the store's thread, so the file is never
        // read on main; the Loaded event fills configState when it is done.
        viewModelScope.launch { store.load() }
    }

    /**
     * The helper is deliberately NOT stopped here.
     *
     * It keeps the controller grabbed and keeps swallowing AYN+X for as long as
     * the process lives, which is what makes the shortcut work with the
     * diagnostics screen closed — the behaviour Privilege.scope exists to serve.
     * Stopping it here would hand the combo back to whatever is in the
     * foreground, i.e. silently turn the shortcut off when the screen closes.
     *
     * Logged because after this point `viewModelScope` is dead, so this line is
     * the boundary a combo-driven swap has to work on the far side of.
     */
    override fun onCleared() {
        Log.i(TAG, "VIEWMODEL_CLEARED helper left running")
        super.onCleared()
    }

    fun probePrivilege() = privileged {
        val id = gateway.exec("id") ?: "<no reply>"
        val ok = id.contains("uid=0")
        // One line, distinctive tag, machine-greppable. This is the M1 gate:
        // recon proved PServerBinder is registered; only this proves an ordinary
        // app process can reach it and be uid 0.
        Log.i(TAG, "PRIVILEGE_PROBE ok=$ok id=${id.trim()}")
        _state.update {
            it.copy(
                privilege = if (ok) "uid 0 CONFIRMED" else "NOT privileged",
                idOutput = id.trim(),
            )
        }
    }

    fun listTasks() = privileged {
        val out = tasks.listTasks()
        _state.update { it.copy(taskList = out) }
    }

    fun swapScreens() = privileged {
        val outcome = tasks.swapScreens()
        _state.update { it.copy(lastSwap = outcome) }
    }

    fun startHelper() = privileged {
        // A fresh connection gets a fresh slot -- but ONLY when there is no live helper.
        // start() is a silent no-op while one runs, and blanking the line then would
        // claim "no table" about a helper that still holds generation N.
        if (!helper.connected) _state.update { it.copy(tableState = NO_TABLE) }
        helper.start()
    }

    fun stopHelper() = privileged { helper.stop() }

    /**
     * The one write surface until M3's editor: flips the swap shortcut's enabled flag
     * through the store, which validates, persists, publishes, and — with a helper live —
     * pushes a recompiled table. Not a privileged call, so not through [privileged].
     */
    fun toggleSwap() {
        viewModelScope.launch {
            val outcome = store.update { config ->
                val now = config.shortcut(SWAP_ID)?.enabled ?: return@update config
                config.withEnabled(SWAP_ID, !now)
            }
            if (outcome is ConfigStore.UpdateOutcome.Rejected) {
                Log.w(TAG, "CONFIG_WRITE_REJECTED ${outcome.reason}")
                _state.update {
                    it.copy(configState = "config: write rejected: ${outcome.reason}")
                }
            }
        }
    }

    private fun onHelperMessage(message: HelperMessage) {
        when (message) {
            is HelperMessage.Ready -> {
                _state.update { it.copy(helperState = "ready, ${message.deviceCount} devices") }
                // The store compiles the current config at the next generation and pushes
                // it — on Ready, never earlier (HelperSupervisor.pushTable says why). A
                // config that does not compile comes back as a PushFailed event.
                store.helperReady()
            }
            is HelperMessage.Log -> {
                // The disconnect line is the one Log that means "no helper": clear the
                // table slot with it, or the screen keeps claiming a table nothing holds —
                // and tell the store, so nothing retained can be mistaken for live.
                if (message.text == HelperSupervisor.DISCONNECTED) {
                    store.helperGone()
                    _state.update {
                        it.copy(helperState = message.text, tableState = NO_TABLE_NO_HELPER)
                    }
                } else {
                    _state.update { it.copy(helperState = message.text) }
                }
            }
            is HelperMessage.Exiting -> {
                store.helperGone()
                _state.update {
                    it.copy(
                        helperState = "exited: ${message.reason}",
                        tableState = NO_TABLE_NO_HELPER,
                    )
                }
            }
            is HelperMessage.Fired -> store.fired(message.generation, message.index)
            is HelperMessage.Event -> _state.update {
                val row = "dev=${message.device} type=${message.type} " +
                    "code=${message.code} value=${message.value}"
                it.copy(eventLog = (listOf(row) + it.eventLog).take(40))
            }
            is HelperMessage.TableAccepted -> {
                val gen = message.generation
                // `installed` is the helper's proof that nothing older than gen can fire
                // again; the store prunes on it and keeps on deferred. An ack for a
                // generation it never pushed comes back as an UnexpectedAck event.
                store.tableAccepted(gen, message.installed)
                val how = if (message.installed) "installed" else "deferred"
                Log.i(TAG, "TABLE_ACK gen=$gen rules=${message.ruleCount} $how")
                val line = "TABLE gen $gen ok, ${message.ruleCount} rule(s), $how"
                _state.update {
                    it.copy(
                        tableState = "table gen $gen: ${message.ruleCount} rule(s), $how",
                        eventLog = (listOf(line) + it.eventLog).take(40),
                    )
                }
            }
            is HelperMessage.TableRejected -> {
                Log.w(TAG, "TABLE_REJECTED gen=${message.generation} reason=${message.reason}")
                val line = "TABLE gen ${message.generation} REJECTED: ${message.reason}"
                _state.update {
                    it.copy(
                        tableState = "table gen ${message.generation} REJECTED: ${message.reason}",
                        eventLog = (listOf(line) + it.eventLog).take(40),
                    )
                }
            }
        }
    }

    /** Runs on the store's thread. `_state.update` is thread-safe; a fire hops to
     *  Privilege.scope below, exactly as it did when this logic lived here. */
    private fun onConfigEvent(event: ConfigStore.Event) {
        when (event) {
            is ConfigStore.Event.Loaded -> {
                val n = event.config.shortcuts.size
                Log.i(
                    TAG,
                    "CONFIG_LOAD created=${event.created} shortcuts=$n " +
                        "dropped=${event.dropped.size} setAside=${event.setAside} " +
                        "failure=${event.failure}",
                )
                val line = buildString {
                    append("config: $n shortcut(s)")
                    if (event.created) append(", default written")
                    if (event.dropped.isNotEmpty()) {
                        append(", ${event.dropped.size} dropped: ")
                        append(event.dropped.joinToString { d ->
                            "#${d.index} ${d.id ?: "?"}: ${d.reason}"
                        })
                    }
                    if (event.setAside != null) {
                        append(", unreadable file kept as ${event.setAside}")
                    }
                    if (event.failure != null) append(" (${event.failure})")
                }
                _state.update {
                    it.copy(
                        configState = line,
                        swapEnabled = event.config.shortcut(SWAP_ID)?.enabled,
                    )
                }
            }
            is ConfigStore.Event.Written -> {
                val n = event.config.shortcuts.size
                Log.i(TAG, "CONFIG_WRITE shortcuts=$n")
                _state.update {
                    it.copy(
                        configState = "config: $n shortcut(s), written",
                        swapEnabled = event.config.shortcut(SWAP_ID)?.enabled,
                    )
                }
            }
            is ConfigStore.Event.TablePushed -> {
                // The supervisor's TABLE_PUSH line already logs it; the ack is the proof.
                val t = event.table
                val row = "TABLE gen ${t.generation} pushed, ${t.ruleCount} rule(s)"
                _state.update { it.copy(eventLog = (listOf(row) + it.eventLog).take(40)) }
            }
            is ConfigStore.Event.PushFailed -> {
                Log.w(TAG, "TABLE_PUSH_FAILED gen=${event.generation}: ${event.reason}")
                val line = "TABLE gen ${event.generation} NOT PUSHED: ${event.reason}"
                _state.update {
                    it.copy(
                        tableState = "table gen ${event.generation} NOT PUSHED: ${event.reason}",
                        eventLog = (listOf(line) + it.eventLog).take(40),
                    )
                }
            }
            is ConfigStore.Event.UnexpectedAck ->
                Log.w(TAG, "TABLE_ACK gen=${event.generation} matches no pushed table")
            is ConfigStore.Event.Fire -> onFire(event.resolution)
            is ConfigStore.Event.Failed -> {
                // Where the process used to die (or, before the store, one connection was
                // lost): reported with the trace, and a row the owner can see.
                Log.e(TAG, "CONFIG_FAILURE", event.error)
                val row = "CONFIG FAILURE: ${event.error}"
                _state.update { it.copy(eventLog = (listOf(row) + it.eventLog).take(40)) }
            }
        }
    }

    private fun onFire(res: FireResolution) {
        when (res) {
            is FireResolution.Hit -> {
                val gen = res.table.generation
                val index = res.index
                val id = res.shortcut.id
                Log.i(TAG, "FIRED gen=$gen index=$index shortcut=$id")
                val head = "FIRED gen $gen #$index $id"
                _state.update { it.copy(eventLog = (listOf(head) + it.eventLog).take(40)) }
                // Privilege.scope, NOT viewModelScope — and NOT privileged{}.
                // The helper outlives this ViewModel on purpose: it keeps the
                // controller grabbed and keeps swallowing the combo after the
                // diagnostics Activity finishes, for as long as the process
                // lives. A viewModelScope.launch is a silent no-op once the
                // ViewModel is cleared (measured in M1: the combo was eaten and
                // nothing happened). Privilege.scope runs on the same single
                // privileged thread, so this stays serialised against every
                // UI-driven call; it just does not die with the screen.
                Privilege.scope.launch {
                    // The outcome is the tool's own line, verbatim, never a boolean.
                    val outcome = dispatcher.dispatch(res.shortcut.action)
                    Log.i(TAG, "FIRE_OUTCOME gen=$gen index=$index shortcut=$id outcome=$outcome")
                    val row = "FIRED gen $gen #$index $id -> $outcome"
                    _state.update {
                        it.copy(
                            lastSwap = "$outcome ($id)",
                            eventLog = (listOf(row) + it.eventLog).take(40),
                        )
                    }
                }
            }
            is FireResolution.Stale -> unresolved("gen=${res.generation}", res)
            is FireResolution.OutOfRange ->
                unresolved("gen=${res.generation} index=${res.index}", res)
        }
    }

    /** A fire from a table we no longer hold, or an index the table does not have.
     *  Nothing runs; the row says why. */
    private fun unresolved(where: String, res: FireResolution) {
        Log.w(TAG, "FIRE_UNRESOLVED $where: $res")
        val row = "FIRED $where UNRESOLVED: $res"
        _state.update { it.copy(eventLog = (listOf(row) + it.eventLog).take(40)) }
    }

    /**
     * Runs [block] on the one privileged thread, never on main.
     *
     * For UI-initiated work only, so `busy` keeps tracking what the UI itself
     * started: a combo-driven swap must outlive this ViewModel and goes through
     * [Privilege.scope] instead (see [onFire]). Both land on the same single
     * thread, so they are still serialised against each other.
     *
     * `busy` is driven by a COUNTER, not a boolean. Overlapping launches are
     * routine now that a FIRE can arrive while a UI-driven call is queued, and
     * with a boolean the first block's `finally` cleared `busy` while the second
     * was still waiting on the dispatcher — re-enabling every button mid-
     * operation. Nothing breaks when that happens (the dispatcher serialises
     * regardless, so a stray tap only queues), which is why this is cosmetic, but
     * the flag may as well tell the truth.
     */
    private fun privileged(block: () -> Unit) {
        viewModelScope.launch {
            uiCallsInFlight.incrementAndGet()
            _state.update { it.copy(busy = true) }
            try {
                withContext(Privilege.dispatcher) { block() }
            } finally {
                // Still busy if another UI-driven call is queued behind this one.
                val remaining = uiCallsInFlight.decrementAndGet()
                _state.update { it.copy(busy = remaining > 0) }
            }
        }
    }

    private companion object {
        const val TAG = "ThorcutsDiag"
    }
}
