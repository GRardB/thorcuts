package com.grardb.thorcuts

import android.net.Uri
import com.grardb.thorcuts.core.config.InMemoryConfigFile
import com.grardb.thorcuts.core.config.ONE_SHORTCUT_TEXT
import com.grardb.thorcuts.platform.coordinator.FakeSwitch
import com.grardb.thorcuts.platform.coordinator.ThorcutsCoordinator
import com.grardb.thorcuts.platform.helper.FakeChannel
import com.grardb.thorcuts.platform.log.RecordingLogger
import com.grardb.thorcuts.platform.privilege.FakeGateway
import com.grardb.thorcuts.platform.tasks.FakeScreenshots
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The ViewModel on the host, over the real coordinator with :platform's fakes and :core's
 * fixtures: what an import's two outcomes become on the screen — a report for the dialog, or
 * one line for the snackbar. Main is a test dispatcher (`viewModelScope`), the write scope and
 * the I/O dispatcher are Unconfined, so every step runs inline and the state is read after.
 *
 * Android-free only because the import path calls nothing in `android.*`; the edit paths
 * (`Log.i`) would throw "not mocked" on the host, needing `isReturnDefaultValues` or a seam.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ThorcutsViewModelTest {

    private val file = InMemoryConfigFile()

    private val coordinator = ThorcutsCoordinator(
        gateway = FakeGateway(),
        channel = { onMessage -> FakeChannel(onMessage) },
        file = file,
        switch = FakeSwitch(),
        keeper = {},
        homePackages = { emptySet() },
        launchComponent = { null },
        screenshots = FakeScreenshots(),
        log = RecordingLogger(),
        privileged = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        storeDispatcher = Dispatchers.Unconfined,
        clock = { 0L },
    )

    /** Never reached: these tests drive the stream seams, not the URI ones. */
    private val noFiles = object : PickedFiles {
        override fun read(uri: Uri): InputStream? = error("not a URI test")
        override fun write(uri: Uri): OutputStream? = error("not a URI test")
    }

    private fun viewModel() = ThorcutsViewModel(
        coordinator = coordinator,
        writes = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        files = noFiles,
        installedApps = { emptyList() },
        io = Dispatchers.Unconfined,
    )

    @BeforeTest
    fun mainIsATestDispatcher() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun mainIsRestored() = Dispatchers.resetMain()

    /** The state once [ready] holds; the combine behind it runs on Main, inline. Bounded. */
    private fun ThorcutsViewModel.stateWhen(ready: (UiState) -> Boolean): UiState =
        runBlocking { withTimeout(2_000) { state.first(ready) } }

    @Test
    fun `an import that merges lands the report for the dialog`() {
        val vm = viewModel()
        vm.importStream { ONE_SHORTCUT_TEXT.byteInputStream() }
        val s = vm.stateWhen { it.report != null }
        assertEquals("Imported: 1 added, 0 skipped, 0 dropped", s.report?.title)
        assertEquals("Nothing was skipped or dropped.", s.report?.body)
        assertNull(s.message)
        assertEquals(listOf("swap-screens"), coordinator.config.value.shortcuts.map { it.id })
    }

    @Test
    fun `an import whose read throws lands one line for the snackbar`() {
        val vm = viewModel()
        vm.importStream { throw IOException("boom") }
        val s = vm.stateWhen { it.message != null }
        assertEquals("Import failed: could not read: boom", s.message?.text)
        assertNull(s.report)
        assertEquals(1, file.writes.size)                       // the default at load; no more
    }
}
