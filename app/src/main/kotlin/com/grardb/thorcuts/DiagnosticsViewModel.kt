package com.grardb.thorcuts

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.grardb.thorcuts.core.helper.HelperMessage
import com.grardb.thorcuts.core.model.Bootstrap
import com.grardb.thorcuts.core.rules.CompiledTable
import com.grardb.thorcuts.core.rules.RuleCompiler
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
     * The table most recently compiled and handed to pushTable() -- which can drop it
     * silently if the socket is gone, so "pushed" means offered, not acknowledged. Kept
     * so a rule index the helper reports can be
     * resolved back to its Shortcut. Rule i of [CompiledTable.bytes] is
     * [CompiledTable.shortcuts]`[i]`, and [CompiledTable.generation] says which push.
     *
     * RETENTION DEPTH IS NOT DECIDED HERE. Only the latest table is kept for now, which
     * is enough for the acknowledgement check below. The matcher rewrite — the piece that
     * makes `F <gen> <index>` real — must state how many generations the app keeps; a hold
     * session can straddle a push, so "current plus previous" is the expected answer.
     * Written on the accept thread in the Ready branch; read there in TableAccepted.
     */
    @Volatile private var pushedTable: CompiledTable? = null

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
                    pushedTable = table
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
                _state.update {
                    it.copy(eventLog = (listOf("FIRED rule ${message.ruleId}") + it.eventLog).take(40))
                }
                if (message.ruleId == RULE_SWAP_SCREENS) {
                    // Arrives on the helper's reader thread. The swap blocks and needs
                    // uid 0, so it must hop to the one privileged thread.
                    //
                    // Privilege.scope, NOT viewModelScope — and NOT privileged{}.
                    // The helper outlives this ViewModel on purpose: it keeps the
                    // controller grabbed and keeps swallowing AYN+X after the
                    // diagnostics Activity finishes, for as long as the process
                    // lives. A viewModelScope.launch is a silent no-op once the
                    // ViewModel is cleared, so a combo pressed after a back press
                    // was eaten by the helper and then dropped here: no swap, no
                    // key reaching the game, nothing reporting it. Privilege.scope
                    // runs on the same single privileged thread, so this stays
                    // serialised against every UI-driven call (see Privilege.kt);
                    // it just does not die with the screen.
                    Privilege.scope.launch {
                        // swapScreens() returns the tool's result line verbatim, NOT a
                        // boolean. Pass it through and mark that the combo drove it, so
                        // a one-sided move and a partial failure stay readable.
                        val outcome = tasks.swapScreens()
                        // Logged as well as stored: when the combo fires with no
                        // Activity up, _state has no collector and this line is the
                        // only trace the swap happened. Safe either way —
                        // MutableStateFlow just holds the value.
                        Log.i(TAG, "COMBO_SWAP outcome=$outcome")
                        _state.update { it.copy(lastSwap = "$outcome (AYN+X)") }
                    }
                }
            }
            is HelperMessage.Event -> _state.update {
                val row = "dev=${message.device} type=${message.type} code=${message.code} value=${message.value}"
                it.copy(eventLog = (listOf(row) + it.eventLog).take(40))
            }
            is HelperMessage.TableAccepted -> {
                val pushed = pushedTable
                if (pushed == null || pushed.generation != message.generation) {
                    Log.w(
                        TAG,
                        "TABLE_ACK gen=${message.generation} does not match the pushed " +
                            "gen=${pushed?.generation}",
                    )
                }
                Log.i(TAG, "TABLE_ACK gen=${message.generation} rules=${message.ruleCount}")
                val gen = message.generation
                val line = "TABLE gen $gen ok, ${message.ruleCount} rule(s)"
                _state.update {
                    it.copy(
                        tableState = "table gen $gen: ${message.ruleCount} rule(s)",
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

        /** Must match TC_RULE_SWAP_SCREENS in native/src/matcher.h. */
        const val RULE_SWAP_SCREENS = 1
    }
}
