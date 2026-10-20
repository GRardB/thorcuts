package com.grardb.thorcuts.platform.tasks

import com.grardb.thorcuts.core.model.BrightnessMode
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
 * [homePackages] answers "which packages are a launcher" — HOME and SECONDARY_HOME, as
 * `HomePackages.resolve` reads them from PackageManager — and is asked on every close, so
 * a launcher change is honoured; the tool refuses to close any of them by name.
 *
 * [launchComponent] answers "which activity opens this package" as `am start -n` takes it
 * (`org.x/.Main`, or null for a package with no launch activity, or not installed) — the
 * app's `getLaunchIntentForPackage`, asked on every open so an install or an update is
 * honoured. Resolved on the app side rather than by an implicit MAIN/LAUNCHER intent in
 * `am`, which can hit a chooser when a package has two launcher activities. What comes
 * back is validated and quoted like any component that reaches a root shell — a value
 * PackageManager computed is not a silent exception to the rule.
 *
 * [screenshots] is the bottom screenshot's Android boundary (M7 spec §6): where the root
 * script leaves the PNG, the save into the gallery, the toast. Asked only on the bottom
 * path; the top screen's screenshot is Android's own and needs no help from the app.
 */
class TaskActions(
    private val gateway: PrivilegeGateway,
    private val homePackages: () -> Set<String>,
    private val launchComponent: (String) -> String?,
    private val screenshots: Screenshots,
) {

    /** Every task with its display and visibility, plus the focused one, for diagnostics;
     *  since M3 nothing in the app calls it (the device runs use the tool's `list`). */
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
     * Open [packageName]'s launch activity on [screen]: `am start --display <id> -n`, run as
     * uid 0 so Android's background-launch restriction (which blocks a stopped app starting
     * an Activity — exactly when a shortcut needs it) does not apply. The component is
     * resolved first ([launchComponent]: a package with none, a resolver that throws — it is
     * a binder call — or a name that is not a component is answered as a line, and nothing
     * runs), then for FOCUSED the tool is asked which display: two round trips, serialised
     * on the one thread. `am` prints `Starting: Intent {…}` first and an `Error: …` or a
     * "brought to the front" warning on later lines; pservice returns one line, so the
     * script folds them and this classifies: `Error` or `Exception` anywhere in the output
     * is a failure (a Java trace from `am` has no `Error:` line). A positive `Starting:`
     * test would not do — `am` prints it BEFORE it tries, so a failed start begins with it
     * too.
     */
    fun openApp(packageName: String, screen: ScreenTarget): String {
        val component = try {
            launchComponent(packageName)
        } catch (e: RuntimeException) {
            return "ERR launch activity: $e"
        } ?: return "ERR no launch activity for $packageName"
        val componentArg = CommandSafety.componentArg(component)
            ?: return "ERR not a component name: '$component'"
        val display = if (screen == ScreenTarget.FOCUSED) {
            val d = focusedDisplay()
            if (d.startsWith("ERR ")) return d
            d
        } else {
            screenArg(screen)
        }
        val out = shell("am start --display $display -n $componentArg") ?: return NO_REPLY
        return if ("Error" in out || "Exception" in out) "ERR am: $out"
        else "OK display $display: $out"
    }

    /**
     * Take a screenshot of [screen] (M7 spec §4-6). TOP: the system's own screenshot — the
     * screenshot key through the root shell, exactly as [goHome] sends Home; the shutter, the
     * popup and the file are Android's, so no notifier runs. BOTTOM: the system cannot reach
     * that screen (its screenshot is hard-wired to the default display), so a root `screencap`
     * on the bottom's physical id writes a PNG into the app's cache, [screenshots] files it
     * into the gallery, and EVERY outcome of that path — the save's line or any failure before
     * it — goes to [Screenshots.notify], the toast. FOCUSED asks the tool which display holds
     * focus, as [openApp] does, and takes that screen's path.
     */
    fun takeScreenshot(screen: ScreenTarget): String = when (screen) {
        ScreenTarget.TOP -> screenshotKey()
        ScreenTarget.BOTTOM -> bottomScreenshot()
        ScreenTarget.FOCUSED -> {
            val d = focusedDisplay()
            when {
                d.startsWith("ERR ") -> d
                d == TOP -> screenshotKey()
                d == BOTTOM -> bottomScreenshot()
                else -> "ERR unexpected focused display $d"
            }
        }
        // The model refuses BOTH on this action (brightness spec §2); the branch is the
        // compiler's, not a path.
        ScreenTarget.BOTH -> error("TakeScreenshot cannot aim at both screens")
    }

    /**
     * Step or set [screen]'s brightness (brightness spec §4-5): the tool's `brightness <id>
     * <mode> <percent>` once per display, which reads that display's level from the display
     * service, applies the step or the level, clamps, and writes it back — the path both
     * sliders use. FOCUSED asks the tool which display holds focus first, as [openApp] does.
     * BOTH runs display 0 then 4 and folds the two parts into one line, trying the second
     * even when the first failed: a one-sided result and a failure before anything read
     * differently (the swap's rule). Each part is the tool's line after its `OK ` — or
     * `display <id>: <its reason>` after an `ERR `, so a no-reply or a blank reply names
     * its display too.
     */
    fun changeBrightness(mode: BrightnessMode, percent: Int, screen: ScreenTarget): String {
        val displays = when (screen) {
            ScreenTarget.TOP -> listOf(TOP)
            ScreenTarget.BOTTOM -> listOf(BOTTOM)
            ScreenTarget.BOTH -> listOf(TOP, BOTTOM)
            ScreenTarget.FOCUSED -> {
                val d = focusedDisplay()
                if (d.startsWith("ERR ")) return d
                if (d != TOP && d != BOTTOM) return "ERR unexpected focused display $d"
                listOf(d)
            }
        }
        val results = displays.map { id ->
            id to tool("brightness", id, mode.name, percent.toString())
        }
        val failed = results.any { (_, line) -> !line.startsWith("OK ") }
        val parts = results.map { (id, line) ->
            if (line.startsWith("OK ")) line.removePrefix("OK ")
            else "display $id: ${line.removePrefix("ERR ")}"
        }
        return (if (failed) "ERR " else "OK ") + parts.joinToString(", ")
    }

    /** The system's screenshot: `input` prints nothing on success, its usage on a bad key. */
    private fun screenshotKey(): String {
        val out = shell("input keyevent KEYCODE_SYSRQ") ?: return NO_REPLY
        return if (out.isEmpty()) "OK screenshot key sent" else "ERR input: $out"
    }

    /** The bottom path, with its one rule: whatever it returns, the user is told. */
    private fun bottomScreenshot(): String = bottomCapture().also(screenshots::notify)

    /**
     * `screencap -p -d <physical id>` into [Screenshots.frame], readable by the app once it is
     * 644 (the app owns the directory, so it can delete the file). The script's one line is
     * `<bytes written> <screencap's output, folded>`: a count above zero means a PNG to file;
     * zero means screencap refused, and its text (or "wrote nothing") is the reason.
     */
    private fun bottomCapture(): String {
        val frame = screenshots.frame.absolutePath
        val line = gateway.execScript(
            listOf(
                "screencap -p -d ${ThorDisplays.BOTTOM_PHYSICAL_ID} $frame 2>&1 | tr '\\n' ' ' > $frame.err",
                "chmod 644 $frame",
                "echo \"\$(wc -c < $frame) \$(cat $frame.err)\"",
            ),
        )?.trim() ?: return NO_REPLY
        val count = line.substringBefore(' ')
        val text = line.substringAfter(' ', missingDelimiterValue = "").trim()
        val bytes = count.toLongOrNull() ?: return "ERR unexpected screencap line: $line"
        if (bytes == 0L) return "ERR screencap: ${text.ifEmpty { "wrote nothing" }}"
        return screenshots.save()
    }

    /**
     * The display that holds input focus, as the tool's `focused` line reports it: its id
     * (`"0"`, `"4"`), or an `ERR …` line to return as is — the tool's own, or one naming a
     * `focused` line this cannot read. Shared by [openApp] and [takeScreenshot].
     */
    private fun focusedDisplay(): String {
        val line = tool("focused")
        if (!line.startsWith("OK ")) return line
        return FOCUSED_DISPLAY.find(line)?.groupValues?.get(1)
            ?: "ERR unexpected focused line: $line"
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
        // Every action that reaches here refuses BOTH in the model (brightness spec §2).
        ScreenTarget.BOTH -> error("no single display for both screens")
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
