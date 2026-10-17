package com.grardb.thorcuts

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.grardb.thorcuts.core.config.ConfigStore.UpdateOutcome
import com.grardb.thorcuts.core.config.ThorcutsConfig
import com.grardb.thorcuts.core.model.ModifierKey
import com.grardb.thorcuts.core.model.ScreenTarget
import com.grardb.thorcuts.core.recorder.TriggerRecorder
import com.grardb.thorcuts.core.rules.ConflictDetector
import com.grardb.thorcuts.platform.coordinator.CoordinatorState
import com.grardb.thorcuts.platform.coordinator.ImportOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.math.roundToInt

/** A recording left open — the game on the other screen, a dead pad — ends itself. */
private const val RECORDING_TIMEOUT_MS = 20_000L

/** The picker's icon size; each icon is rendered once at this size, in pixels, and kept. */
private const val ICON_DP = 40

/**
 * A view of the holder, plus the one piece of state that is the screen's own: the editor's
 * draft. Everything with a lifetime lives in [ThorcutsCoordinator] (built once per process
 * by [ThorcutsApp]); every edit goes through `coordinator.update`, and the Shortcut is built
 * INSIDE the edit lambda so every model `require` comes back as one `Rejected(reason)`. The
 * writes run on [ThorcutsApp.writes], not this ViewModel's scope, so a Save survives the
 * screen closing; the editor's writes go one at a time (the `writing` flag — an import or
 * export does not take it), so a second Save tap cannot run the add again against the
 * config the first one wrote and report a refusal for a write that succeeded.
 * Recording is the coordinator's too; this ViewModel only starts it, copies the result into
 * the draft when the combo is released, and ends it — on the release, on close, and after
 * [RECORDING_TIMEOUT_MS] — so the helper's gate never stays shut behind a screen that is gone.
 * Import and export are the coordinator's too ([exportFile], [importFile]): this ViewModel only
 * opens the file the picker returned and does that I/O off main. What those report, and a
 * refused row toggle, is posted as a [Message] for the shell's snackbar (the UI polish).
 */
class ThorcutsViewModel(app: Application) : AndroidViewModel(app) {

    private val coordinator = ThorcutsApp.of(app).coordinator

    private val pm = app.packageManager

    private val resolver = app.contentResolver

    /** Pixels per dp on this device, for the picker's icons. */
    private val density = app.resources.displayMetrics.density

    /** Process-lifetime and on main: a write outlives this ViewModel. See [ThorcutsApp.writes]. */
    private val writes = ThorcutsApp.of(app).writes

    private val draft = MutableStateFlow<EditorDraft?>(null)

    /** The ViewModel's own state beyond the draft: the picker's app list (resolved once, with
     *  a flag while it is), the in-flight-write flag and the last message, so the `combine`
     *  stays at five flows. */
    private data class Local(
        val installed: List<InstalledApp> = emptyList(),
        val loadingApps: Boolean = false,
        val writing: Boolean = false,
        val message: Message? = null,
        val report: Report? = null,
    )

    private val local = MutableStateFlow(Local())

    private var timeout: Job? = null

    /** Bumped whenever the draft is opened, replaced or closed; a write captures it and
     *  [finish] applies its outcome only while it is still current, so a Save that returns
     *  after the user backed out cannot close or stamp the next draft (E7-1). */
    private var draftSession = 0

    private fun render(
        c: CoordinatorState,
        config: ThorcutsConfig,
        recording: TriggerRecorder?,
        d: EditorDraft?,
        l: Local,
    ) = UiState(
        enabled = c.enabled,
        transition = c.transition,
        failure = c.failure,
        helperLive = c.helperLive,
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
        needsMonitor = needsMonitor(config.shortcuts, c.monitor),
        apps = l.installed,
        loadingApps = l.loadingApps,
        writing = l.writing,
        message = l.message,
        report = l.report,
    )

