package com.grardb.thorcuts

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.grardb.thorcuts.core.model.Shortcut

private const val TITLE = "Shortcuts"
private const val ADD = "Add shortcut"
private const val EMPTY = "No shortcuts yet"
private const val NEEDS_MONITOR = "Per-app shortcuts need the accessibility service."
private const val TURN_ON = "Turn on"

/**
 * The master switch, the one conditional notice, the list in a card, Add — ONE scrolling
 * list, so nothing is pushed off the Thor's shorter bottom panel whatever the system font
 * size. Nothing diagnostic: a start failure is the one line under the switch, every other
 * outcome is the shell's snackbar.
 */
@Composable
fun ShortcutsScreen(state: UiState, vm: ThorcutsViewModel, listState: LazyListState) {
    // Turning ON asks for the notification permission first — on API 33+ the service's
    // notification is invisible without it — and turns on whatever the answer; the contract
    // returns at once when already granted, so the dialog shows once. OFF needs nothing.
    val askNotifications = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { vm.setEnabled(true) }
    val onSwitch: (Boolean) -> Unit = { on ->
        when {
            !on -> vm.setEnabled(false)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            else -> vm.setEnabled(true)
        }
    }
    // The accessibility-settings page, where the user turns the monitor on. NEW_TASK: started
    // from a composition context, not an Activity result.
    val context = LocalContext.current
    val onMonitor: () -> Unit = {
        context.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { MasterRow(state, onSwitch) }
        if (state.needsMonitor) item { MonitorNotice(onMonitor) }
        item {
            ShortcutList(
                shortcuts = state.shortcuts,
                onToggle = { id, on -> vm.setShortcutEnabled(id, on) },
                onEdit = vm::edit,
            )
        }
        item {
            PrimaryButton(onClick = vm::add, modifier = Modifier.fillMaxWidth()) { Text(ADD) }
        }
    }
}

/** "Shortcuts" and the switch. DISABLED while a start or stop is in flight (H3-7): the
 *  coordinator ignores a call during a transition. Under the title, the transition text while
 *  there is one, else the last start's failure in the error colour, else nothing. */
@Composable
private fun MasterRow(state: UiState, onSwitch: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(TITLE, style = MaterialTheme.typography.titleMedium)
            val transition = state.transition
            val line = transition ?: state.failure
            if (line != null) {
                Text(
                    line,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (transition != null) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.error,
                )
            }
        }
        Switch(
            checked = state.enabled,
            onCheckedChange = onSwitch,
            enabled = state.transition == null,
        )
    }
}

/** Shown only while a shortcut has a per-app scope and the accessibility service is off. */
@Composable
private fun MonitorNotice(onMonitor: () -> Unit) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                NEEDS_MONITOR,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).padding(vertical = 12.dp),
            )
            TextButton(onClick = onMonitor) { Text(TURN_ON) }
        }
    }
}

/** The rows in one outlined card, a divider between them; the empty list says so. */
@Composable
private fun ShortcutList(
    shortcuts: List<Shortcut>,
    onToggle: (String, Boolean) -> Unit,
    onEdit: (String) -> Unit,
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        if (shortcuts.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    EMPTY,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Column {
                shortcuts.forEachIndexed { i, s ->
                    if (i > 0) HorizontalDivider()
                    ShortcutRow(
                        shortcut = s,
                        onToggle = { onToggle(s.id, it) },
                        onEdit = { onEdit(s.id) },
                    )
                }
            }
        }
    }
}

/** One shortcut: its id, its combo as pieces, its action and scope on one line; the switch
 *  is its enabled flag. The row opens the editor. */
@Composable
private fun ShortcutRow(shortcut: Shortcut, onToggle: (Boolean) -> Unit, onEdit: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f).padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(shortcut.id, style = MaterialTheme.typography.bodyLarge)
            Combo(shortcut.trigger)
            Text(
                listOfNotNull(shortcut.action.label(), shortcut.scope.line()).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = shortcut.enabled, onCheckedChange = onToggle)
    }
}
