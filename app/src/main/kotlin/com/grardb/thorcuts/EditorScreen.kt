package com.grardb.thorcuts

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import com.grardb.thorcuts.core.model.ScreenTarget

/**
 * One shortcut being added or edited: its id, the recorder, Tap or Timer, the action and
 * its screen, where it applies, Save / Cancel / Delete. Nothing here is privileged. A
 * recording ends with the combo's release (the ViewModel), on Cancel and Save, on the
 * timeout, and when this screen stops or leaves composition — the gate must not stay shut
 * behind a screen that is gone.
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
            // The id is typed once: the file, the reasons and the log name it.
            SectionLabel("Id")
            OutlinedTextField(
                value = draft.id,
                onValueChange = vm::setId,
                readOnly = draft.original != null,
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
                Text(Copy.NEEDS_MODIFIER, style = MaterialTheme.typography.bodySmall)
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

            SectionLabel("Action")
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

            SectionLabel("Where")
            for (kind in ScopeKind.entries) {
                ChoiceRow(kind.label, selected = draft.scope == kind) { vm.setScope(kind) }
            }
            if (draft.scope != ScopeKind.EVERYWHERE) {
                Text(
                    if (draft.apps.isEmpty()) Copy.NO_APPS
                    else draft.apps.sorted().joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                )
                FilledTonalButton(
                    onClick = vm::openPicker,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Choose apps…") }
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
                SecondaryButton(
                    onClick = vm::delete,
                    enabled = !state.writing,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Delete") }
            }
        }
    }
}

/** The editor's and the picker's top bar: a back arrow and the title. `TopAppBar` is still
 *  experimental in material3 1.4; the opt-in is confined to this one composable. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenBar(title: String, onBack: () -> Unit) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(painterResource(R.drawable.ic_glyph_back), contentDescription = "Back")
            }
        },
    )
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