    val state: StateFlow<UiState> =
        combine(
            coordinator.state, coordinator.config, coordinator.recording, draft, local, ::render,
        )
            .stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                // Seeded from the holder's live snapshot, not the defaults: a recreated screen
                // reads ON and "ready" on its first frame, with no dispatch in between.
                render(
                    coordinator.state.value, coordinator.config.value,
                    coordinator.recording.value, draft.value, local.value,
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

    /** The row switch. A Rejected (enabling a parked duplicate) is posted as a snackbar; the
     *  row's switch itself snaps back, since it only ever shows the config. */
    fun setShortcutEnabled(id: String, on: Boolean) {
        writes.launch {
            val outcome = coordinator.update { it.withEnabled(id, on) }
            Log.i(TAG, "EDIT ${if (on) "enabled" else "disabled"} $id -> ${outcome.text()}")
            if (outcome is UpdateOutcome.Rejected) post("rejected: ${outcome.reason}")
        }
    }

    /** Messages are numbered from 1 for the life of this ViewModel; the shell keys its
     *  snackbar effect on the number. Written on main only (every post is under [writes]). */
    private var posted = 0

    /** Queue [text] for the shell's snackbar, its first letter capitalised. */
    private fun post(text: String) {
        val shown = text.replaceFirstChar(Char::uppercase)
        local.update { it.copy(message = Message(++posted, shown)) }
    }

    /** The shell showed message [seq]: it is spent. A newer one, posted meanwhile, stays. */
    fun consumed(seq: Int) {
        local.update { if (it.message?.seq == seq) it.copy(message = null) else it }
    }

    /** The shell's OK on the import report. */
    fun dismissReport() = local.update { it.copy(report = null) }

    fun add() {
        draftSession++
        draft.value = EditorDraft()
    }

    fun edit(id: String) {
        val s = coordinator.config.value.shortcut(id) ?: return
        draftSession++
        draft.value = EditorDraft.of(s)
    }

    fun setScope(kind: ScopeKind) = change { it.copy(scope = kind) }

    fun toggleApp(packageName: String) = change {
        val apps = if (packageName in it.apps) it.apps - packageName else it.apps + packageName
        it.copy(apps = apps)
    }

    /**
     * Open the picker at once, and resolve the installed-app list the first time — off main,
     * since each app's icon is rendered to a bitmap on the way (tens of apps, a few hundred
     * ms); the picker shows a spinner until the list lands. Resolved once per ViewModel.
     */
    fun openPicker() {
        change { it.copy(picker = true) }
        if (local.value.installed.isNotEmpty() || local.value.loadingApps) return
        local.update { it.copy(loadingApps = true) }
        viewModelScope.launch {
            try {
                val apps = withContext(Dispatchers.IO) { resolveInstalled() }
                local.update { it.copy(installed = apps) }
            } finally {
                local.update { it.copy(loadingApps = false) }
            }
        }
    }

    fun closePicker() = change { it.copy(picker = false) }

    /** Every launcher app, one per package, by label; its icon at [ICON_DP] or null when the
     *  icon cannot be loaded (a broken package's resources — the row shows a blank circle). */
    private fun resolveInstalled(): List<InstalledApp> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val px = (ICON_DP * density).roundToInt()
        @Suppress("DEPRECATION")
        return pm.queryIntentActivities(intent, 0)
            .associateBy { it.activityInfo.packageName }
            .map { (packageName, info) ->
                val icon = runCatching { info.loadIcon(pm).toBitmap(px, px).asImageBitmap() }
                InstalledApp(packageName, info.loadLabel(pm).toString(), icon.getOrNull())
            }
            .sortedBy { it.label.lowercase() }
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

    /** Disabled (not merely ignored) while a write is in flight (E7-2). */
    fun save() {
        val d = draft.value ?: return
        if (local.value.writing) return
        local.update { it.copy(writing = true) }
        val session = draftSession
        stopRecording()
        writes.launch {
            try {
                val outcome = coordinator.update { config ->
                    val s = d.toShortcut()
                    if (d.original == null) config.added(s) else config.replaced(s)
                }
                val verb = if (d.original == null) "added" else "replaced"
                Log.i(TAG, "EDIT $verb ${d.id.trim()} -> ${outcome.text()}")
                finish(outcome, session)
            } finally {
                local.update { it.copy(writing = false) }
            }
        }
    }

    /** Disabled while a write is in flight, like [save]. */
    fun delete() {
        val id = draft.value?.original?.id ?: return
        if (local.value.writing) return
        local.update { it.copy(writing = true) }
        val session = draftSession
        stopRecording()
        writes.launch {
            try {
                val outcome = coordinator.update { it.without(id) }
                Log.i(TAG, "EDIT removed $id -> ${outcome.text()}")
                finish(outcome, session)
            } finally {
                local.update { it.copy(writing = false) }
            }
        }
    }

    /**
     * Export to the file the Save-As picker returned: the write on IO, the outcome on the
     * config line (the coordinator's). `wt`, so an existing file picked for overwriting is
     * truncated — on some providers `w` alone leaves the tail of a longer old text.
     */
    fun exportFile(uri: Uri) {
        writes.launch {
            val outcome = withContext(Dispatchers.IO) {
                coordinator.exportTo { text ->
                    val out = resolver.openOutputStream(uri, "wt")
                        ?: throw IOException("could not open the file")
                    out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                }
            }
            post(outcome)
        }
    }

    /**
     * Import the file the picker returned, as a merge that never replaces (parent §10): the
     * read on IO and bounded ([ImportReader] — a mis-pick cannot load gigabytes), the merge
     * and its report the coordinator's.
     */
    fun importFile(uri: Uri) {
        writes.launch {
            val outcome = withContext(Dispatchers.IO) {
                coordinator.importFrom {
                    val input = resolver.openInputStream(uri)
                        ?: throw IOException("could not open the file")
                    input.use { ImportReader.read(it, MAX_IMPORT_BYTES) }
                }
            }
            // A merge that ran is a report (a dialog); a failure is one line (a snackbar).
            when (outcome) {
                is ImportOutcome.Merged ->
                    local.update { it.copy(report = importReport(outcome.result)) }
                is ImportOutcome.Failed -> post(outcome.line)
            }
        }
    }

    fun cancel() {
        draftSession++
        stopRecording()
        draft.value = null
    }

    /** Applied closes the editor; Rejected keeps it open with the reason under Save — but
     *  only while this is still the draft the write was started for (E7-1). */
    private fun finish(outcome: UpdateOutcome, session: Int) {
        if (session != draftSession) return
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
        /** The import's cap: a 64-rule config is tens of KB; a megabyte is a mis-pick. */
        const val MAX_IMPORT_BYTES = 1 shl 20
    }
}
