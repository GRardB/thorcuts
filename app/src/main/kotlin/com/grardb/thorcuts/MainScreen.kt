package com.grardb.thorcuts

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.grardb.thorcuts.core.model.Shortcut
import com.grardb.thorcuts.core.model.ThorControls

/**
 * The app: the main screen, or the editor while a draft is open. Dark and flat throughout —
 * a true-black background from the theme in [MainActivity], Material 3's dark defaults for
 * the text, outlined rather than filled buttons — with no Cards.
 */
@Composable
fun ThorcutsScreen(vm: ThorcutsViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val editor = state.editor
    if (editor == null) MainScreen(state, vm) else EditorScreen(state, editor, vm)
}

/**
 * The switch, the three status lines, the notes, the shortcut list, Add — ONE scrolling list,
 * so nothing is ever pushed off the Thor's shorter bottom panel: the header is as tall as its
 * status lines and notes happen to be (a rejected write's reason wraps; a larger system font
 * grows everything), and the rows and the Add button scroll with it. (Task 5's review: a
 * fixed header above a weighted list left Add, the only way into the editor, with nothing to
 * scroll once the header outgrew the panel.)
 */
@Composable
private fun MainScreen(state: UiState, vm: ThorcutsViewModel) {
    // The switch. Turning ON asks for the notification permission first — on API 33+
    // the service's notification is invisible without it — and turns on whatever the
    // answer; the contract returns at once when already granted, so the dialog shows
    // once. OFF needs nothing. The launcher is remembered here, on the screen that owns
    // the button, not inside a list item.
    val askNotifications = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { vm.setEnabled(true) }
    val onSwitch: () -> Unit = {
        when {
            state.enabled -> vm.setEnabled(false)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            else -> vm.setEnabled(true)
        }
    }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            // On the Thor (API 33) the system insets the window itself; this becomes
            // load-bearing on Android 15+, which forces edge-to-edge for targetSdk 35+.
            .safeDrawingPadding()
            .padding(horizontal = 12.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item { Header(state, onSwitch) }
        items(state.shortcuts, key = { it.id }) { s ->
            ShortcutRow(
                shortcut = s,
                onToggle = { vm.setShortcutEnabled(s.id, it) },
                onEdit = { vm.edit(s.id) },
            )
        }
        item {
            OutlinedButton(onClick = vm::add, modifier = Modifier.fillMaxWidth()) {
                Text("Add a shortcut")
            }
        }
    }
}

/** Everything above the rows: the title, the switch, the three status lines, the notes. */
@Composable
private fun Header(state: UiState, onSwitch: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Thorcuts", style = MaterialTheme.typography.titleMedium)

        // DISABLED while a start or stop is in flight (H3-7): the coordinator ignores a call
        // during a transition, and this is what keeps an OFF-during-start from being
        // swallowed. The text says which.
        OutlinedButton(
            onClick = onSwitch,
            enabled = state.transition == null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                state.transition
                    ?: if (state.enabled) "Shortcuts: ON — tap to turn off"
                    else "Shortcuts: OFF — tap to turn on"
            )
        }

        Text(state.helperState, style = MaterialTheme.typography.bodyMedium)
        Text(state.tableState, style = MaterialTheme.typography.bodyMedium)
        Text(state.configState, style = MaterialTheme.typography.bodyMedium)
        for (note in Copy.NOTES) Text(note, style = MaterialTheme.typography.bodySmall)

        HorizontalDivider()
        Text("Shortcuts", style = MaterialTheme.typography.labelMedium)
        if (state.shortcuts.isEmpty()) {
            Text("No shortcuts yet.", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** One shortcut: its id, its trigger, its action; the switch is its enabled flag. */
@Composable
private fun ShortcutRow(shortcut: Shortcut, onToggle: (Boolean) -> Unit, onEdit: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(shortcut.id, style = MaterialTheme.typography.bodyMedium)
            Text(
                ThorControls.describe(shortcut.trigger),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(shortcut.action.label(), style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = shortcut.enabled, onCheckedChange = onToggle)
    }
}
