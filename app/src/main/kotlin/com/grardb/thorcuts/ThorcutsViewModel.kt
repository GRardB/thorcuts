package com.grardb.thorcuts

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.grardb.thorcuts.core.config.ConfigStore.UpdateOutcome
import com.grardb.thorcuts.core.config.ThorcutsConfig
import com.grardb.thorcuts.core.model.ModifierKey
import com.grardb.thorcuts.core.model.ScreenTarget
import com.grardb.thorcuts.core.model.Shortcut
import com.grardb.thorcuts.core.recorder.TriggerRecorder
import com.grardb.thorcuts.core.rules.ConflictDetector
import com.grardb.thorcuts.platform.coordinator.CoordinatorState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A recording left open — the game on the other screen, a dead pad — ends itself. */
private const val RECORDING_TIMEOUT_MS = 20_000L

data class UiState(
    /** The persisted switch, and the transition text while a start or stop is in flight. */
    val enabled: Boolean,
    val transition: String?,
    /** The helper is up and ready: Record is offered exactly then. */
    val helperLive: Boolean,
    val helperState: String,
    val tableState: String,
    val configState: String,
    val shortcuts: List<Shortcut>,
    /** Non-null while the editor is open. */
    val editor: EditorDraft?,
    /** Non-null while the recorder is listening. */
    val recording: TriggerRecorder?,
    /** The one line about Start or Select among the draft's modifiers, when they are. */
    val modifierNote: String?,
    /** ConflictDetector's warning on the draft's trigger, as copy; null when there is none, or
     *  the draft is not yet a trigger. */
    val warning: String?,
)

/**
 * A view of the holder, plus the one piece of state that is the screen's own: the editor's
 * draft. Everything with a lifetime lives in [ThorcutsCoordinator] (built once per process
 * by [ThorcutsApp]); every edit goes through `coordinator.update`, and the Shortcut is built
 * INSIDE the edit lambda so every model `require` comes back as one `Rejected(reason)`.
 * Recording is the coordinator's too; this ViewModel only starts it, copies the result into
 * the draft when the combo is released, and ends it — on the release, on close, and after
 * [RECORDING_TIMEOUT_MS] — so the helper's gate never stays shut behind a screen that is gone.
 */
class ThorcutsViewModel(app: Application) : AndroidViewModel(app) {

    private val coordinator = ThorcutsApp.of(app).coordinator

    private val draft = MutableStateFlow<EditorDraft?>(null)

    private var timeout: Job? = null

    private fun render(
        c: CoordinatorState,
        config: ThorcutsConfig,
        recording: TriggerRecorder?,
        d: EditorDraft?,
    ) = UiState(
        enabled = c.enabled,
        transition = c.transition,
        helperLive = c.helperLive,
        helperState = c.helperState,
        tableState = c.tableState,
        configState = c.configState,
        shortcuts = config.shortcuts,
        editor = d,
        recording = recording,
        modifierNote = d?.takeIf { it.modifiers.any { m -> m in GAMEPLAY } }
            ?.let { Copy.MODIFIER_NOTE },
        warning = d?.let { runCatching { it.trigger() }.getOrNull() }
            ?.let { ConflictDetector.warnings(it) }
            ?.firstOrNull()
            ?.let { w ->
                when (w) {
                    ConflictDetector.Warning.HOLD_ON_GAMEPLAY_MODIFIER -> Copy.LATE
                }
            },
    )

