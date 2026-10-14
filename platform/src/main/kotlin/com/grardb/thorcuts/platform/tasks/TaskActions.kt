package com.grardb.thorcuts.platform.tasks

import com.grardb.thorcuts.core.model.ScreenTarget
import com.grardb.thorcuts.core.safety.CommandSafety
import com.grardb.thorcuts.platform.privilege.PrivilegeGateway

/**
 * The app-side face of [TaskTool], and of the two actions a shell line performs.
 * Blocking — call on [com.grardb.thorcuts.platform.privilege.Privilege.dispatcher].
 *
 * Every method returns ONE line, `OK …` or `ERR …`, verbatim from whatever did the work
 * — the tool's own result line, or `input`'s / `am`'s output folded to one line — never
 * a boolean (parent spec, Appendix C): a one-sided move, a partial failure and "no app
 * to close" all read differently. A null from the gateway (pservice did not answer) is
 * the same "ERR no reply from pservice" line from every method, so callers handle one
 * shape of result.
 *
 * [thorcutsMain] is Thorcuts' own Activity as `am start -n` takes it
 * (`com.grardb.thorcuts/.MainActivity`); the app passes it so this module need not know
 * the app's package. Validated at construction, then quoted like any component that
 * reaches a root shell — a value the app computes is not a silent exception to the rule.
 *
 * [homePackages] answers "which packages are a launcher" — HOME and SECONDARY_HOME, as
 * `HomePackages.resolve` reads them from PackageManager — and is asked on every close, so
 * a launcher change is honoured; the tool refuses to close any of them by name.
 */
class TaskActions(
    private val gateway: PrivilegeGateway,
    thorcutsMain: String,
    private val homePackages: () -> Set<String>,
) {

    private val thorcutsMainArg: String = CommandSafety.componentArg(thorcutsMain)
        ?: throw IllegalArgumentException("not a component name: '$thorcutsMain'")

    /** Every task with its display and visibility, plus the focused one, for diagnostics. */
    fun listTasks(): String = tool("list")

    /**
     * Swap whatever is visible on the two screens: the tool's `swap`. Its line tells a
     * real swap from a one-sided move from a partial failure (parent spec §9).
     */
    fun swapScreens(): String = tool("swap", TOP, BOTTOM)

    /**
     * Close the app on [screen]: the tool's `close`, with the launcher packages after the
     * screen so the tool refuses them by name (a launcher relaunched as an ordinary task
     * passes its app predicate; measured). Resolved on every call; filtered by the package
     * rule here as well as in `HomePackages`, because what reaches a root shell is validated
     * at the boundary that reaches it, unconditionally; sorted, so the command is
     * deterministic; the gateway quotes them like every argument. A resolver that throws —
     * it is a binder call — or a set that is EMPTY once filtered (every device has a HOME
     * handler, so empty means the query failed, or the names were not packages) is answered
     * as a line, and nothing is closed: passing no names would be the silent default this
     * class forbids.
     */
    fun closeScreen(screen: ScreenTarget): String {
        val launchers = try {
            homePackages()
        } catch (e: RuntimeException) {
            return "ERR launchers: $e"
        }
        val args = launchers.filter { CommandSafety.isPackage(it) }.sorted()
        if (args.isEmpty()) return "ERR launchers: none resolved; not closing"
        return tool("close", screenArg(screen), *args.toTypedArray())
    }

    /** Send the focused screen's app to the other screen: the tool's `move`. */
    fun moveFocusedToOtherScreen(): String = tool("move", FOCUSED, TOP, BOTTOM)

    /**
     * Go home on [screen] by sending a Home key for that display.
     *
     * The key runs the system's own path (`PhoneWindowManager.handleShortPressOnHome`
     * → `startHomeOnDisplay`), which picks HOME for the default display and
     * SECONDARY_HOME for the other — two packages register SECONDARY_HOME on this device
     * (M1), so an implicit `am start -c HOME` would hit a chooser or start the wrong
     * launcher. For FOCUSED the key carries no display: the start-home path resolves
     * that to the top focused display itself, so this is exactly the physical Home
     * button and needs no round trip to the tool. `input` prints nothing on success and
     * its usage text on a bad argument, which is how the two lines below are told apart.
     */
    fun goHome(screen: ScreenTarget): String {
        val focused = screen == ScreenTarget.FOCUSED
        val option = if (focused) "" else "-d ${screenArg(screen)} "
        val where = if (focused) "the focused display" else "display ${screenArg(screen)}"
        val out = shell("input ${option}keyevent KEYCODE_HOME") ?: return NO_REPLY
        return if (out.isEmpty()) "OK home sent to $where" else "ERR input: $out"
    }

    /**
     * Open Thorcuts on [screen]: `am start --display <id>`, run as uid 0 so Android's
     * background-launch restriction (which blocks a stopped app starting its own
     * Activity — exactly when a shortcut needs it) does not apply. `am` prints
     * `Starting: Intent {…}` first and an `Error: …` or a "brought to the front" warning
     * on later lines; pservice returns one line, so the script folds them and this
     * classifies: `Error` or `Exception` anywhere in the output is a failure (a Java trace
     * from `am` has no `Error:` line). A positive `Starting:` test would not do — `am`
     * prints it BEFORE it tries, so a failed start begins with it too. For FOCUSED the tool
     * is asked which display first: two round trips, serialised on the one thread.
     */
    fun openThorcuts(screen: ScreenTarget): String {
        val display = if (screen == ScreenTarget.FOCUSED) {
            val line = tool("focused")
            if (!line.startsWith("OK ")) return line
            FOCUSED_DISPLAY.find(line)?.groupValues?.get(1)
                ?: return "ERR unexpected focused line: $line"
        } else {
            screenArg(screen)
        }
        val out = shell("am start --display $display -n $thorcutsMainArg") ?: return NO_REPLY
        return if ("Error" in out || "Exception" in out) "ERR am: $out"
        else "OK display $display: $out"
    }

    private fun tool(vararg args: String): String {
        val line = gateway.runEntryPoint(ENTRY, *args) ?: return NO_REPLY
        // app_process can die before it prints — a stale CLASSPATH ends in a bare "Aborted"
        // with exit 0 (parent spec, Appendix B) — and the gateway's grep then matches
        // nothing, so the reply is "" rather than null. That is not a result line and must
        // never become an empty outcome.
        return line.ifBlank { NO_LINE }
    }

    /** One shell line through the script path: stderr folded in, line breaks to spaces. */
    private fun shell(command: String): String? =
        gateway.execScript(listOf("$command 2>&1 | tr '\\n' ' '"))?.trim()

    private fun screenArg(screen: ScreenTarget): String = when (screen) {
        ScreenTarget.TOP -> TOP
        ScreenTarget.BOTTOM -> BOTTOM
        ScreenTarget.FOCUSED -> FOCUSED
    }

    private companion object {
        const val ENTRY = "com.grardb.thorcuts.platform.tasks.TaskTool"
        const val NO_REPLY = "ERR no reply from pservice"
        const val NO_LINE = "ERR the tool printed no result line"

        /** The tool's word for "the display that holds input focus". */
        const val FOCUSED = "focused"
        val TOP = ThorDisplays.TOP.toString()
        val BOTTOM = ThorDisplays.BOTTOM.toString()

        /** What the tool's `OK focused display=N …` line is parsed for. */
        val FOCUSED_DISPLAY = Regex("display=(\\d+)")
    }
}
