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
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import com.grardb.thorcuts.core.model.ScreenTarget

/** The indent of a choice nested under its parent — a radio's width, so it reads as a child. */
private val NESTED = 32.dp

/**
 * One shortcut being added or edited: its name (editable on an existing one too: a rename),
 * the recorder, Tap or Timer, the action and — nested under it — the app it opens and its
 * screen, where it applies and — nested — the chosen apps, Save / Cancel / Delete (which asks
 * first). Nothing here is privileged. A recording ends with the
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

            // The screen choice sits under the action it belongs to, indented, so it is in
            // sight the moment that action is picked; "Open an app" puts the app first,
            // the way the list reads it ("Open Dolphin on the bottom screen").
            val installed = state.apps.associateBy { it.packageName }
            SectionLabel("Action")
            ChoiceGroup {
                for (kind in ActionKind.entries) {
                    ChoiceRow(kind.choiceLabel, selected = draft.action == kind) {
                        vm.setAction(kind)
                    }
                    if (draft.action == kind && kind == ActionKind.OPEN) {
                        Column(
                            modifier = Modifier.padding(start = NESTED),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
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
                    }
                    if (draft.action == kind && kind.takesScreen) {
                        ChoiceGroup {
                            for (target in ScreenTarget.entries) {
                                ChoiceRow(
                                    target.choiceLabel(),
                                    selected = draft.screen == target,
                                    nested = true,
                                ) { vm.setScreen(target) }
                            }
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
 *  role (the radio itself has no click, so a screen reader announces the row once); [nested]
 *  indents it under the choice above. */
@Composable
private fun ChoiceRow(
    label: String,
    selected: Boolean,
    nested: Boolean = false,
    onSelect: () -> Unit,
) {
    Row(
        // The indent comes BEFORE the selectable, so a nested row's tap target and ripple
        // start at the indent too (the editor polish's final review).
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = if (nested) NESTED else 0.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyMedium)
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