    val state: StateFlow<UiState> =
        combine(coordinator.state, coordinator.config, coordinator.recording, draft, ::render)
            .stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                // Seeded from the holder's live snapshot, not the defaults: a recreated screen
                // reads ON and "ready" on its first frame, with no dispatch in between.
                render(
                    coordinator.state.value, coordinator.config.value,
                    coordinator.recording.value, draft.value,
                ),
            )

    init {
        // An app open is a process start as far as the switch is concerned: with shortcuts
        // on and nothing running (after a force-stop, a crash, an update), this starts the
        // helper. A no-op whenever it already runs.
        coordinator.ensureRunning()
        // The release ends the recording: the draft takes the combo and the gate reopens.
        viewModelScope.launch {
            coordinator.recording.collect { r ->
                if (r?.complete == true) {
                    draft.update {
                        it?.copy(modifiers = r.modifiers, buttons = r.buttons, error = null)
                    }
                    stopRecording()
                }
            }
        }
    }

    /**
     * The backstop for the gate: a ViewModel cleared mid-recording (the Activity finished)
     * ends it. The helper itself stays: the holder owns it, and it keeps the controller
     * grabbed and the combos swallowed for as long as the process lives. Logged as the
     * boundary marker the device runs grep for.
     */
    override fun onCleared() {
        coordinator.stopRecording()
        Log.i(TAG, "VIEWMODEL_CLEARED helper left running")
        super.onCleared()
    }

    /** The switch: the coordinator hops to the privileged thread itself and returns at once;
     *  the button is disabled on the transition. */
    fun setEnabled(on: Boolean) = coordinator.setEnabled(on)

    /** The row switch. A Rejected (enabling a parked duplicate) shows in the config line, as
     *  every rejected write does. */
    fun setShortcutEnabled(id: String, on: Boolean) {
        viewModelScope.launch {
            val outcome = coordinator.update { it.withEnabled(id, on) }
            Log.i(TAG, "EDIT ${if (on) "enabled" else "disabled"} $id -> ${outcome.text()}")
        }
    }

    fun add() {
        draft.value = EditorDraft()
    }

    fun edit(id: String) {
        val s = coordinator.config.value.shortcut(id) ?: return
        draft.value = EditorDraft.of(s)
    }

    fun setId(id: String) = change { it.copy(id = id) }
    fun setTimer(timer: Boolean) = change { it.copy(timer = timer) }
    fun setHoldMs(ms: String) = change { it.copy(holdMs = ms) }
    fun setAction(kind: ActionKind) = change { it.copy(action = kind) }
    fun setScreen(screen: ScreenTarget) = change { it.copy(screen = screen) }

    /** Every change clears the last refusal: it was about the draft as it was. */
    private fun change(edit: (EditorDraft) -> EditorDraft) {
        draft.update { it?.let(edit)?.copy(error = null) }
    }

    /** Start, or start over. The timeout is this ViewModel's: the coordinator has no clock. */
    fun startRecording() {
        coordinator.startRecording()
        timeout?.cancel()
        timeout = viewModelScope.launch {
            delay(RECORDING_TIMEOUT_MS)
            coordinator.stopRecording()
        }
    }

    fun stopRecording() {
        timeout?.cancel()
        timeout = null
        coordinator.stopRecording()
    }

    fun save() {
        val d = draft.value ?: return
        stopRecording()
        viewModelScope.launch {
            val outcome = coordinator.update { config ->
                val s = d.toShortcut()
                if (d.original == null) config.added(s) else config.replaced(s)
            }
            val verb = if (d.original == null) "added" else "replaced"
            Log.i(TAG, "EDIT $verb ${d.id.trim()} -> ${outcome.text()}")
            finish(outcome)
        }
    }

    fun delete() {
        val id = draft.value?.original?.id ?: return
        stopRecording()
        viewModelScope.launch {
            val outcome = coordinator.update { it.without(id) }
            Log.i(TAG, "EDIT removed $id -> ${outcome.text()}")
            finish(outcome)
        }
    }

    fun cancel() {
        stopRecording()
        draft.value = null
    }

    /** Applied closes the editor; Rejected keeps it open with the reason under Save. */
    private fun finish(outcome: UpdateOutcome) {
        when (outcome) {
            is UpdateOutcome.Applied -> draft.value = null
            is UpdateOutcome.Rejected -> draft.update { it?.copy(error = outcome.reason) }
        }
    }

    private fun UpdateOutcome.text(): String = when (this) {
        is UpdateOutcome.Applied -> "applied"
        is UpdateOutcome.Rejected -> "rejected: $reason"
    }

    private companion object {
        /** Kept from the diagnostics ViewModel: the device runs filter on this tag. */
        const val TAG = "ThorcutsDiag"
        val GAMEPLAY = setOf(ModifierKey.START, ModifierKey.SELECT)
    }
}
