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

data class DiagnosticsState(
    val gatewayName: String = "",
    val privilege: String = "not probed",
    val idOutput: String = "",
    val taskList: String = "",
    val lastSwap: String = "",
    val busy: Boolean = false,
    val helperState: String = "not started",
    val eventLog: List<String> = emptyList(),
)

class DiagnosticsViewModel(app: Application) : AndroidViewModel(app) {

    private val gateway = PserviceGateway(app)
    private val tasks = TaskActions(gateway)
    private val helper = HelperSupervisor(app, gateway, ::onHelperMessage)

    private val _state = MutableStateFlow(DiagnosticsState(gatewayName = gateway.name))
    val state: StateFlow<DiagnosticsState> = _state.asStateFlow()

    init {
        // Probe on open rather than waiting for a tap: a diagnostics screen should
        // already show its answer when you look at it, and it makes this milestone's
        // gate verifiable from `adb logcat` without anyone touching the device.
        probePrivilege()
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
            is HelperMessage.Fired ->
                _state.update { it.copy(eventLog = (listOf("FIRED rule ${message.ruleId}") + it.eventLog).take(40)) }
            is HelperMessage.Event -> _state.update {
                val row = "dev=${message.device} type=${message.type} code=${message.code} value=${message.value}"
                it.copy(eventLog = (listOf(row) + it.eventLog).take(40))
            }
        }
    }

    /** Runs [block] on the one privileged thread, never on main. */
    private fun privileged(block: () -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            try {
                withContext(Privilege.dispatcher) { block() }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    private companion object {
        const val TAG = "ThorcutsDiag"
    }
}
