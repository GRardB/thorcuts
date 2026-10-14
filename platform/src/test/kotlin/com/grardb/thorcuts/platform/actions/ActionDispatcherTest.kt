package com.grardb.thorcuts.platform.actions

import com.grardb.thorcuts.core.model.Action
import com.grardb.thorcuts.core.model.ScreenTarget
import com.grardb.thorcuts.platform.privilege.FakeGateway
import com.grardb.thorcuts.platform.privilege.FakeGateway.Call
import com.grardb.thorcuts.platform.tasks.TaskActions
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Each Action variant reaches exactly one TaskActions call with the right screen, and
 * the outcome line passes through unchanged. The calls are observed at the gateway —
 * that is the seam — so each test pins the whole path from Action to command.
 */
class ActionDispatcherTest {

    private val tool = "com.grardb.thorcuts.platform.tasks.TaskTool"

    private fun dispatcher(g: FakeGateway) =
        ActionDispatcher(TaskActions(g, "com.grardb.thorcuts/.MainActivity") { setOf("c.home") })

    private fun entryPoint(vararg args: String): Call = Call.EntryPoint(tool, args.toList())

    private fun script(command: String): Call =
        Call.Script(listOf("$command 2>&1 | tr '\\n' ' '"))

    @Test
    fun `SwapScreens runs the tool's swap`() {
        val g = FakeGateway("OK swapped 12@0 <-> 7@4")
        assertEquals("OK swapped 12@0 <-> 7@4", dispatcher(g).dispatch(Action.SwapScreens))
        assertEquals(listOf(entryPoint("swap", "0", "4")), g.calls)
    }

    @Test
    fun `MoveFocusedToOtherScreen runs the tool's move from the focused screen`() {
        val g = FakeGateway("OK moved 12@0 -> 4; display 4 had no app")
        assertEquals(
            "OK moved 12@0 -> 4; display 4 had no app",
            dispatcher(g).dispatch(Action.MoveFocusedToOtherScreen),
        )
        assertEquals(listOf(entryPoint("move", "focused", "0", "4")), g.calls)
    }

    @Test
    fun `CloseScreen runs the tool's close on its screen`() {
        val g = FakeGateway("OK closed 7@4 com.android.settings")
        assertEquals(
            "OK closed 7@4 com.android.settings",
            dispatcher(g).dispatch(Action.CloseScreen(ScreenTarget.BOTTOM)),
        )
        assertEquals(listOf(entryPoint("close", "4", "c.home")), g.calls)
    }

    @Test
    fun `GoHome sends a Home key for its screen`() {
        val g = FakeGateway("")
        assertEquals(
            "OK home sent to display 0",
            dispatcher(g).dispatch(Action.GoHome(ScreenTarget.TOP)),
        )
        assertEquals(listOf(script("input -d 0 keyevent KEYCODE_HOME")), g.calls)
    }

    @Test
    fun `OpenThorcuts starts the Activity on its screen`() {
        val g = FakeGateway("Starting: Intent { } ")
        assertEquals(
            "OK display 0: Starting: Intent { }",
            dispatcher(g).dispatch(Action.OpenThorcuts(ScreenTarget.TOP)),
        )
        assertEquals(
            listOf(script("am start --display 0 -n 'com.grardb.thorcuts/.MainActivity'")),
            g.calls,
        )
    }
}
