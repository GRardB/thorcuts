package com.grardb.thorcuts.platform.tasks

import com.grardb.thorcuts.core.model.ScreenTarget
import com.grardb.thorcuts.core.safety.CommandSafety
import com.grardb.thorcuts.platform.privilege.FakeGateway
import com.grardb.thorcuts.platform.privilege.FakeGateway.Call
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TaskActionsTest {

    private val tool = "com.grardb.thorcuts.platform.tasks.TaskTool"
    private val main = "com.grardb.thorcuts/.MainActivity"
    private val quotedMain = "'com.grardb.thorcuts/.MainActivity'"

    private fun entryPoint(vararg args: String): Call = Call.EntryPoint(tool, args.toList())

    /** One shell line as TaskActions builds it: stderr folded in, line breaks to spaces. */
    private fun script(command: String): Call =
        Call.Script(listOf("$command 2>&1 | tr '\\n' ' '"))

    private fun actions(g: FakeGateway) = TaskActions(g, main)

    // ── the tool's subcommands ────────────────────────────────────────────────────────

    @Test
    fun `listTasks runs the tool's list and returns its line verbatim`() {
        val g = FakeGateway("OK swappable 12@0=com.example")
        assertEquals("OK swappable 12@0=com.example", actions(g).listTasks())
        assertEquals(listOf(entryPoint("list")), g.calls)
    }

    @Test
    fun `swapScreens runs swap on the two measured display ids`() {
        val g = FakeGateway("OK swapped 12@0 <-> 7@4")
        assertEquals("OK swapped 12@0 <-> 7@4", actions(g).swapScreens())
        assertEquals(listOf(entryPoint("swap", "0", "4")), g.calls)
    }

    @Test
    fun `closeScreen passes the screen as the tool's argument`() {
        val cases = mapOf(
            ScreenTarget.TOP to "0",
            ScreenTarget.BOTTOM to "4",
            ScreenTarget.FOCUSED to "focused",
        )
        for ((screen, arg) in cases) {
            val g = FakeGateway("OK closed 12@$arg com.example")
            assertEquals("OK closed 12@$arg com.example", actions(g).closeScreen(screen))
            assertEquals(listOf(entryPoint("close", arg)), g.calls)
        }
    }

    @Test
    fun `moveFocusedToOtherScreen runs move focused between the two screens`() {
        val g = FakeGateway("ERR no app to move on display 4")
        assertEquals("ERR no app to move on display 4", actions(g).moveFocusedToOtherScreen())
        assertEquals(listOf(entryPoint("move", "focused", "0", "4")), g.calls)
    }

    // ── go home: a Home key for the display ───────────────────────────────────────────

    @Test
    fun `goHome sends a Home key for the display and reads silence as success`() {
        val bottom = FakeGateway(" ")   // tr leaves a trailing space; nothing else = success
        assertEquals("OK home sent to display 4", actions(bottom).goHome(ScreenTarget.BOTTOM))
        assertEquals(listOf(script("input -d 4 keyevent KEYCODE_HOME")), bottom.calls)

        val top = FakeGateway("")
        assertEquals("OK home sent to display 0", actions(top).goHome(ScreenTarget.TOP))
        assertEquals(listOf(script("input -d 0 keyevent KEYCODE_HOME")), top.calls)
    }

    @Test
    fun `goHome FOCUSED sends the key with no display - the physical Home button`() {
        val g = FakeGateway("")
        assertEquals(
            "OK home sent to the focused display",
            actions(g).goHome(ScreenTarget.FOCUSED),
        )
        assertEquals(listOf(script("input keyevent KEYCODE_HOME")), g.calls)
    }

    @Test
    fun `goHome reports whatever input printed as an ERR`() {
        val usage = "Error: Invalid arguments for display ID. Usage: input [<source>] ..."
        val g = FakeGateway("$usage ")
        assertEquals("ERR input: $usage", actions(g).goHome(ScreenTarget.BOTTOM))
    }

    // ── open Thorcuts: am start on the display ────────────────────────────────────────

    @Test
    fun `openThorcuts starts the Activity on the display with the component quoted`() {
        val g = FakeGateway("Starting: Intent { cmp=com.grardb.thorcuts/.MainActivity } ")
        assertEquals(
            "OK display 4: Starting: Intent { cmp=com.grardb.thorcuts/.MainActivity }",
            actions(g).openThorcuts(ScreenTarget.BOTTOM),
        )
        assertEquals(listOf(script("am start --display 4 -n $quotedMain")), g.calls)
    }

    @Test
    fun `openThorcuts reports am's Error line as an ERR`() {
        val out = "Starting: Intent { cmp=x/.Y } Error: Activity class {x/.Y} does not exist."
        val g = FakeGateway(out)
        assertEquals("ERR am: $out", actions(g).openThorcuts(ScreenTarget.TOP))
        assertEquals(listOf(script("am start --display 0 -n $quotedMain")), g.calls)
    }

    @Test
    fun `openThorcuts FOCUSED asks the tool which display first`() {
        val g = FakeGateway(
            "OK focused display=4 task=1548 pkg=org.dolphinemu.dolphinemu",
            "Starting: Intent { } ",
        )
        assertEquals(
            "OK display 4: Starting: Intent { }",
            actions(g).openThorcuts(ScreenTarget.FOCUSED),
        )
        assertEquals(
            listOf(entryPoint("focused"), script("am start --display 4 -n $quotedMain")),
            g.calls,
        )
    }

    @Test
    fun `openThorcuts FOCUSED returns the tool's ERR line and starts nothing`() {
        val g = FakeGateway("ERR no focused root task")
        assertEquals("ERR no focused root task", actions(g).openThorcuts(ScreenTarget.FOCUSED))
        assertEquals(listOf(entryPoint("focused")), g.calls)
    }

    @Test
    fun `openThorcuts FOCUSED refuses a focused line it cannot parse`() {
        val g = FakeGateway("OK focused but no display here")
        assertEquals(
            "ERR unexpected focused line: OK focused but no display here",
            actions(g).openThorcuts(ScreenTarget.FOCUSED),
        )
        assertEquals(listOf(entryPoint("focused")), g.calls)
    }

    // ── the contract every method keeps ───────────────────────────────────────────────

    @Test
    fun `no reply from pservice is one ERR line from every method, never a null`() {
        val noReply = "ERR no reply from pservice"
        assertEquals(noReply, actions(FakeGateway(null)).listTasks())
        assertEquals(noReply, actions(FakeGateway(null)).swapScreens())
        assertEquals(noReply, actions(FakeGateway(null)).closeScreen(ScreenTarget.TOP))
        assertEquals(noReply, actions(FakeGateway(null)).moveFocusedToOtherScreen())
        assertEquals(noReply, actions(FakeGateway(null)).goHome(ScreenTarget.TOP))
        assertEquals(noReply, actions(FakeGateway(null)).openThorcuts(ScreenTarget.TOP))
        // FOCUSED open: the tool answered, am did not.
        val toolThenNothing = FakeGateway("OK focused display=0 task=1 pkg=a.b", null)
        assertEquals(noReply, actions(toolThenNothing).openThorcuts(ScreenTarget.FOCUSED))
    }

    @Test
    fun `an empty tool reply is an ERR line, never an empty outcome`() {
        // app_process died before printing (Appendix B's stale-CLASSPATH "Aborted" with exit
        // 0): the gateway's grep matched nothing, so the reply is "" rather than null.
        val noLine = "ERR the tool printed no result line"
        assertEquals(noLine, actions(FakeGateway("")).listTasks())
        assertEquals(noLine, actions(FakeGateway(" ")).closeScreen(ScreenTarget.TOP))
        val g = FakeGateway("")
        assertEquals(noLine, actions(g).openThorcuts(ScreenTarget.FOCUSED))
        assertEquals(listOf(entryPoint("focused")), g.calls)   // and am never ran
    }

    @Test
    fun `the entry point names the tool class`() {
        assertEquals(TaskTool::class.java.name, tool)
    }

    @Test
    fun `every script line is one physical line`() {
        val g = FakeGateway("", "", "x", "x")
        val a = actions(g)
        a.goHome(ScreenTarget.TOP)
        a.goHome(ScreenTarget.FOCUSED)
        a.openThorcuts(ScreenTarget.TOP)
        a.openThorcuts(ScreenTarget.BOTTOM)
        val scripts = g.calls.filterIsInstance<Call.Script>().map { it.lines }
        assertEquals(4, scripts.size)
        for (lines in scripts) {
            assertEquals(1, lines.size)
            assertTrue(CommandSafety.isSingleLine(lines.single()), lines.single())
        }
    }

    @Test
    fun `a component that is not one is refused at construction`() {
        assertFailsWith<IllegalArgumentException> { TaskActions(FakeGateway(), "no slash") }
        assertFailsWith<IllegalArgumentException> { TaskActions(FakeGateway(), "a.b/.C; rm x") }
    }
}
