package com.grardb.thorcuts

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

/**
 * The app picker: a search field, then every installed launcher app by label with its icon
 * and a checkbox — and first any package the draft already names that is not installed
 * (shown by package, no icon, so it can be unchecked; not filtered). Done, or Back, returns
 * to the editor. The list and its icons are resolved once, off main, the first time the
 * picker opens; a spinner stands in until they land.
 */
@Composable
fun PickerScreen(state: UiState, draft: EditorDraft, vm: ThorcutsViewModel) {
    BackHandler(onBack = vm::closePicker)
    var query by rememberSaveable { mutableStateOf("") }
    val installed = state.apps.mapTo(mutableSetOf()) { it.packageName }
    val extras = draft.apps.filter { it !in installed }.sorted()
    val shown = filterApps(state.apps, query)
    Column(modifier = Modifier.fillMaxSize()) {
        ScreenBar(title = "Choose apps", onBack = vm::closePicker)
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search apps") },
            leadingIcon = {
                Icon(painterResource(R.drawable.ic_search), contentDescription = null)
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        if (state.loadingApps) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                contentPadding = PaddingValues(vertical = 12.dp),
            ) {
                items(extras) { pkg ->
                    AppRow(pkg, pkg, icon = null, checked = pkg in draft.apps) {
                        vm.toggleApp(pkg)
                    }
                }
                items(shown, key = { it.packageName }) { app ->
                    AppRow(app.label, app.packageName, app.icon, app.packageName in draft.apps) {
                        vm.toggleApp(app.packageName)
                    }
                }
                item {
                    PrimaryButton(
                        onClick = vm::closePicker,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    ) { Text("Done") }
                }
            }
        }
    }
}

/** One app: its icon in a circle (a blank one when there is none), the label and the
 *  package, a checkbox; the whole row toggles it. */
@Composable
private fun AppRow(
    label: String,
    pkg: String,
    icon: ImageBitmap?,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val circle = Modifier.size(40.dp).clip(CircleShape)
        if (icon != null) {
            Image(bitmap = icon, contentDescription = null, modifier = circle)
        } else {
            Box(modifier = circle.background(MaterialTheme.colorScheme.surfaceVariant))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                pkg,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
    }
}
