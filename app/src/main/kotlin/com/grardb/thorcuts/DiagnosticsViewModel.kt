package com.grardb.thorcuts

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.grardb.thorcuts.core.helper.HelperMessage
import com.grardb.thorcuts.core.model.Bootstrap
import com.grardb.thorcuts.core.rules.FireResolution
import com.grardb.thorcuts.core.rules.RetainedTables
import com.grardb.thorcuts.core.rules.RuleCompiler
import com.grardb.thorcuts.platform.actions.ActionDispatcher
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
    val eventLog: List<String> = emptyList(),
)

class DiagnosticsViewModel(app: Application) : AndroidViewModel(app) {

    private val gateway = PserviceGateway(app)
    private val tasks = TaskActions(gateway)
    private val dispatcher = ActionDispatcher(tasks)
    private val helper = HelperSupervisor(app, gateway, ::onHelperMessage)

    /**
     * One generation per push, per process, starting at 1. Only the Ready branch below
     * touches it. Ready is delivered inline from serve() on the accept thread — a NEW
     * accept thread per start/stop cycle, never two at once — and the handoff between a
     * dying accept thread and the next start() goes through @Volatile `connected`/`running`
     * plus Thread.start(), which is the happens-before this plain var relies on. The
     * helper's table dies with its socket, so a restarted helper at a higher generation
     * is the only cross-connection case, and the right one.
     */
    private var tableGeneration = 0

    /**
     * Every table pushed since the helper last reported one `installed`, plus that one —
     * the matcher spec's retention rule, implemented by RetainedTables. A fire names the
     * INSTALLED generation and a rule index; it resolves here to the Shortcut it means.
     * Written on the accept thread (Ready pushes, TableAccepted prunes) and read there
     * (Fired resolves); one thread, so no lock.
     */
    @Volatile private var tables = RetainedTables()

    private val _state = MutableStateFlow(DiagnosticsState(gatewayName = gateway.name))
    val state: StateFlow<DiagnosticsState> = _state.asStateFlow()

    /** UI-initiated privileged calls not yet finished. See [privileged]. */
    private val uiCallsInFlight = AtomicInteger(0)

    init {
        // Probe on open rather than waiting for a tap: a diagnostics screen should
        // already show its answer when you look at it, and it makes this milestone's
        // gate verifiable from `adb logcat` without anyone touching the device.
        probePrivilege()
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

    private fun onHelperMessage(message: HelperMessage) {
        when (message) {
            is HelperMessage.Ready -> {
                _state.update { it.copy(helperState = "ready, ${message.deviceCount} devices") }
                // Push the rule table NOW, on Ready, and never earlier — see pushTable().
                // Bootstrap.SHORTCUTS is M1's one rule until the config layer exists.
                // foreground = null until M4's monitor exists: Global rules apply, Apps
                // rules do not (RuleCompiler.select).
                //
                // Caught here, not left to serve(): a `require` out of compile() (too many
                // applicable rules) or PushTable (a malformed payload) is a FAILED PUSH, and
                // serve()'s catch would instead close the socket and report a dead helper.
                // Unreachable with one bootstrap rule; reachable the day a config loads.
                // Deliberately NOT Throwable: an encoder-invariant `check` failing is a bug
                // in this app, and killing the connection is the right outcome for that.
                try {
                    val table = RuleCompiler.compile(Bootstrap.SHORTCUTS, null, ++tableGeneration)
                    tables = tables.push(table)
                    helper.pushTable(table)
                } catch (e: IllegalArgumentException) {
                    Log.w(TAG, "TABLE_PUSH_FAILED gen=$tableGeneration: ${e.message}")
                    val line = "TABLE gen $tableGeneration NOT PUSHED: ${e.message}"
                    _state.update {
                        it.copy(
                            tableState = "table gen $tableGeneration NOT PUSHED: ${e.message}",
                            eventLog = (listOf(line) + it.eventLog).take(40),
                        )
                    }
                }
            }
            is HelperMessage.Log -> _state.update {
                // The disconnect line is the one Log that means "no helper": clear the
                // table slot with it, or the screen keeps claiming a table nothing holds.
                if (message.text == HelperSupervisor.DISCONNECTED) {
                    it.copy(helperState = message.text, tableState = NO_TABLE_NO_HELPER)
                } else {
                    it.copy(helperState = message.text)
                }
            }
            is HelperMessage.Exiting ->
                _state.update {
                    it.copy(
                        helperState = "exited: ${message.reason}",
                        tableState = NO_TABLE_NO_HELPER,
                    )
                }
            is HelperMessage.Fired -> {
                val gen = message.generation
                val index = message.index
                when (val res = tables.resolve(gen, index)) {
                    is FireResolution.Hit -> {
                        val id = res.shortcut.id
                        Log.i(TAG, "FIRED gen=$gen index=$index shortcut=$id")
                        val head = "FIRED gen $gen #$index $id"
                        _state.update {
                            it.copy(eventLog = (listOf(head) + it.eventLog).take(40))
                        }
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
                            Log.i(TAG, "FIRE_OUTCOME gen=$gen index=$index shortcut=$id " +
                                "outcome=$outcome")
                            val row = "FIRED gen $gen #$index $id -> $outcome"
                            _state.update {
                                it.copy(
                                    lastSwap = "$outcome ($id)",
                                    eventLog = (listOf(row) + it.eventLog).take(40),
                                )
                            }
                        }
                    }
                    is FireResolution.Stale, is FireResolution.OutOfRange -> {
                        // A fire from a table we no longer hold, or an index the table
                        // does not have. Nothing runs; the row says why.
                        Log.w(TAG, "FIRE_UNRESOLVED gen=$gen index=$index: $res")
                        val row = "FIRED gen $gen #$index UNRESOLVED: $res"
                        _state.update {
                            it.copy(eventLog = (listOf(row) + it.eventLog).take(40))
                        }
                    }
                }
            }
            is HelperMessage.Event -> _state.update {
                val row = "dev=${message.device} type=${message.type} code=${message.code} value=${message.value}"
                it.copy(eventLog = (listOf(row) + it.eventLog).take(40))
            }
            is HelperMessage.TableAccepted -> {
                val gen = message.generation
                if (tables.tables.none { it.generation == gen }) {
                    Log.w(TAG, "TABLE_ACK gen=$gen matches no pushed table")
                }
                // `installed` is the helper's proof that nothing older than gen can fire
                // again: a session fires only from the installed table, and a push during
                // a session is deferred until it closes. Prune on it; keep on deferred.
                if (message.installed) tables = tables.installed(gen)
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

    /**
     * Runs [block] on the one privileged thread, never on main.
     *
     * For UI-initiated work only, so `busy` keeps tracking what the UI itself
     * started: a combo-driven swap must outlive this ViewModel and goes through
     * [Privilege.scope] instead (see the Fired branch above). Both land on the
     * same single thread, so they are still serialised against each other.
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
