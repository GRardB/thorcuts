package com.grardb.thorcuts

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * The M1 diagnostics screen. Deliberately dark and flat, with no Cards.
 *
 * The look is all-dark throughout: a true-black background from the theme in
 * [MainActivity], Material 3's dark defaults for the text, and outlined rather
 * than filled buttons so there is no large solid block of colour.
 *
 * From Task 8 onward the live event log occupies most of the screen; the static
 * controls take the other half and scroll on their own account.
 */
@Composable
fun DiagnosticsScreen(vm: DiagnosticsViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()

    // Root: NOT scrollable. verticalScroll here would give its children infinite
    // height and the weight()s below would stop working — see the inner Column
    // and the LazyColumn, which split this space instead.
    Column(
        modifier = Modifier
            .fillMaxSize()
            // Kept although this app no longer opts into edge-to-edge: on the
            // Thor (API 33) the system insets the window itself, so this adds
            // nothing today. It becomes load-bearing on Android 15+, which forces
            // edge-to-edge on anything targeting SDK 35 or above — and targetSdk
            // here is 37. It also covers display cutouts either way.
            .safeDrawingPadding()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // All the static controls, confined to half the panel and scrollable on
        // their own account so they can never crowd the log out — which is what
        // was happening before this split: the log only had whatever was left
        // over after this content, often just three or four rows.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Thorcuts M1", style = MaterialTheme.typography.titleMedium)
            HorizontalDivider()

            Text("Privilege — ${state.gatewayName}", style = MaterialTheme.typography.labelMedium)
            Text(
                state.privilege,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (state.idOutput.isNotBlank()) {
                Text(state.idOutput, style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(
                onClick = vm::probePrivilege,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Probe privilege")
            }

            HorizontalDivider()

            Text("Screens", style = MaterialTheme.typography.labelMedium)
            if (state.lastOutcome.isNotBlank()) {
                Text("Last action: ${state.lastOutcome}", style = MaterialTheme.typography.bodyMedium)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = vm::swapScreens, enabled = !state.busy, modifier = Modifier.weight(1f)) {
                    Text("Swap")
                }
                OutlinedButton(onClick = vm::listTasks, enabled = !state.busy, modifier = Modifier.weight(1f)) {
                    Text("List tasks")
                }
            }
            if (state.taskList.isNotBlank()) {
                Text(state.taskList, style = MaterialTheme.typography.bodySmall)
            }

            HorizontalDivider()

            Text("Input helper", style = MaterialTheme.typography.labelMedium)
            Text(state.helperState, style = MaterialTheme.typography.bodyMedium)
            Text(state.tableState, style = MaterialTheme.typography.bodyMedium)
            // The switch. Turning ON asks for the notification permission first — on API
            // 33+ the service's notification is invisible without it — and turns on whatever
            // the answer; the contract returns at once when already granted, so the dialog
            // shows once. OFF needs nothing. Disabled while a start or stop is in flight,
            // and the text says which.
            val askNotifications = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { vm.setEnabled(true) }
            OutlinedButton(
                onClick = {
                    when {
                        state.enabled -> vm.setEnabled(false)
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                        else -> vm.setEnabled(true)
                    }
                },
                enabled = state.transition == null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    state.transition
                        ?: if (state.enabled) "Shortcuts: ON — tap to turn off"
                        else "Shortcuts: OFF — tap to turn on"
                )
            }

            HorizontalDivider()

            Text("Config", style = MaterialTheme.typography.labelMedium)
            Text(state.configState, style = MaterialTheme.typography.bodyMedium)
            // The one write surface until M3's editor: a real write through the config
            // store, so the device run can watch write -> recompile -> push -> ack. Not a
            // privileged call, so not gated on `busy`. Deleted with the rest of this screen.
            OutlinedButton(
                onClick = vm::toggleSwap,
                enabled = state.swapEnabled != null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    when (state.swapEnabled) {
                        true -> "Swap shortcut: ON — tap to disable"
                        false -> "Swap shortcut: OFF — tap to enable"
                        null -> "Swap shortcut: not in config"
                    }
                )
            }
        }

        HorizontalDivider()

        // The live log gets the other half of the panel outright, not merely
        // whatever the static controls leave behind — it is the one part of this
        // screen worth watching during a long session. Newest first, so the
        // interesting line is always on top and the list never needs
        // auto-scrolling.
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            items(state.eventLog) { row ->
                Text(
                    row,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}
