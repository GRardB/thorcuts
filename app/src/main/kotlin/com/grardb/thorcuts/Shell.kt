package com.grardb.thorcuts

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** The rail's two entries. */
enum class Tab(val label: String, val icon: Int) {
    SHORTCUTS("Shortcuts", R.drawable.ic_rail_shortcuts),
    BACKUP("Backup", R.drawable.ic_rail_backup),
}

/**
 * The app: the theme, the snackbar, and — while no draft is open — a rail down the left with
 * the Shortcuts and Backup screens beside it; the editor and the picker take the full width,
 * and Back returns. The shell pads for the system bars once (`safeDrawingPadding` consumes
 * them for every screen under it), so the Scaffold is told to handle none.
 */
@Composable
fun ThorcutsScreen(vm: ThorcutsViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    // Hoisted above the `when`, so the list keeps its scroll position across a visit to the
    // editor, the picker or the Backup tab.
    val listState = rememberLazyListState()
    var tab by rememberSaveable { mutableStateOf(Tab.SHORTCUTS) }
    val snackbar = remember { SnackbarHostState() }
    // The message is the ViewModel's until it has been shown: `consumed` clears it, so an
    // Activity recreate (the Thor's screen swap) finds nothing to show again, and a process
    // death starts with none — no saved watermark to collide with a fresh ViewModel's seq
    // (the final review, twice). A show cut short by a recreate is shown again, which is
    // right; a newer message cancels the effect, and consuming the older one is a no-op.
    val message = state.message
    LaunchedEffect(message?.seq) {
        if (message != null) {
            snackbar.showSnackbar(message.text)
            vm.consumed(message.seq)
        }
    }
    ThorcutsTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                modifier = Modifier.safeDrawingPadding(),
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                snackbarHost = { SnackbarHost(snackbar) },
            ) { padding ->
                val editor = state.editor
                Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                    when {
                        editor == null -> Row(modifier = Modifier.fillMaxSize()) {
                            NavigationRail {
                                for (t in Tab.entries) {
                                    NavigationRailItem(
                                        selected = tab == t,
                                        onClick = { tab = t },
                                        icon = { Icon(painterResource(t.icon), null) },
                                        label = { Text(t.label) },
                                    )
                                }
                            }
                            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                                when (tab) {
                                    Tab.SHORTCUTS -> ShortcutsScreen(state, vm, listState)
                                    Tab.BACKUP -> BackupScreen(vm)
                                }
                            }
                        }
                        editor.picker != null -> PickerScreen(state, editor, editor.picker, vm)
                        else -> EditorScreen(state, editor, vm)
                    }
                }
            }
            // An import's report: a dialog, since its reasons overran a snackbar on the
            // device (the UI polish's device run); OK or a tap outside dismisses it.
            state.report?.let { report ->
                AlertDialog(
                    onDismissRequest = vm::dismissReport,
                    title = { Text(report.title) },
                    text = {
                        Text(
                            report.body,
                            modifier = Modifier.verticalScroll(rememberScrollState()),
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = vm::dismissReport) { Text("OK") }
                    },
                )
            }
        }
    }
}
