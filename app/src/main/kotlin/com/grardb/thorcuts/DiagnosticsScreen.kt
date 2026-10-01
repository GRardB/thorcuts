package com.grardb.thorcuts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * The M1 diagnostics screen. Deliberately dark and flat, with no Cards.
 *
 * This screen is parked on an AMOLED panel for long stretches while the device
 * tasks are verified, so it minimises lit pixels: a true-black background (black
 * pixels are OFF on AMOLED, so they neither burn nor draw power), no elevated
 * surfaces, and no large static blocks of colour. From Task 8 onward the live
 * event log occupies most of the screen, which keeps those pixels changing.
 */
@Composable
fun DiagnosticsScreen(vm: DiagnosticsViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Thorcuts M1", style = MaterialTheme.typography.titleMedium)
        HorizontalDivider()

        Text("Privilege — ${state.gatewayName}", style = MaterialTheme.typography.labelMedium)
        Text(state.privilege, style = MaterialTheme.typography.bodyMedium)
        if (state.idOutput.isNotBlank()) {
            Text(state.idOutput, style = MaterialTheme.typography.bodySmall)
        }
        Button(
            onClick = vm::probePrivilege,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Probe privilege")
        }
    }
}
