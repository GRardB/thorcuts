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
import androidx.compose.material3.RadioButton
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
 * — and first any package the draft already names that is not installed (shown by package,
 * no icon, so it can be seen and unchosen; not filtered). Two uses, told apart by [picking]:
 * the scope's apps, each row a checkbox and Done (or Back) returning to the editor; or the
 * one app an "Open an app" shortcut opens, each row a radio, a tap choosing it and returning
 * at once. The list and its icons are resolved once, off main, the first time the picker
 * opens; a spinner stands in until they land.
 */
@Composable
fun PickerScreen(state: UiState, draft: EditorDraft, picking: Picking, vm: ThorcutsViewModel) {
    BackHandler(onBack = vm::closePicker)
    var query by rememberSaveable { mutableStateOf("") }
    val installed = state.apps.mapTo(mutableSetOf()) { it.packageName }
    val chosen: Set<String> = when (picking) {
        Picking.SCOPE_APPS -> draft.apps
        Picking.OPEN_APP -> setOfNotNull(draft.openApp)
    }
    val onRow: (String) -> Unit = when (picking) {
        Picking.SCOPE_APPS -> vm::toggleApp
        Picking.OPEN_APP -> vm::chooseApp
    }
    val extras = chosen.filter { it !in installed }.sorted()
    val shown = filterApps(state.apps, query)
    Column(modifier = Modifier.fillMaxSize()) {
        ScreenBar(
            title = when (picking) {
                Picking.SCOPE_APPS -> "Choose apps"
                Picking.OPEN_APP -> "Choose an app"
            },
            onBack = vm::closePicker,
        )
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
                    AppRow(pkg, pkg, icon = null, chosen = pkg in chosen, picking) { onRow(pkg) }
                }
                items(shown, key = { it.packageName }) { app ->
                    val pkg = app.packageName
                    AppRow(app.label, pkg, app.icon, chosen = pkg in chosen, picking) { onRow(pkg) }
                }
                if (picking == Picking.SCOPE_APPS) {
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
}

/** One app: its icon in a circle (a blank one when there is none), the label and the
 *  package, a checkbox (the scope's many) or a radio (the one to open); the whole row acts. */
@Composable
private fun AppRow(
    label: String,
    pkg: String,
    icon: ImageBitmap?,
    chosen: Boolean,
    picking: Picking,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
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
        when (picking) {
            Picking.SCOPE_APPS -> Checkbox(checked = chosen, onCheckedChange = { onSelect() })
            Picking.OPEN_APP -> RadioButton(selected = chosen, onClick = onSelect)
        }
    }
}
