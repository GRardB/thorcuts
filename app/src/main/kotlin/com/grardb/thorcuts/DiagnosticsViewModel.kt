package com.grardb.thorcuts

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.grardb.thorcuts.core.helper.HelperMessage
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
    val tableState: String = "no table",
    val eventLog: List<String> = emptyList(),
)

class DiagnosticsViewModel(app: Application) : AndroidViewModel(app) {

    private val gateway = PserviceGateway(app)
    private val tasks = TaskActions(gateway)
    private val helper = HelperSupervisor(app, gateway, ::onHelperMessage)

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

    fun startHelper() = privileged { helper.start() }

    fun stopHelper() = privileged { helper.stop() }

    private fun onHelperMessage(message: HelperMessage) {
        when (message) {
            is HelperMessage.Ready ->
                _state.update { it.copy(helperState = "ready, ${message.deviceCount} devices") }
            is HelperMessage.Log ->
                _state.update { it.copy(helperState = message.text) }
            is HelperMessage.Exiting ->
                _state.update { it.copy(helperState = "exited: ${message.reason}") }
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
