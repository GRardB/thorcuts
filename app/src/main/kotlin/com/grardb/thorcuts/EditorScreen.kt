package com.grardb.thorcuts

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect

/** The indent of a choice nested under its parent — a radio's width, so it reads as a child. */
private val NESTED = 32.dp

/**
 * One shortcut being added or edited: its name (editable on an existing one too: a rename),
 * the recorder, Tap or Timer, the action (a select) and, under it, the app it opens, its
 * percent and its screen, where it applies and — nested — the chosen apps, Save / Cancel /
 * Delete (which asks first). Nothing here is privileged. A recording ends with the
 * combo's release (the ViewModel), on Cancel and Save, on the timeout, and when this screen
 * stops or leaves composition — the gate must not stay shut behind a screen that is gone.
 */
@Composable
fun EditorScreen(state: UiState, draft: EditorDraft, vm: ThorcutsViewModel) {
    BackHandler(onBack = vm::cancel)
    LifecycleStartEffect(Unit) {
        onStopOrDispose { vm.stopRecording() }
    }
    Column(modifier = Modifier.fillMaxSize()) {
        ScreenBar(
            title = if (draft.original == null) "New shortcut" else "Edit shortcut",
            onBack = vm::cancel,
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // The name: the file (as its id), the reasons and the log name it. Editable on an
            // existing shortcut too — Save replaces the one it opened, under its old name.
            SectionLabel("Name")
            OutlinedTextField(
                value = draft.id,
                onValueChange = vm::setId,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            SectionLabel("Trigger")
            val recording = state.recording
            when {
                recording != null -> {
                    Text(Copy.RECORDING, style = MaterialTheme.typography.bodyMedium)
                    HeldPieces(recording.held.toList())
                }
                !draft.recorded ->
                    Text(Copy.NOT_RECORDED, style = MaterialTheme.typography.bodyMedium)
                else -> {
                    val trigger = runCatching { draft.trigger() }.getOrNull()
                    if (trigger != null) {
                        Combo(trigger)
                    } else {
                        HeldPieces(draft.modifiers.map { it.control } + draft.buttons)
                    }
                }
            }
            if (draft.recorded && draft.modifiers.isEmpty()) {
                Text(
                    Copy.NEEDS_MODIFIER,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            PrimaryButton(
                onClick = vm::startRecording,
                enabled = state.helperLive && recording == null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state.helperLive) Copy.RECORD else Copy.RECORD_OFF)
            }
            state.modifierNote?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

            SectionLabel("Style")
            ChoiceGroup {
                ChoiceRow("Tap", selected = !draft.timer) { vm.setTimer(false) }
                ChoiceRow("Timer", selected = draft.timer) { vm.setTimer(true) }
            }
            if (draft.timer) {
                OutlinedTextField(
                    value = draft.holdMs,
                    onValueChange = vm::setHoldMs,
                    label = { Text("Milliseconds") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            state.warning?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

            // The action is a select: nine choices are too many for radios. What it needs sits
            // flush beneath it — the app an "Open an app" opens, the percent a brightness
            // action takes, then the screen the screen actions aim at ("Both screens" only
            // for the brightness ones: ActionKind.screens) — in sight the moment the action
            // is picked, and in the order the list reads it ("Increase brightness by 10% on
            // both screens"). No indent: a full-width field gives one nothing to hang from.
            val installed = state.apps.associateBy { it.packageName }
            SectionLabel("Action")
            ActionSelect(selected = draft.action, onSelect = vm::setAction)
            if (draft.action == ActionKind.OPEN) {
                val pkg = draft.openApp
                if (pkg == null) {
                    Text(Copy.NO_APP, style = MaterialTheme.typography.bodySmall)
                } else {
                    ChosenApp(installed[pkg], pkg)
                }
                FilledTonalButton(
                    onClick = vm::openAppPicker,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Choose app…") }
            }
            if (draft.action.takesPercent) {
                OutlinedTextField(
                    value = draft.percent,
                    onValueChange = vm::setPercent,
                    label = { Text("Percent") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (draft.action.takesScreen) {
                ChoiceGroup {
                    for (target in draft.action.screens) {
                        ChoiceRow(target.choiceLabel(), selected = draft.screen == target) {
                            vm.setScreen(target)
                        }
                    }
                }
            }

            // Likewise the chosen apps, by icon and name, under the scope they belong to.
            SectionLabel("Where")
            ChoiceGroup {
                for (kind in ScopeKind.entries) {
                    ChoiceRow(kind.label, selected = draft.scope == kind) { vm.setScope(kind) }
                    if (draft.scope == kind && kind != ScopeKind.EVERYWHERE) {
                        Column(
                            modifier = Modifier.padding(start = NESTED),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (draft.apps.isEmpty()) {
                                Text(Copy.NO_APPS, style = MaterialTheme.typography.bodySmall)
                            }
                            val byName =
                                draft.apps.sortedBy { installed[it]?.label?.lowercase() ?: it }
                            for (pkg in byName) ChosenApp(installed[pkg], pkg)
                            FilledTonalButton(
                                onClick = vm::openPicker,
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Choose apps…") }
                        }
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
            draft.error?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PrimaryButton(
                    onClick = vm::save,
                    enabled = !state.writing,
                    modifier = Modifier.weight(1f),
                ) { Text("Save") }
                SecondaryButton(onClick = vm::cancel, modifier = Modifier.weight(1f)) {
                    Text("Cancel")
                }
            }
            if (draft.original != null) {
                // Delete asks first: the dialog's flag survives a recreate with the screen.
                var confirmDelete by rememberSaveable { mutableStateOf(false) }
                SecondaryButton(
                    onClick = { confirmDelete = true },
                    enabled = !state.writing,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Delete") }
                if (confirmDelete) {
                    AlertDialog(
                        onDismissRequest = { confirmDelete = false },
                        // The saved name, not the one being typed: that is what goes.
                        title = { Text("Delete '${draft.original.id}'?") },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    confirmDelete = false
                                    vm.delete()
                                },
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error,
                                ),
                            ) { Text("Delete") }
                        },
                        dismissButton = {
                            TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
                        },
                    )
                }
            }
        }
    }
}

/** A section's heading: the accent colour, a rule above it so the sections read apart. */
@Composable
private fun SectionLabel(text: String) {
    HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

/** A group of [ChoiceRow]s, one of them selected: the semantics a screen reader needs to say
 *  "2 of 3", and the rows spaced as the editor's column spaces everything else. */
@Composable
private fun ChoiceGroup(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

/** One choice of a few: a radio and its label, the whole row ONE tap target with the radio's
 *  role (the radio itself has no click, so a screen reader announces the row once). */
@Composable
private fun ChoiceRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            // No click of its own, so Material3 would skip its 48 dp box; the row is the
            // target, and the control keeps the box so the row's height and the label's
            // place are what they were.
            modifier = Modifier.minimumInteractiveComponentSize(),
        )
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

/** The action, as a select: the field reads the chosen one as the menu words it (an ellipsis
 *  on the seven that aim at a screen, since that choice follows beneath), and the menu, as wide
 *  as the field, lists the nine in the editor's order. The box gives the field the dropdown
 *  role; `PrimaryNotEditable` makes the whole field the tap target and keeps the keyboard
 *  away. Open or shut is a plain `remember`: a menu is transient. The exposed dropdown is
 *  still experimental in material3 1.4; the opt-in is confined to this one composable. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActionSelect(selected: ActionKind, onSelect: (ActionKind) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected.choiceLabel,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (kind in ActionKind.entries) {
                DropdownMenuItem(
                    text = { Text(kind.choiceLabel) },
                    onClick = {
                        onSelect(kind)
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
}

/** One chosen app: its icon in a small circle (a blank one while the list loads, or when it
 *  is not installed) and its name (its package when it is not installed). */
@Composable
private fun ChosenApp(app: InstalledApp?, packageName: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val circle = Modifier.size(24.dp).clip(CircleShape)
        val icon = app?.icon
        if (icon != null) {
            Image(bitmap = icon, contentDescription = null, modifier = circle)
        } else {
            Box(modifier = circle.background(MaterialTheme.colorScheme.surfaceVariant))
        }
        Text(app?.label ?: packageName, style = MaterialTheme.typography.bodyMedium)
    }
}
