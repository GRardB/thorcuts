package com.grardb.thorcuts

import androidx.compose.ui.graphics.ImageBitmap
import com.grardb.thorcuts.core.config.MergeResult
import com.grardb.thorcuts.core.model.Scope
import com.grardb.thorcuts.core.model.Shortcut
import com.grardb.thorcuts.core.recorder.TriggerRecorder

/**
 * What the screens render: a view of the coordinator's state and config, plus what is the
 * ViewModel's own — the editor's draft, the picker's apps, the in-flight-write flag and the
 * last outcome to show. Built by [ThorcutsViewModel.render] alone. The pure rules beside it
 * ([needsMonitor], [filterApps]) are host-tested; nothing here touches Android.
 */
data class UiState(
    /** The persisted switch, and the transition text while a start or stop is in flight. */
    val enabled: Boolean,
    val transition: String?,
    /** Why the last start failed or the relaunch chain gave up, shown under the switch in the
     *  error colour; null otherwise (the coordinator clears it when the next start is decided). */
    val failure: String?,
    /** The helper is up and ready: Record is offered exactly then. */
    val helperLive: Boolean,
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
    /** A shortcut has a per-app scope while the accessibility service is off: the notice that
     *  says so shows exactly then. */
    val needsMonitor: Boolean,
    /** The installed apps the picker lists; empty until the picker is first opened. */
    val apps: List<InstalledApp>,
    /** The app list (and its icons) is being resolved: the picker shows a spinner. */
    val loadingApps: Boolean,
    /** A Save or Delete is in flight: the buttons are disabled on it (E7-2). */
    val writing: Boolean,
    /** The last outcome to show as a snackbar — a refused toggle, an export, an import — or
     *  null. [Message.seq] tells a new one from the last, even with the same text. */
    val message: Message?,
    /** An import's result, shown as a dialog until dismissed; null otherwise. */
    val report: Report?,
)

/** One snackbar: its text, and a number that grows with every post. */
data class Message(val seq: Int, val text: String)

/** An import's report: the counts as its title, one line per skipped or dropped shortcut as
 *  its body — a dialog, since the reasons overran a snackbar on the device. */
data class Report(val title: String, val body: String)

/** The report for a merge that ran: `Imported: a added, s skipped, d dropped`, then each skip
 *  and each drop with its reason (`?` for a drop with no name), or one line saying there were
 *  none. */
fun importReport(r: MergeResult): Report {
    val title = "Imported: ${r.added.size} added, ${r.skipped.size} skipped, " +
        "${r.dropped.size} dropped"
    val lines = r.skipped.map { "Skipped '${it.id}': ${it.reason}" } +
        r.dropped.map { "Dropped #${it.index} '${it.id ?: "?"}': ${it.reason}" }
    val body = if (lines.isEmpty()) "Nothing was skipped or dropped." else lines.joinToString("\n")
    return Report(title, body)
}

/** One installed app the picker can scope to: its package, its label for the list, and its
 *  icon at list size — null when it could not be loaded. */
data class InstalledApp(val packageName: String, val label: String, val icon: ImageBitmap?)

/** True when a shortcut's scope needs the app in front — an `Apps` scope, or a `Global` with
 *  exclusions — and the monitor is off, so that scope cannot be honoured. */
fun needsMonitor(shortcuts: List<Shortcut>, monitor: Boolean): Boolean =
    !monitor && shortcuts.any { s ->
        when (val scope = s.scope) {
            is Scope.Global -> scope.excluded.isNotEmpty()
            is Scope.Apps -> true
        }
    }

/** The picker's filter: a blank query is every app; otherwise the trimmed query, case
 *  insensitive, anywhere in the label or the package. */
fun filterApps(apps: List<InstalledApp>, query: String): List<InstalledApp> {
    val q = query.trim()
    if (q.isEmpty()) return apps
    return apps.filter {
        it.label.contains(q, ignoreCase = true) || it.packageName.contains(q, ignoreCase = true)
    }
}
