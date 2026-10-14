package com.grardb.thorcuts

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.grardb.thorcuts.core.config.ThorcutsConfig
import com.grardb.thorcuts.core.model.Action
import com.grardb.thorcuts.platform.coordinator.CoordinatorState
import com.grardb.thorcuts.platform.coordinator.ThorcutsCoordinator
import com.grardb.thorcuts.platform.privilege.Privilege
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

/** The default shortcut's id (Defaults.SWAP_SCREENS); the one the diagnostics toggle flips. */
private const val SWAP_ID = "swap-screens"

data class DiagnosticsState(
    val gatewayName: String = "",
    val privilege: String = "not probed",
    val idOutput: String = "",
    val taskList: String = "",
    /** The last action's outcome line — the Swap button's or a fired shortcut's. */
    val lastOutcome: String = "",
    val busy: Boolean = false,
    /** The persisted switch, and the transition text while a start or stop is in flight. */
    val enabled: Boolean = false,
    val transition: String? = null,
    val helperState: String = "not started",
    val tableState: String = "no table",
    val configState: String = "config: loading",
    /** The swap shortcut's enabled flag; null while loading or when the config has none. */
    val swapEnabled: Boolean? = null,
    val eventLog: List<String> = emptyList(),
)

/**
 * A view of the holder. Everything with a lifetime — the gateway, the actions, the helper,
 * the store, the switch, the log — lives in [ThorcutsCoordinator], built once per process by
 * [ThorcutsApp]; this ViewModel renders its state, forwards the buttons, and keeps only what
 * is UI-driven: the probe's answer, the task list and `busy`. A screen closed and reopened
 * therefore shows the same helper, the same table and the same rows (F2, closed by
 * construction: nothing is rebuilt here).
 */
class DiagnosticsViewModel(app: Application) : AndroidViewModel(app) {

    private val coordinator = ThorcutsApp.of(app).coordinator

    /** What only this screen produces. */
    private data class Local(
        val privilege: String = "not probed",
        val idOutput: String = "",
        val taskList: String = "",
        val busy: Boolean = false,
    )

    private val local = MutableStateFlow(Local())

    /** The holder's rows, this screen's own, and the config's one flag, as one state. */
    private fun render(c: CoordinatorState, l: Local, config: ThorcutsConfig) = DiagnosticsState(
        gatewayName = coordinator.gateway.name,
        privilege = l.privilege,
        idOutput = l.idOutput,
        taskList = l.taskList,
        lastOutcome = c.lastOutcome,
        busy = l.busy,
        enabled = c.enabled,
        transition = c.transition,
        helperState = c.helperState,
        tableState = c.tableState,
        configState = c.configState,
        swapEnabled = config.shortcut(SWAP_ID)?.enabled,
        eventLog = c.eventLog,
    )

    val state: StateFlow<DiagnosticsState> =
        combine(coordinator.state, local, coordinator.config, ::render).stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            // Seeded from the holder's live snapshot, not the defaults: a recreated screen
            // reads ON and "ready" on its first frame, with no dispatch in between.
            render(coordinator.state.value, local.value, coordinator.config.value),
        )

    /** UI-initiated privileged calls not yet finished. See [privileged]. */
    private val uiCallsInFlight = AtomicInteger(0)

    init {
        // Probe on open rather than waiting for a tap: a diagnostics screen should
        // already show its answer when you look at it, and it makes this milestone's
        // gate verifiable from `adb logcat` without anyone touching the device.
        probePrivilege()
        // An app open is a process start as far as the switch is concerned: with shortcuts
        // on and nothing running (after a force-stop, a crash, an update), this starts the
        // helper. A no-op whenever it already runs.
        coordinator.ensureRunning()
    }

    /**
     * Nothing to stop here: the holder owns the helper, and it keeps the controller grabbed
     * and the combo swallowed for as long as the process lives. Logged as the boundary
     * marker the device runs grep for — a fire after this line is served with no UI at all.
     */
    override fun onCleared() {
        Log.i(TAG, "VIEWMODEL_CLEARED helper left running")
        super.onCleared()
    }

    fun probePrivilege() = privileged {
        val id = coordinator.gateway.exec("id") ?: "<no reply>"
        val ok = id.contains("uid=0")
        // One line, distinctive tag, machine-greppable. This is the M1 gate:
        // recon proved PServerBinder is registered; only this proves an ordinary
        // app process can reach it and be uid 0.
        Log.i(TAG, "PRIVILEGE_PROBE ok=$ok id=${id.trim()}")
        local.update {
            it.copy(
                privilege = if (ok) "uid 0 CONFIRMED" else "NOT privileged",
                idOutput = id.trim(),
            )
        }
    }

    fun listTasks() = privileged {
        val out = coordinator.tasks.listTasks()
        local.update { it.copy(taskList = out) }
    }

    /** The Swap button: the path a fired SwapScreens takes, labelled "swap". */
    fun swapScreens() = privileged { coordinator.run(Action.SwapScreens, "swap") }

    /**
     * The switch. Not through [privileged]: the coordinator hops to the privileged thread
     * itself and returns at once, and the button disables on the transition instead.
     */
    fun setEnabled(on: Boolean) = coordinator.setEnabled(on)

    /**
     * The one write surface until M3's editor: flips the swap shortcut's enabled flag
     * through the holder, which validates, persists, publishes, pushes when a helper is
     * live, and shows a rejection in configState.
     */
    fun toggleSwap() {
        viewModelScope.launch {
            coordinator.update { config ->
                val now = config.shortcut(SWAP_ID)?.enabled ?: return@update config
                config.withEnabled(SWAP_ID, !now)
            }
        }
    }

    /**
     * Runs [block] on the one privileged thread, never on main.
     *
     * For UI-initiated work only, so `busy` keeps tracking what the UI itself started: a
     * fired shortcut goes through the coordinator on Privilege.scope instead. Both land on
     * the same single thread, so they are still serialised against each other.
     *
     * `busy` is driven by a COUNTER, not a boolean. Overlapping launches are routine now
     * that a fire can arrive while a UI-driven call is queued, and with a boolean the first
     * block's `finally` cleared `busy` while the second was still waiting on the dispatcher
     * — re-enabling every button mid-operation. Nothing breaks when that happens (the
     * dispatcher serialises regardless, so a stray tap only queues), which is why this is
     * cosmetic, but the flag may as well tell the truth.
     */
    private fun privileged(block: () -> Unit) {
        viewModelScope.launch {
            uiCallsInFlight.incrementAndGet()
            local.update { it.copy(busy = true) }
            try {
                withContext(Privilege.dispatcher) { block() }
            } finally {
                // Still busy if another UI-driven call is queued behind this one.
                val remaining = uiCallsInFlight.decrementAndGet()
                local.update { it.copy(busy = remaining > 0) }
            }
        }
    }

    private companion object {
        const val TAG = "ThorcutsDiag"
    }
}
