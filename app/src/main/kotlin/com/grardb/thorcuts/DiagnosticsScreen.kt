package com.grardb.thorcuts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
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
 * This screen is parked on an AMOLED panel for long stretches while the device
 * tasks are verified, so it minimises lit pixels: a true-black background (black
 * pixels are OFF on AMOLED), mid-grey rather than near-white text, outlined rather
 * than filled buttons so there is no large solid block of colour, and the whole
 * layout slowly walks a few pixels so nothing holds one position. See BurnIn.kt.
 *
 * From Task 8 onward the live event log occupies most of the screen, which keeps
 * those pixels changing on their own.
 */
@Composable
fun DiagnosticsScreen(vm: DiagnosticsViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val shift = rememberBurnInShift()

    // Root: NOT scrollable. verticalScroll here would give its children infinite
    // height and the weight()s below would stop working — see the inner Column
    // and the LazyColumn, which split this space instead.
    Column(
        modifier = Modifier
            .fillMaxSize()
            // The system bars are hidden, so respect the safe area ourselves.
            .safeDrawingPadding()
            .offset(x = shift.x, y = shift.y)
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
                color = DIM_TEXT_EMPHASIS,
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
            if (state.lastSwap.isNotBlank()) {
                Text("Last swap: ${state.lastSwap}", style = MaterialTheme.typography.bodyMedium)
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = vm::startHelper, enabled = !state.busy, modifier = Modifier.weight(1f)) {
                    Text("Start helper")
                }
                OutlinedButton(onClick = vm::stopHelper, enabled = !state.busy, modifier = Modifier.weight(1f)) {
                    Text("Stop helper")
                }
            }
        }

        HorizontalDivider()

        // The live log gets the other half of the panel outright, not merely
        // whatever the static controls leave behind. It is the only large area
        // of the screen, and its pixels change constantly as events arrive —
        // which is what keeps a long diagnostics session off an AMOLED burn-in
        // path. Newest first, so the interesting line is always on top and the
        // list never needs auto-scrolling.
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
