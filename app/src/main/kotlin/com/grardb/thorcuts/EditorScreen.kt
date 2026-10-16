package com.grardb.thorcuts

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import com.grardb.thorcuts.core.model.ScreenTarget
import com.grardb.thorcuts.core.model.ThorControls

/**
 * One shortcut being added or edited: its id, the recorder, Tap or Timer, the action and
 * its screen, Save / Cancel / Delete. Nothing here is privileged. A recording ends with the
 * combo's release (the ViewModel), on Cancel and Save, on the timeout, and when this screen
 * stops or leaves composition — the gate must not stay shut behind a screen that is gone.
 */
@Composable
fun EditorScreen(state: UiState, draft: EditorDraft, vm: ThorcutsViewModel) {
    BackHandler(onBack = vm::cancel)
    LifecycleStartEffect(Unit) {
        onStopOrDispose { vm.stopRecording() }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(12.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            if (draft.original == null) "New shortcut" else "Edit shortcut",
            style = MaterialTheme.typography.titleMedium,
        )
        // The id is typed once: the file, the reasons and the log name it.
        OutlinedTextField(
            value = draft.id,
            onValueChange = vm::setId,
            label = { Text("Id") },
            readOnly = draft.original != null,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        HorizontalDivider()
        Text("Trigger", style = MaterialTheme.typography.labelMedium)
        val recording = state.recording
        if (recording != null) {
            Text(Copy.RECORDING, style = MaterialTheme.typography.bodyMedium)
            Text(
                recording.held.joinToString(" + ") { ThorControls.name(it) },
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            Text(draft.triggerText, style = MaterialTheme.typography.bodyMedium)
        }
        if (draft.recorded && draft.modifiers.isEmpty()) {
            Text(Copy.NEEDS_MODIFIER, style = MaterialTheme.typography.bodySmall)
        }
        OutlinedButton(
            onClick = vm::startRecording,
            enabled = state.helperLive && recording == null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (state.helperLive) Copy.RECORD else Copy.RECORD_OFF)
        }
        state.modifierNote?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

        HorizontalDivider()
        Text("Style", style = MaterialTheme.typography.labelMedium)
        ChoiceRow("Tap", selected = !draft.timer) { vm.setTimer(false) }
        ChoiceRow("Timer", selected = draft.timer) { vm.setTimer(true) }
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

        HorizontalDivider()
        Text("Action", style = MaterialTheme.typography.labelMedium)
        for (kind in ActionKind.entries) {
            ChoiceRow(kind.label, selected = draft.action == kind) { vm.setAction(kind) }
        }
        if (draft.action.takesScreen) {
            for (target in ScreenTarget.entries) {
                ChoiceRow(target.choiceLabel(), selected = draft.screen == target) {
                    vm.setScreen(target)
                }
            }
        }

        HorizontalDivider()
        Text("Where", style = MaterialTheme.typography.labelMedium)
        for (kind in ScopeKind.entries) {
            ChoiceRow(kind.label, selected = draft.scope == kind) { vm.setScope(kind) }
        }
        if (draft.scope != ScopeKind.EVERYWHERE) {
            Text(
                if (draft.apps.isEmpty()) Copy.NO_APPS else draft.apps.sorted().joinToString(", "),
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = vm::openPicker, modifier = Modifier.fillMaxWidth()) {
                Text("Choose apps…")
            }
        }

        HorizontalDivider()
        draft.error?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = vm::save,
                enabled = !state.writing,
                modifier = Modifier.weight(1f),
            ) { Text("Save") }
            OutlinedButton(onClick = vm::cancel, modifier = Modifier.weight(1f)) {
                Text("Cancel")
            }
        }
        if (draft.original != null) {
            OutlinedButton(
                onClick = vm::delete,
                enabled = !state.writing,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Delete") }
        }
    }
}

/** One choice of a few: a radio and its label, the whole row tappable. */
@Composable
private fun ChoiceRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
