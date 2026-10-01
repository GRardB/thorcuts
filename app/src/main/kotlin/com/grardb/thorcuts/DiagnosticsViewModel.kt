package com.grardb.thorcuts

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
)

class DiagnosticsViewModel(app: Application) : AndroidViewModel(app) {

    private val gateway = PserviceGateway(app)
    private val tasks = TaskActions(gateway)

    private val _state = MutableStateFlow(DiagnosticsState(gatewayName = gateway.name))
    val state: StateFlow<DiagnosticsState> = _state.asStateFlow()

    fun probePrivilege() = privileged {
        val id = gateway.exec("id") ?: "<no reply>"
        val ok = id.contains("uid=0")
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
}
