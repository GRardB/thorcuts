package com.grardb.thorcuts

import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The app picker: every installed launcher app by label with a checkbox, and first any
 * package the draft already names that is not installed (shown by package, so it can be
 * unchecked). Done returns to the editor. No search, no icons — the least UI for the choice.
 */
@Composable
fun PickerScreen(state: UiState, draft: EditorDraft, vm: ThorcutsViewModel) {
    BackHandler(onBack = vm::closePicker)
    val installed = state.apps.mapTo(mutableSetOf()) { it.packageName }
    val extras = draft.apps.filter { it !in installed }.sorted()
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(horizontal = 12.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item { Text("Choose apps", style = MaterialTheme.typography.titleMedium) }
        items(extras) { pkg ->
            AppRow(pkg, pkg, pkg in draft.apps) { vm.toggleApp(pkg) }
        }
        items(state.apps, key = { it.packageName }) { app ->
            AppRow(app.label, app.packageName, app.packageName in draft.apps) {
                vm.toggleApp(app.packageName)
            }
        }
        item {
            OutlinedButton(onClick = vm::closePicker, modifier = Modifier.fillMaxWidth()) {
                Text("Done")
            }
        }
    }
}

/** One app: a checkbox, the label and the package; the whole row toggles it. */
@Composable
private fun AppRow(label: String, pkg: String, checked: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(pkg, style = MaterialTheme.typography.bodySmall)
        }
    }
}
