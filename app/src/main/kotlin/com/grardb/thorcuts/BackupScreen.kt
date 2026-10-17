package com.grardb.thorcuts

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** What the Save-As dialog proposes; the user can rename it there. */
private const val EXPORT_NAME = "thorcuts-config.json"

/** The type Save-As stamps on the new file. */
private const val EXPORT_MIME = "application/json"

/** The open filter: every file, so a JSON file saved under the wrong type by a chat app is
 *  still pickable — a wrong pick is explained by the snackbar, a greyed-out right file by
 *  nothing. */
private const val ANY_FILE = "*/*"

private const val EXPLAIN = "Export saves your shortcuts to a file. Import adds shortcuts " +
    "from a file; it never replaces one you already have."

/**
 * One sentence and two buttons. Export and import go through the system's file picker (the
 * Storage Access Framework): CreateDocument is a browser's "Save as" dialog, OpenDocument its
 * <input type="file">. Each returns a content Uri the ViewModel writes or reads once, off
 * main; no permission, no provider of our own. A cancelled picker returns null. The outcome
 * is the shell's snackbar.
 */
@Composable
fun BackupScreen(vm: ThorcutsViewModel) {
    val exportTo = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(EXPORT_MIME),
    ) { uri -> if (uri != null) vm.exportFile(uri) }
    val importFrom = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) vm.importFile(uri) }
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(EXPLAIN, style = MaterialTheme.typography.bodyMedium)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PrimaryButton(
                onClick = { exportTo.launch(EXPORT_NAME) },
                modifier = Modifier.weight(1f),
            ) { Text("Export…") }
            PrimaryButton(
                onClick = { importFrom.launch(arrayOf(ANY_FILE)) },
                modifier = Modifier.weight(1f),
            ) { Text("Import…") }
        }
    }
}
