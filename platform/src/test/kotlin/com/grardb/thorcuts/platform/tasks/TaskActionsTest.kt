package com.grardb.thorcuts.platform.tasks

import com.grardb.thorcuts.core.model.ScreenTarget
import com.grardb.thorcuts.core.safety.CommandSafety
import com.grardb.thorcuts.platform.privilege.FakeGateway
import com.grardb.thorcuts.platform.privilege.FakeGateway.Call
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TaskActionsTest {

    private val tool = "com.grardb.thorcuts.platform.tasks.TaskTool"

    /** Thorcuts' own package, and its component as the app's resolver writes one. */
    private val thorcuts = "com.grardb.thorcuts"
    private val quotedMain = "'com.grardb.thorcuts/.MainActivity'"

    /** The resolver every test uses unless it says otherwise: a `.MainActivity` per package. */
    private val mainOf: (String) -> String? = { pkg -> "$pkg/.MainActivity" }

    private fun entryPoint(vararg args: String): Call = Call.EntryPoint(tool, args.toList())

    /** One shell line as TaskActions builds it: stderr folded in, line breaks to spaces. */
    private fun script(command: String): Call =
        Call.Script(listOf("$command 2>&1 | tr '\\n' ' '"))

    /** One launcher by default: with none resolved, close refuses before it reaches the tool. */
    private fun actions(
        g: FakeGateway,
        launchers: Set<String> = setOf("c.launcher"),
        resolve: (String) -> String? = mainOf,
    ) = TaskActions(g, { launchers }, resolve)

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
            assertEquals(listOf(entryPoint("close", arg, "c.launcher")), g.calls)
        }
    }

    @Test
    fun `closeScreen passes the launcher packages after the screen, sorted`() {
        val refused = "ERR refusing to close launcher rip.moth.cocoonshell on display 4"
        val g = FakeGateway(refused)
        val launchers = setOf("rip.moth.cocoonshell", "com.android.launcher3")
        assertEquals(refused, actions(g, launchers).closeScreen(ScreenTarget.BOTTOM))
        assertEquals(
            listOf(entryPoint("close", "4", "com.android.launcher3", "rip.moth.cocoonshell")),
            g.calls,
        )
    }

    @Test
    fun `closeScreen asks for the launchers on every call`() {
        var asked = 0
        val g = FakeGateway("OK closed 1@0 a.b", "OK closed 2@0 a.b")
        val a = TaskActions(g, { asked++; setOf("c.launcher") }, mainOf)
        a.closeScreen(ScreenTarget.TOP)
        a.closeScreen(ScreenTarget.TOP)
        assertEquals(2, asked)
        assertEquals(
            listOf(entryPoint("close", "0", "c.launcher"), entryPoint("close", "0", "c.launcher")),
            g.calls,
        )
    }

    @Test
    fun `closeScreen passes only names that are packages`() {
        val g = FakeGateway("OK closed 1@4 a.b")
        val a = TaskActions(g, { setOf("rip.moth.cocoonshell", "not a package; rm x") }, mainOf)
        a.closeScreen(ScreenTarget.BOTTOM)
        assertEquals(listOf(entryPoint("close", "4", "rip.moth.cocoonshell")), g.calls)
    }

    @Test
    fun `closeScreen answers an ERR line when the launchers cannot be resolved`() {
        val g = FakeGateway()
        val a = TaskActions(g, { throw IllegalStateException("pm dead") }, mainOf)
        val line = a.closeScreen(ScreenTarget.BOTTOM)
        assertTrue(line.startsWith("ERR launchers: "), line)
        assertTrue(g.calls.isEmpty())                  // nothing reached the tool
    }

    @Test
    fun `closeScreen refuses when no launcher resolved, and nothing reaches the tool`() {
        // Every device has a HOME handler, so an empty resolution means the query failed —
        // and a close with no names would remove a launcher that happened to be the app.
        val g = FakeGateway()
        val a = TaskActions(g, { emptySet() }, mainOf)
        assertEquals("ERR launchers: none resolved; not closing", a.closeScreen(ScreenTarget.TOP))
        assertTrue(g.calls.isEmpty())
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
        assertEquals(listOf(script("input -d 4 keyevent KEYCODE_HOME")), g.calls)
    }

    // ── open an app: its launch activity resolved, then am start on the display ───────

    @Test
    fun `openApp starts the Activity on the display with the component quoted`() {
        val g = FakeGateway("Starting: Intent { cmp=com.grardb.thorcuts/.MainActivity } ")
        assertEquals(
            "OK display 4: Starting: Intent { cmp=com.grardb.thorcuts/.MainActivity }",
            actions(g).openApp(thorcuts, ScreenTarget.BOTTOM),
        )
        assertEquals(listOf(script("am start --display 4 -n $quotedMain")), g.calls)
    }

    @Test
    fun `openApp reports am's Error line as an ERR`() {
        val out = "Starting: Intent { cmp=x/.Y } Error: Activity class {x/.Y} does not exist."
        val g = FakeGateway(out)
        assertEquals("ERR am: $out", actions(g).openApp(thorcuts, ScreenTarget.TOP))
        assertEquals(listOf(script("am start --display 0 -n $quotedMain")), g.calls)
    }

    @Test
    fun `openApp reports an exception in am's output as an ERR`() {
        // A Java trace from am has no "Error:" line, and it begins with "Starting:" like a
        // success does — which is why the classifier is negative, not startsWith.
        val out = "Starting: Intent { cmp=x/.Y } Exception occurred while executing 'start': " +
            "java.lang.SecurityException: Permission Denial"
        val g = FakeGateway(out)
        assertEquals("ERR am: $out", actions(g).openApp(thorcuts, ScreenTarget.TOP))
    }

    @Test
    fun `openApp FOCUSED asks the tool which display first`() {
        val g = FakeGateway(
            "OK focused display=4 task=1548 pkg=org.dolphinemu.dolphinemu",
            "Starting: Intent { } ",
        )
        assertEquals(
            "OK display 4: Starting: Intent { }",
            actions(g).openApp(thorcuts, ScreenTarget.FOCUSED),
        )
        assertEquals(
            listOf(entryPoint("focused"), script("am start --display 4 -n $quotedMain")),
            g.calls,
        )
    }

    @Test
    fun `openApp FOCUSED returns the tool's ERR line and starts nothing`() {
        val g = FakeGateway("ERR no focused root task")
        assertEquals("ERR no focused root task", actions(g).openApp(thorcuts, ScreenTarget.FOCUSED))
        assertEquals(listOf(entryPoint("focused")), g.calls)
    }

    @Test
    fun `openApp FOCUSED refuses a focused line it cannot parse`() {
        val g = FakeGateway("OK focused but no display here")
        assertEquals(
            "ERR unexpected focused line: OK focused but no display here",
            actions(g).openApp(thorcuts, ScreenTarget.FOCUSED),
        )
        assertEquals(listOf(entryPoint("focused")), g.calls)
    }

    @Test
    fun `openApp starts whatever component the resolver names for the package, quoted`() {
        val g = FakeGateway("Starting: Intent { } ")
        val a = actions(g) { pkg -> "$pkg/org.dolphinemu.dolphinemu.ui.MainActivity" }
        assertEquals(
            "OK display 0: Starting: Intent { }",
            a.openApp("org.dolphinemu.dolphinemu", ScreenTarget.TOP),
        )
        assertEquals(
            listOf(
                script(
                    "am start --display 0 -n " +
                        "'org.dolphinemu.dolphinemu/org.dolphinemu.dolphinemu.ui.MainActivity'",
                ),
            ),
            g.calls,
        )
    }

    @Test
    fun `openApp answers a line and runs nothing when the package has no launch activity`() {
        val g = FakeGateway()
        val a = actions(g) { null }
        assertEquals(
            "ERR no launch activity for org.gone.app",
            a.openApp("org.gone.app", ScreenTarget.BOTTOM),
        )
        // FOCUSED too: the component comes before the round trip for the display
        assertEquals(
            "ERR no launch activity for org.gone.app",
            a.openApp("org.gone.app", ScreenTarget.FOCUSED),
        )
        assertTrue(g.calls.isEmpty())
    }

    @Test
    fun `openApp answers a line when the resolver throws, or names a non-component`() {
        val g = FakeGateway()
        val dead = actions(g) { throw IllegalStateException("pm dead") }
        assertEquals(
            "ERR launch activity: java.lang.IllegalStateException: pm dead",
            dead.openApp(thorcuts, ScreenTarget.TOP),
        )
        val odd = actions(g) { "not a component; rm -rf /" }
        assertEquals(
            "ERR not a component name: 'not a component; rm -rf /'",
            odd.openApp(thorcuts, ScreenTarget.TOP),
        )
        assertTrue(g.calls.isEmpty())                  // nothing reached a shell
    }

    @Test
    fun `openApp asks the resolver on every call`() {
        val asked = mutableListOf<String>()
        val g = FakeGateway("", "")
        val a = actions(g) { pkg -> asked += pkg; "$pkg/.Main" }
        a.openApp("a.b", ScreenTarget.TOP)
        a.openApp("c.d", ScreenTarget.BOTTOM)
        assertEquals(listOf("a.b", "c.d"), asked)
        assertEquals(
            listOf(
                script("am start --display 0 -n 'a.b/.Main'"),
                script("am start --display 4 -n 'c.d/.Main'"),
            ),
            g.calls,
        )
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
        assertEquals(noReply, actions(FakeGateway(null)).openApp(thorcuts, ScreenTarget.TOP))
        // FOCUSED open: the tool answered, am did not.
        val toolThenNothing = FakeGateway("OK focused display=0 task=1 pkg=a.b", null)
        assertEquals(noReply, actions(toolThenNothing).openApp(thorcuts, ScreenTarget.FOCUSED))
    }

    @Test
    fun `an empty tool reply is an ERR line, never an empty outcome`() {
        // app_process died before printing (Appendix B's stale-CLASSPATH "Aborted" with exit
        // 0): the gateway's grep matched nothing, so the reply is "" rather than null.
        val noLine = "ERR the tool printed no result line"
        assertEquals(noLine, actions(FakeGateway("")).listTasks())
        assertEquals(noLine, actions(FakeGateway(" ")).closeScreen(ScreenTarget.TOP))
        val g = FakeGateway("")
        assertEquals(noLine, actions(g).openApp(thorcuts, ScreenTarget.FOCUSED))
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
        a.openApp(thorcuts, ScreenTarget.TOP)
        a.openApp(thorcuts, ScreenTarget.BOTTOM)
        val scripts = g.calls.filterIsInstance<Call.Script>().map { it.lines }
        assertEquals(4, scripts.size)
        for (lines in scripts) {
            assertEquals(1, lines.size)
            assertTrue(CommandSafety.isSingleLine(lines.single()), lines.single())
        }
    }

}
