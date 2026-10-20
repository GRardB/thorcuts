package com.grardb.thorcuts.platform.tasks

import com.grardb.thorcuts.core.model.BrightnessMode
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

    /** One launcher by default: with none resolved, close refuses before it reaches the tool.
     *  [lock] is Focus Mode as the app reads it; null is Auto-lock, so every test that does not
     *  pass one — the existing FOCUSED tests included — is the Auto-lock case. */
    private fun actions(
        g: FakeGateway,
        launchers: Set<String> = setOf("c.launcher"),
        resolve: (String) -> String? = mainOf,
        shots: FakeScreenshots = FakeScreenshots(),
        lock: () -> ScreenTarget? = { null },
    ) = TaskActions(g, { launchers }, resolve, shots, lock)

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
        val a = TaskActions(g, { asked++; setOf("c.launcher") }, mainOf, FakeScreenshots(), { null })
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
        val a = TaskActions(
            g, { setOf("rip.moth.cocoonshell", "not a package; rm x") }, mainOf, FakeScreenshots(),
            { null },
        )
        a.closeScreen(ScreenTarget.BOTTOM)
        assertEquals(listOf(entryPoint("close", "4", "rip.moth.cocoonshell")), g.calls)
    }

    @Test
    fun `closeScreen answers an ERR line when the launchers cannot be resolved`() {
        val g = FakeGateway()
        val a = TaskActions(g, { throw IllegalStateException("pm dead") }, mainOf, FakeScreenshots(), { null })
        val line = a.closeScreen(ScreenTarget.BOTTOM)
        assertTrue(line.startsWith("ERR launchers: "), line)
        assertTrue(g.calls.isEmpty())                  // nothing reached the tool
    }

    @Test
    fun `closeScreen refuses when no launcher resolved, and nothing reaches the tool`() {
        // Every device has a HOME handler, so an empty resolution means the query failed —
        // and a close with no names would remove a launcher that happened to be the app.
        val g = FakeGateway()
        val a = TaskActions(g, { emptySet() }, mainOf, FakeScreenshots(), { null })
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

    // ── take a screenshot: the system key for the top, a root screencap for the bottom ──

    private val frame = "/data/user/0/com.grardb.thorcuts/cache/screenshot.png"

    /** The bottom's script, as TaskActions builds it (M7 spec §5). */
    private fun bottomScript(): Call = Call.Script(
        listOf(
            "screencap -p -d 4630946482288158084 $frame 2>&1 | tr '\\n' ' ' > $frame.err",
            "chmod 644 $frame",
            "echo \"\$(wc -c < $frame) \$(cat $frame.err)\"",
        ),
    )

    @Test
    fun `takeScreenshot TOP sends the screenshot key and reads silence as success`() {
        val g = FakeGateway("")
        val shots = FakeScreenshots()
        assertEquals("OK screenshot key sent", actions(g, shots = shots).takeScreenshot(ScreenTarget.TOP))
        assertEquals(listOf(script("input keyevent KEYCODE_SYSRQ")), g.calls)
        assertEquals(0, shots.saves)
        assertTrue(shots.notified.isEmpty())   // the popup is the feedback
    }

    @Test
    fun `takeScreenshot TOP reports whatever input printed as an ERR`() {
        val usage = "Error: Unknown command: keyevnt Usage: input [<source>] ..."
        val g = FakeGateway("$usage ")
        assertEquals("ERR input: $usage", actions(g).takeScreenshot(ScreenTarget.TOP))
    }

    @Test
    fun `takeScreenshot BOTTOM runs the screencap script, saves, notifies and returns the save's line`() {
        val g = FakeGateway("1236356 ")
        val shots = FakeScreenshots()
        val line = actions(g, shots = shots).takeScreenshot(ScreenTarget.BOTTOM)
        assertEquals("OK saved Pictures/Screenshots/Screenshot_20261019-182426_bottom.png", line)
        assertEquals(listOf(bottomScript()), g.calls)
        assertEquals(1, shots.saves)
        assertEquals(listOf(line), shots.notified)
    }

    @Test
    fun `takeScreenshot BOTTOM with a save that fails notifies and returns that line`() {
        val g = FakeGateway("1236356 ")
        val shots = FakeScreenshots("ERR gallery: java.io.IOException: no output stream")
        val line = actions(g, shots = shots).takeScreenshot(ScreenTarget.BOTTOM)
        assertEquals("ERR gallery: java.io.IOException: no output stream", line)
        assertEquals(listOf(line), shots.notified)
    }

    @Test
    fun `takeScreenshot BOTTOM with nothing written saves nothing and reports screencap's text`() {
        val g = FakeGateway("0 Error opening display 4630946482288158084 ")
        val shots = FakeScreenshots()
        assertEquals(
            "ERR screencap: Error opening display 4630946482288158084",
            actions(g, shots = shots).takeScreenshot(ScreenTarget.BOTTOM),
        )
        assertEquals(0, shots.saves)
        assertEquals(listOf("ERR screencap: Error opening display 4630946482288158084"), shots.notified)

        val silent = FakeGateway("0 ")
        val shots2 = FakeScreenshots()
        assertEquals("ERR screencap: wrote nothing", actions(silent, shots = shots2).takeScreenshot(ScreenTarget.BOTTOM))
        assertEquals(0, shots2.saves)
    }

    @Test
    fun `takeScreenshot BOTTOM with an unparseable line or no reply is an ERR, notified`() {
        val odd = FakeGateway("sh: wc: not found")
        val shots = FakeScreenshots()
        assertEquals(
            "ERR unexpected screencap line: sh: wc: not found",
            actions(odd, shots = shots).takeScreenshot(ScreenTarget.BOTTOM),
        )
        assertEquals(0, shots.saves)
        assertEquals(1, shots.notified.size)

        val none = FakeGateway(null)
        val shots2 = FakeScreenshots()
        assertEquals("ERR no reply from pservice", actions(none, shots = shots2).takeScreenshot(ScreenTarget.BOTTOM))
        assertEquals(listOf("ERR no reply from pservice"), shots2.notified)
    }

    @Test
    fun `takeScreenshot FOCUSED asks the tool, then takes the top path on display 0`() {
        val g = FakeGateway("OK focused display=0 task=12 pkg=org.x", "")
        val shots = FakeScreenshots()
        assertEquals("OK screenshot key sent", actions(g, shots = shots).takeScreenshot(ScreenTarget.FOCUSED))
        assertEquals(listOf(entryPoint("focused"), script("input keyevent KEYCODE_SYSRQ")), g.calls)
        assertTrue(shots.notified.isEmpty())
    }

    @Test
    fun `takeScreenshot FOCUSED takes the bottom path on display 4`() {
        val g = FakeGateway("OK focused display=4 task=7 pkg=org.x", "1236356 ")
        val shots = FakeScreenshots()
        val line = actions(g, shots = shots).takeScreenshot(ScreenTarget.FOCUSED)
        assertEquals("OK saved Pictures/Screenshots/Screenshot_20261019-182426_bottom.png", line)
        assertEquals(listOf(entryPoint("focused"), bottomScript()), g.calls)
        assertEquals(listOf(line), shots.notified)
    }

    @Test
    fun `takeScreenshot FOCUSED returns the tool's ERR verbatim, and refuses another display`() {
        val err = FakeGateway("ERR no focused task")
        assertEquals("ERR no focused task", actions(err).takeScreenshot(ScreenTarget.FOCUSED))
        assertEquals(listOf(entryPoint("focused")), err.calls)

        val elsewhere = FakeGateway("OK focused display=2 task=7 pkg=org.x")
        assertEquals(
            "ERR unexpected focused display 2",
            actions(elsewhere).takeScreenshot(ScreenTarget.FOCUSED),
        )
        assertEquals(listOf(entryPoint("focused")), elsewhere.calls)
    }

    // ── brightness ────────────────────────────────────────────────────────────────────

    private fun brightness(display: String, mode: BrightnessMode, percent: Int): Call =
        entryPoint("brightness", display, mode.name, percent.toString())

    @Test
    fun `changeBrightness runs the tool once with the screen's display word, line verbatim`() {
        val cases = mapOf(
            ScreenTarget.TOP to ("0" to "OK display 0: 35% -> 45%"),
            ScreenTarget.BOTTOM to ("4" to "OK display 4: 60% -> 50%"),
            ScreenTarget.FOCUSED to ("focused" to "OK display 4: 20% -> 30%"),
            ScreenTarget.BOTH to ("both" to "OK display 0: 35% -> 45%, display 4: 20% -> 30%"),
        )
        for ((screen, case) in cases) {
            val (word, line) = case
            val g = FakeGateway(line)
            assertEquals(line, actions(g).changeBrightness(BrightnessMode.INCREASE, 10, screen))
            assertEquals(listOf(brightness(word, BrightnessMode.INCREASE, 10)), g.calls)
        }
    }

    @Test
    fun `changeBrightness passes the tool's ERR line through, and names a silent pservice`() {
        val err =
            FakeGateway("ERR display 0: 35% -> 45%, display 4: unknown to the display service")
        assertEquals(
            "ERR display 0: 35% -> 45%, display 4: unknown to the display service",
            actions(err).changeBrightness(BrightnessMode.SET, 50, ScreenTarget.BOTH),
        )
        val unfocused = FakeGateway("ERR no focused root task")
        assertEquals(
            "ERR no focused root task",
            actions(unfocused).changeBrightness(BrightnessMode.SET, 50, ScreenTarget.FOCUSED),
        )
        assertEquals(listOf(brightness("focused", BrightnessMode.SET, 50)), unfocused.calls)
        val none = FakeGateway(null)
        assertEquals(
            "ERR no reply from pservice",
            actions(none).changeBrightness(BrightnessMode.SET, 50, ScreenTarget.TOP),
        )
        val blank = FakeGateway("")
        assertEquals(
            "ERR the tool printed no result line",
            actions(blank).changeBrightness(BrightnessMode.SET, 50, ScreenTarget.BOTTOM),
        )
    }

    @Test
    fun `closeAllApps runs the tool's closeall once with the screen's word and the launchers, line verbatim`() {
        val cases = mapOf(
            ScreenTarget.TOP to ("0" to "OK display 0: closed 2 (com.a, com.b)"),
            ScreenTarget.BOTTOM to ("4" to "OK display 4: nothing to close"),
            ScreenTarget.FOCUSED to ("focused" to "OK display 4: closed 1 (com.a)"),
            ScreenTarget.BOTH to
                ("both" to "OK display 0: nothing to close, display 4: closed 1 (com.a)"),
        )
        for ((screen, case) in cases) {
            val (word, line) = case
            val g = FakeGateway(line)
            assertEquals(line, actions(g).closeAllApps(screen))
            assertEquals(listOf(entryPoint("closeall", word, "c.launcher")), g.calls)
        }
    }

    @Test
    fun `closeAllApps passes the tool's ERR line through, and names a silent pservice`() {
        val partial = "ERR display 0: closed 1 (com.a), failed com.b: removeTask(7) returned false"
        assertEquals(partial, actions(FakeGateway(partial)).closeAllApps(ScreenTarget.TOP))
        val unfocused = FakeGateway("ERR no focused root task")
        assertEquals("ERR no focused root task", actions(unfocused).closeAllApps(ScreenTarget.FOCUSED))
        assertEquals(listOf(entryPoint("closeall", "focused", "c.launcher")), unfocused.calls)
        assertEquals(
            "ERR no reply from pservice",
            actions(FakeGateway(null)).closeAllApps(ScreenTarget.BOTH),
        )
        assertEquals(
            "ERR the tool printed no result line",
            actions(FakeGateway(" ")).closeAllApps(ScreenTarget.BOTTOM),
        )
    }

    @Test
    fun `closeAllApps follows closeScreen's launcher rules - sorted packages, asked every call, refused when unresolved`() {
        // Sorted, and only names that are packages.
        val sorted = FakeGateway("OK display 4: nothing to close")
        val a = TaskActions(
            sorted,
            { setOf("rip.moth.cocoonshell", "com.android.launcher3", "not a package; rm x") },
            mainOf,
            FakeScreenshots(),
            { null },
        )
        a.closeAllApps(ScreenTarget.BOTTOM)
        assertEquals(
            listOf(entryPoint("closeall", "4", "com.android.launcher3", "rip.moth.cocoonshell")),
            sorted.calls,
        )
        // Asked on every call, so a launcher change is honoured.
        var asked = 0
        val twice = FakeGateway("OK display 0: nothing to close", "OK display 0: nothing to close")
        val b = TaskActions(twice, { asked++; setOf("c.launcher") }, mainOf, FakeScreenshots(), { null })
        b.closeAllApps(ScreenTarget.TOP)
        b.closeAllApps(ScreenTarget.TOP)
        assertEquals(2, asked)
        // A resolver that throws, or one that resolves nothing: a line, and nothing reaches
        // the tool — a closeall with no names would remove the launcher's relaunched task.
        val dead = FakeGateway()
        val c = TaskActions(dead, { throw IllegalStateException("pm dead") }, mainOf, FakeScreenshots(), { null })
        assertTrue(c.closeAllApps(ScreenTarget.TOP).startsWith("ERR launchers: "))
        val none = FakeGateway()
        val d = TaskActions(none, { emptySet() }, mainOf, FakeScreenshots(), { null })
        assertEquals("ERR launchers: none resolved; not closing", d.closeAllApps(ScreenTarget.BOTH))
        assertTrue(dead.calls.isEmpty() && none.calls.isEmpty())
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
        val a = actions(g, resolve = { pkg -> "$pkg/org.dolphinemu.dolphinemu.ui.MainActivity" })
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
        val a = actions(g, resolve = { null })
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
        val dead = actions(g, resolve = { throw IllegalStateException("pm dead") })
        assertEquals(
            "ERR launch activity: java.lang.IllegalStateException: pm dead",
            dead.openApp(thorcuts, ScreenTarget.TOP),
        )
        val odd = actions(g, resolve = { "not a component; rm -rf /" })
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
        val a = actions(g, resolve = { pkg -> asked += pkg; "$pkg/.Main" })
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
        assertEquals(noReply, actions(FakeGateway(null)).closeAllApps(ScreenTarget.BOTH))
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
        assertEquals(noLine, actions(FakeGateway("")).closeAllApps(ScreenTarget.FOCUSED))
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

    // ── Focus Mode: FOCUSED under a lock is the TOP or BOTTOM variant, one round trip ──

    private val lockedTop: () -> ScreenTarget? = { ScreenTarget.TOP }
    private val lockedBottom: () -> ScreenTarget? = { ScreenTarget.BOTTOM }

    @Test
    fun `closeScreen FOCUSED under a Top lock sends the top's display id, not the focused word`() {
        val g = FakeGateway("OK closed 12@0 com.example")
        assertEquals(
            "OK closed 12@0 com.example",
            actions(g, lock = lockedTop).closeScreen(ScreenTarget.FOCUSED),
        )
        assertEquals(listOf(entryPoint("close", "0", "c.launcher")), g.calls)
    }

    @Test
    fun `closeAllApps FOCUSED under a Top lock sends the top's display id`() {
        val g = FakeGateway("OK display 0: closed 1 (com.a)")
        assertEquals(
            "OK display 0: closed 1 (com.a)",
            actions(g, lock = lockedTop).closeAllApps(ScreenTarget.FOCUSED),
        )
        assertEquals(listOf(entryPoint("closeall", "0", "c.launcher")), g.calls)
    }

    @Test
    fun `moveFocusedToOtherScreen under a Top lock moves from the top`() {
        val g = FakeGateway("OK moved 12@0 -> 4; display 4 had no app")
        assertEquals(
            "OK moved 12@0 -> 4; display 4 had no app",
            actions(g, lock = lockedTop).moveFocusedToOtherScreen(),
        )
        assertEquals(listOf(entryPoint("move", "0", "0", "4")), g.calls)
    }

    @Test
    fun `changeBrightness FOCUSED under a Top lock sends the top's display id`() {
        val g = FakeGateway("OK display 0: 35% -> 45%")
        assertEquals(
            "OK display 0: 35% -> 45%",
            actions(g, lock = lockedTop)
                .changeBrightness(BrightnessMode.INCREASE, 10, ScreenTarget.FOCUSED),
        )
        assertEquals(listOf(brightness("0", BrightnessMode.INCREASE, 10)), g.calls)
    }

    @Test
    fun `goHome FOCUSED under a Top lock sends the key for display 0`() {
        val g = FakeGateway("")
        assertEquals(
            "OK home sent to display 0",
            actions(g, lock = lockedTop).goHome(ScreenTarget.FOCUSED),
        )
        assertEquals(listOf(script("input -d 0 keyevent KEYCODE_HOME")), g.calls)
    }

    @Test
    fun `openApp FOCUSED under a Top lock starts on display 0 in one call, never asking the tool`() {
        val g = FakeGateway("Starting: Intent { } ")
        assertEquals(
            "OK display 0: Starting: Intent { }",
            actions(g, lock = lockedTop).openApp(thorcuts, ScreenTarget.FOCUSED),
        )
        assertEquals(listOf(script("am start --display 0 -n $quotedMain")), g.calls)
    }

    @Test
    fun `takeScreenshot FOCUSED under a Top lock sends the key and nothing else`() {
        val g = FakeGateway("")
        val shots = FakeScreenshots()
        assertEquals(
            "OK screenshot key sent",
            actions(g, shots = shots, lock = lockedTop).takeScreenshot(ScreenTarget.FOCUSED),
        )
        assertEquals(listOf(script("input keyevent KEYCODE_SYSRQ")), g.calls)
        assertTrue(shots.notified.isEmpty())
    }

    @Test
    fun `takeScreenshot FOCUSED under a Bottom lock takes the bottom path, never asking the tool`() {
        val g = FakeGateway("1236356 ")
        val shots = FakeScreenshots()
        val line = actions(g, shots = shots, lock = lockedBottom)
            .takeScreenshot(ScreenTarget.FOCUSED)
        assertEquals("OK saved Pictures/Screenshots/Screenshot_20261019-182426_bottom.png", line)
        assertEquals(listOf(bottomScript()), g.calls)
        assertEquals(listOf(line), shots.notified)
    }

    @Test
    fun `a lock that throws is an ERR line, and nothing reaches the gateway`() {
        val g = FakeGateway()
        val a = actions(g, lock = { throw IllegalStateException("settings dead") })
        assertEquals(
            "ERR focus mode: java.lang.IllegalStateException: settings dead",
            a.closeScreen(ScreenTarget.FOCUSED),
        )
        assertTrue(g.calls.isEmpty())
    }

    @Test
    fun `the lock is asked on every FOCUSED call and never for a named screen`() {
        var asked = 0
        val g = FakeGateway(
            "OK closed 1@0 a",                                   // closeScreen(TOP)
            "",                                                  // goHome(BOTTOM)
            "OK display 0: 50% -> 50%, display 4: 50% -> 50%",   // changeBrightness(BOTH)
            "OK closed 2@4 b",                                   // closeScreen(FOCUSED)
            "OK closed 3@4 b",                                   // closeScreen(FOCUSED)
        )
        val a = actions(g, lock = { asked++; ScreenTarget.BOTTOM })
        a.closeScreen(ScreenTarget.TOP)
        a.goHome(ScreenTarget.BOTTOM)
        a.changeBrightness(BrightnessMode.SET, 50, ScreenTarget.BOTH)
        assertEquals(0, asked)
        a.closeScreen(ScreenTarget.FOCUSED)
        a.closeScreen(ScreenTarget.FOCUSED)
        assertEquals(2, asked)
        assertEquals(
            listOf(entryPoint("close", "4", "c.launcher"), entryPoint("close", "4", "c.launcher")),
            g.calls.takeLast(2),
        )
    }

}
