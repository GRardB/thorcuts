package com.grardb.thorcuts.platform.tasks

import android.app.ActivityManager
import android.os.IBinder

/**
 * Runs in a bare `app_process` VM as uid 0.
 *
 * A root shell can run `am`, but `am` exposes no command for
 * IActivityTaskManager.moveRootTaskToDisplay — the call that reparents a LIVE task
 * to another display without relaunching it, so the app keeps running and keeps its
 * state — nor for removeTask, nor for asking which display holds focus. This makes
 * those binder calls directly.
 *
 * Because this is not an app process, ART's hidden-API enforcement does not apply
 * and the reflected framework calls resolve freely, exactly as the `am` binary's do.
 *
 * Invoked as:
 *   CLASSPATH=<apk> app_process /system/bin \
 *       com.grardb.thorcuts.platform.tasks.TaskTool list
 *   … TaskTool swap <displayA> <displayB>
 *   … TaskTool focused
 *   … TaskTool close <display|focused> [launcherPkg...]
 *   … TaskTool move <display|focused> <displayA> <displayB>
 *
 * A <display> is an int; `focused` names the display of the window manager's focused
 * root task, resolved here and nowhere else (see [resolveDisplay]). The packages after
 * close's screen are the ones it refuses to close: the launchers, resolved by the app.
 *
 * Contract with the caller: the first line matching ^(OK|ERR) is the result.
 * Everything goes to stdout, because the pservice bridge returns stdout only.
 */
object TaskTool {

    private const val MAX_TASKS = 200

    /** The screen argument that means "wherever input focus is": see [resolveDisplay]. */
    private const val FOCUSED = "focused"

    private const val SWAP_ARGS = "swap <displayA> <displayB>"
    private const val CLOSE_ARGS = "close <display|focused> [launcherPkg...]"
    private const val MOVE_ARGS = "move <display|focused> <displayA> <displayB>"
    private const val SWAP_USAGE = "usage: $SWAP_ARGS"
    private const val CLOSE_USAGE = "usage: $CLOSE_ARGS"
    private const val MOVE_USAGE = "usage: $MOVE_ARGS"
    private const val USAGE = "usage: list | $SWAP_ARGS | focused | $CLOSE_ARGS | $MOVE_ARGS"

    /**
     * `WindowConfiguration.ACTIVITY_TYPE_HOME` — @hide, so written out.
     *
     * One of the ACTIVITY_TYPE_* enum. Compared ONLY against an activity type.
     */
    private const val ACTIVITY_TYPE_HOME = 2

    /**
     * `WindowConfiguration.WINDOWING_MODE_PINNED` — @hide, so written out.
     *
     * One of the WINDOWING_MODE_* enum. Compared ONLY against a windowing mode.
     *
     * It is numerically equal to [ACTIVITY_TYPE_HOME] by coincidence — the two
     * are unrelated enums in the same class (ACTIVITY_TYPE_HOME=2 at
     * WindowConfiguration.java:146, WINDOWING_MODE_PINNED=2 at :106). They are
     * kept as two constants on purpose: sharing one would make
     * `windowingMode != ACTIVITY_TYPE_HOME` compile and read as correct, and it
     * would stay correct only for as long as the coincidence holds.
     */
    private const val WINDOWING_MODE_PINNED = 2

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            val atm = activityTaskManager() ?: return fail(3, "activity_task service unavailable")
            when (args.firstOrNull()) {
                "list" -> list(atm)
                "swap" -> swap(atm, intArg(args, 1, SWAP_USAGE), intArg(args, 2, SWAP_USAGE))
                "focused" -> focused(atm)
                "close" -> close(atm, arg(args, 1, CLOSE_USAGE), args.drop(2))
                "move" -> move(
                    atm,
                    arg(args, 1, MOVE_USAGE),
                    intArg(args, 2, MOVE_USAGE),
                    intArg(args, 3, MOVE_USAGE),
                )
                else -> fail(2, USAGE)
            }
        } catch (t: Throwable) {
            // Throwable, not Exception: reflection raises linkage errors too.
            fail(1, chain(t))
        }
    }

    private fun list(atm: Any) {
        // Detail first, result line last: the caller greps for the FIRST ^(OK|ERR),
        // and pservice hands back only one line, so the summary must be on it.
        //
        // Both levels are printed because they differ and the difference is the
        // whole point of this tool: T lines are the LEAF tasks getTasks reports
        // (what the user sees), R lines are the ROOT tasks swap() actually moves.
        // For an ordinary app the two ids are the same; for the launcher's home
        // stack they are not (e.g. leaf 1273 inside root 1272).
        runningTasks(atm).forEach {
            println(
                "T ${it.taskId} display=${displayOf(it)} visible=${visibleOf(it)}" +
                    " type=${activityTypeOf(it)} pkg=${packageOf(it)}"
            )
        }
        // The focused root task, so a FOCUSED decision can be checked from one `list`:
        // close/move/open on FOCUSED act on THIS display (resolveDisplay). A ROM without
        // the focus API must not cost the R lines below — `list` is the diagnostic — so the
        // failure is printed as its own F line and the listing goes on; the actions that
        // need focus still fail loud through resolveDisplay.
        try {
            val focus = focusedRootTask(atm)
            println(
                if (focus == null) "F none"
                else "F ${focus.taskId} display=${displayOf(focus)} pkg=${packageOf(focus)}"
            )
        } catch (t: Throwable) {
            // Throwable, as main() does: reflection raises linkage errors too.
            println("F unavailable: ${chain(t)}")
        }
        val roots = rootTasks(atm)
        roots.forEach {
            // type= is the topmost LEAF's activity type, kept because it is useful
            // when reading this output by eye. rootType= and mode= are the ROOT
            // task's OWN values — the two isApp() actually decides on, and the
            // two the server tests. They are printed so a wrong decision can be
            // diagnosed from one `list` without a second tool.
            println(
                "R ${it.taskId} display=${displayOf(it)} visible=${visibleOf(it)}" +
                    " type=${activityTypeOf(it)} rootType=${ownActivityTypeOf(it)}" +
                    " mode=${ownWindowingModeOf(it)} pkg=${packageOf(it)}"
            )
        }
        // The summary reports ROOT ids and applies the same filter swap() does, so
        // it names exactly what a swap would move: home stacks and PiP windows are
        // left out. It therefore cannot be called "visible" — the launcher is
        // visible and so is a PiP window, and with both screens launcher-only this
        // printed `OK visible ` (trailing space, empty list) while two tasks were
        // plainly on screen. Say what it means instead, as fail(4) and the
        // one-sided ok() already do.
        val summary = roots.filter { isApp(it) }
            .joinToString(";") { "${it.taskId}@${displayOf(it)}=${packageOf(it)}" }
        ok(if (summary.isEmpty()) "swappable none" else "swappable $summary")
    }

    /**
     * Six distinct, honestly-reported outcomes. "A visible task" below means an
     * APP task — see [isApp]: the launcher and a picture-in-picture
     * window are both visible, and neither is something this tool may send
     * anywhere, so a screen showing only those counts as empty.
     *  - both displays have a visible task and both moves succeed -> OK "swapped …"
     *  - only one display has a visible task and its move succeeds -> a distinct
     *    OK line describing the one-sided move (never contains "swapped")
     *  - both present, the first move throws -> ERR "nothing moved …" (nothing
     *    has moved yet, so this is unambiguous)
     *  - both present, the first move succeeds and the second throws -> ERR
     *    describing the partial state precisely (both tasks now on displayB)
     *  - only one display has a visible task and its move throws -> ERR
     *    "nothing moved …" (a one-sided move either happens or it doesn't;
     *    there is no partial state to describe)
     *  - neither display has a visible task -> ERR, nothing attempted
     */
    private fun swap(atm: Any, displayA: Int, displayB: Int) {
        // ROOT tasks, not the leaf tasks getTasks reports. moveRootTaskToDisplay
        // accepts only a root task id and throws IllegalArgumentException
        // ("Unknown rootTaskId=N") for anything else. For an ordinary app the leaf
        // IS the root, which is why passing leaf ids worked at all; the launcher's
        // home stack nests a leaf task inside a root task, so a leaf id there was
        // always rejected — and because a display is never empty for long (two
        // packages on this device register SECONDARY_HOME), that was the default
        // case, not an edge case. Enumerating roots means every id below is already
        // a root id. See rootTasks() for why the leaf's root cannot simply be
        // looked up instead.
        val tasks = rootTasks(atm)

        // An app can own several root tasks, and the enumeration walks display by
        // display, so "the first task for this package" can be a hidden one on the
        // wrong screen. Take the app task on each display: the topmost one [isApp].
        val onA = appOn(tasks, displayA)
        val onB = appOn(tasks, displayB)
        if (onA == null && onB == null) {
            return fail(4, "no app to swap on display $displayA or $displayB")
        }

        val mover = moveRootTaskMethod(atm)

        // Task ids were captured before either move, so the second call is unaffected
        // by the first.
        if (onA != null && onB != null) {
            // Both screens have a visible task: this is a real swap. Move A to B
            // first. If THAT throws, nothing has moved yet, so a plain, unambiguous
            // ERR is correct. If it succeeds but moving B to A then throws, A is now
            // stacked on top of B on display B and display A shows nothing but the
            // launcher — that partial state must be reported precisely, not folded
            // into the generic top-level catch, and must NOT be reverted (a revert
            // could itself fail and make the state harder to reason about).
            try {
                mover.invoke(atm, onA.taskId, displayB)
            } catch (t: Throwable) {
                return fail(
                    7,
                    "nothing moved; task ${onA.taskId}@$displayA -> $displayB failed: ${chain(t)}",
                )
            }
            try {
                mover.invoke(atm, onB.taskId, displayA)
            } catch (t: Throwable) {
                // The "both tasks are now on display $displayB" clause sits AFTER
                // ${chain(t)}, and that is fine. The guarantee that matters is not
                // an ordering: it is that chain() collapses every '\n' and '\r' to
                // a space, so this whole message is one physical line and the
                // caller's `grep -m1 -E '^(OK|ERR)'` cannot truncate the clause
                // away wherever it sits. (An earlier brief asked for the clause to
                // come first as belt-and-braces against embedded remote stack
                // traces; with chain() doing the collapsing, the position is moot,
                // so the requirement is retired rather than the string reordered.)
                return fail(
                    8,
                    "partial move; task ${onA.taskId}@$displayA -> $displayB succeeded but " +
                        "task ${onB.taskId}@$displayB -> $displayA failed: ${chain(t)}; " +
                        "both tasks are now on display $displayB",
                )
            }
            return ok("swapped ${onA.taskId}@$displayA <-> ${onB.taskId}@$displayB")
        }

        // Only one display has a visible task. Moving it to the other, empty
        // screen is still a useful action ("send this to the other screen"), but
        // it is NOT a swap and must never be reported as one.
        val (task, from, to) = if (onA != null) Triple(onA, displayA, displayB) else Triple(onB!!, displayB, displayA)
        try {
            mover.invoke(atm, task.taskId, to)
        } catch (t: Throwable) {
            // Unlike the two-sided branch, there is no partial state to describe
            // here: this move either happens or it doesn't.
            return fail(9, "nothing moved; task ${task.taskId}@$from -> $to failed: ${chain(t)}")
        }
        ok("moved ${task.taskId}@$from -> $to; display $to had no app to send back")
    }

    /**
     * Which display holds input focus, and what is focused there. Unlike every other id
     * this tool prints, task= and pkg= name the focused ROOT task itself — which may be the
     * home stack or a picture-in-picture window, not an app — so the app side reads
     * display= only (TaskActions.openThorcuts) and chooses the app through [isApp] like
     * everything else.
     */
    private fun focused(atm: Any) {
        val f = focusedRootTask(atm) ?: fail(10, "no focused root task")
        ok("focused display=${displayOf(f)} task=${f.taskId} pkg=${packageOf(f)}")
    }

    /**
     * The display a `<display|focused>` argument names.
     *
     * `focused` is the display of the window manager's focused root task — the display
     * whose window the gamepad's events reach. Resolved HERE, in the tool, because the
     * app side has no path to the hidden API; and resolved to a DISPLAY, not to the
     * focused task itself: the app on that display is then chosen by [isApp] from the
     * full root list exactly as [swap] chooses, so a focused picture-in-picture window
     * or a focused launcher yields the display's app task or "no app", never the PiP
     * window or the home stack. A ROM reporting no focused root task is an ERR, not a
     * default display: there is no right screen to act on.
     */
    private fun resolveDisplay(atm: Any, screen: String): Int {
        if (screen == FOCUSED) {
            val f = focusedRootTask(atm) ?: fail(10, "no focused root task")
            return displayOf(f)
        }
        return screen.toIntOrNull()
            ?: fail(2, "screen must be a display id or '$FOCUSED', was '$screen'")
    }

    /** The app on [display]: the topmost root task there that [isApp], or null. */
    private fun appOn(roots: List<android.app.TaskInfo>, display: Int): android.app.TaskInfo? =
        roots.firstOrNull { displayOf(it) == display && isApp(it) }

    /** `moveRootTaskToDisplay(rootTaskId, displayId)` by name; absent -> ERR naming candidates. */
    private fun moveRootTaskMethod(atm: Any): java.lang.reflect.Method =
        atm.javaClass.methods.firstOrNull { it.name == "moveRootTaskToDisplay" }
            ?: fail(6, "moveRootTaskToDisplay not found; candidates=" + signatures(atm, "Display"))

    /**
     * Close the app on a screen: `removeTask` on its root task — unless that app is a
     * launcher. A launcher relaunched as an ordinary task passes [isApp] (Cocoon, measured
     * as type=standard on the bottom screen, 2026-10-13) and `removeTask` would remove it;
     * the app side resolves the HOME / SECONDARY_HOME packages with PackageManager (no
     * privilege) and passes them as [launchers], refused here by name. The predicate stays
     * the swap's.
     *
     * What becomes of the app's PROCESS depends on the state of its activities, and
     * both cases are MEASURED: a STOPPED app's task removed (M1, the recents swipe-away
     * path) kills the process when every activity it had lived in that task and it holds
     * no foreground service; the FOREGROUND app's task removed (the actions run,
     * 2026-10-13, on Thorcuts itself) finishes the activity and leaves the process alive
     * — AMS skips the kill while any activity in the process is not yet stopped, and
     * skips it outright while the process holds a foreground service. Thorcuts' OWN task
     * is that second case either way once shortcuts are on: the holder's service keeps
     * the process, the Activity goes, and the helper keeps working (the holder spec). The
     * vacated screen is refilled by its SECONDARY_HOME within seconds (M1), so the line
     * says `closed`, not "blank".
     */
    private fun close(atm: Any, screen: String, launchers: List<String>) {
        val display = resolveDisplay(atm, screen)
        val task = appOn(rootTasks(atm), display)
            ?: fail(11, "no app to close on display $display")
        val pkg = packageOf(task)
        // The guard reads both the top and the base activity: a launcher task with a foreign
        // activity on top is still the launcher's task (the inverse costs only a refusal the
        // user can read). A task that names no package at all is NOT closed: "?" is the one
        // default packageOf() allows, for printing, and it may not decide a destructive call.
        val names = listOfNotNull(task.topActivity?.packageName, task.baseActivity?.packageName)
        if (names.isEmpty()) fail(18, "cannot name the app on display $display; not closing")
        val launcher = names.firstOrNull { it in launchers }
        if (launcher != null) fail(17, "refusing to close launcher $launcher on display $display")
        // By the int parameter, not the count: a one-argument sibling would otherwise be
        // picked here and fail at invoke.
        val remove = atm.javaClass.methods
            .firstOrNull {
                it.name == "removeTask" &&
                    it.parameterTypes.singleOrNull() == Int::class.javaPrimitiveType
            }
            ?: fail(6, "removeTask not found; candidates=" + signatures(atm, "remove"))
        val removed = remove.invoke(atm, task.taskId) as? Boolean
            ?: fail(12, "removeTask(${task.taskId}) did not return a boolean")
        if (!removed) fail(13, "removeTask(${task.taskId}) returned false")
        ok("closed ${task.taskId}@$display $pkg")
    }

    /**
     * Send the app on a screen to the other screen — the swap's call, one-sided and
     * UNCONDITIONAL. Unlike [swap]'s one-sided branch this does not care whether the
     * destination already shows an app: the task lands ON TOP of it (the binder call
     * reparents on top), and the line names what it covered, read from the root list
     * captured before the move. Nothing is moved back; the swap exists for that.
     */
    private fun move(atm: Any, screen: String, displayA: Int, displayB: Int) {
        val from = resolveDisplay(atm, screen)
        val to = when (from) {
            displayA -> displayB
            displayB -> displayA
            else -> fail(14, "display $from is neither $displayA nor $displayB")
        }
        val roots = rootTasks(atm)
        val task = appOn(roots, from) ?: fail(15, "no app to move on display $from")
        val covered = appOn(roots, to)
        val mover = moveRootTaskMethod(atm)
        try {
            mover.invoke(atm, task.taskId, to)
        } catch (t: Throwable) {
            // Nothing has moved: a one-sided move either happens or it doesn't.
            fail(16, "nothing moved; task ${task.taskId}@$from -> $to failed: ${chain(t)}")
        }
        val onto =
            if (covered != null) " over ${covered.taskId}@$to" else "; display $to had no app"
        ok("moved ${task.taskId}@$from -> $to$onto")
    }

    private fun activityTaskManager(): Any? {
        val binder = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java)
            .invoke(null, "activity_task") as? IBinder ?: return null
        return Class.forName("android.app.IActivityTaskManager\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, binder)
    }

    /**
     * getTasks' parameter list drifts between ROMs and Android versions, so resolve
     * by NAME and build the arguments from the actual reflected parameter types:
     * the first int is maxNum, any other int is -1 (e.g. "all displays"), booleans
     * are false (no filtering, no intent extras).
     */
    private fun runningTasks(atm: Any): List<ActivityManager.RunningTaskInfo> {
        val m = atm.javaClass.methods.firstOrNull { it.name == "getTasks" }
            ?: throw NoSuchMethodException("getTasks not found; candidates=" + signatures(atm, "Task"))
        val args = m.parameterTypes.mapIndexed<Class<*>, Any?> { i, t ->
            when {
                t == Int::class.javaPrimitiveType && i == 0 -> MAX_TASKS
                t == Int::class.javaPrimitiveType -> -1
                t == Boolean::class.javaPrimitiveType -> false
                else -> null
            }
        }.toTypedArray()
        return usableList(
            m.invoke(atm, *args),
            "getTasks",
            ActivityManager.RunningTaskInfo::class.java,
        )
    }

    /**
     * The ROOT tasks — the only ids `moveRootTaskToDisplay` accepts.
     *
     * Resolved by NAME and with arguments built from the reflected parameter types,
     * for the same reason [runningTasks] is: these lists drift between ROMs. On this
     * device (AYN Thor, API 33) `getAllRootTaskInfos()` takes no arguments at all,
     * but an int is given -1 and a boolean false so a ROM that added a display or
     * user filter still gets "everything".
     *
     * Returned as [android.app.TaskInfo], the common supertype: the elements are
     * `ActivityTaskManager.RootTaskInfo`, which is @hide and so cannot be named from
     * code compiled against the public SDK, but it extends `TaskInfo` and the fields
     * this tool reads — `taskId`, `displayId`, `isVisible`, `topActivity` — are all
     * inherited and all populated (verified on the device: root 1272 reports
     * displayId=0, isVisible=true, topActivity=rip.moth.cocoonshell).
     *
     * Why not resolve each leaf's root instead: `TaskInfo` has no `rootTaskId` field
     * at API 33, and `parentTaskId`, which does exist, reads **-1** on this firmware
     * even for the launcher's leaf task, whose root is plainly 1272 in
     * `dumpsys activity activities`. It is documented as being set only for tasks
     * created by a task organizer, and the system-created home stack is not one. A
     * parent-walk therefore has nothing to walk, and reading the absent `rootTaskId`
     * reflectively would throw NoSuchFieldException. Measured, not assumed.
     *
     * An absent method is thrown, not defaulted: a swap that silently moved nothing
     * would look identical to one that worked.
     */
    private fun rootTasks(atm: Any): List<android.app.TaskInfo> {
        val m = atm.javaClass.methods.firstOrNull { it.name == "getAllRootTaskInfos" }
            ?: throw NoSuchMethodException(
                "getAllRootTaskInfos not found; candidates=" + signatures(atm, "RootTask")
            )
        return usableList(
            m.invoke(atm, *everythingArgs(m)),
            "getAllRootTaskInfos",
            android.app.TaskInfo::class.java,
        )
    }

    /**
     * Arguments meaning "everything" for a parameterless-on-this-ROM query that another
     * ROM may have given a display or user filter: an int is -1, a boolean false.
     */
    private fun everythingArgs(m: java.lang.reflect.Method): Array<Any?> =
        m.parameterTypes.map<Class<*>, Any?> { t ->
            when {
                t == Int::class.javaPrimitiveType -> -1
                t == Boolean::class.javaPrimitiveType -> false
                else -> null
            }
        }.toTypedArray()

    /**
     * The window manager's focused root task — `getFocusedRootTaskInfo()`, resolved by
     * NAME with [everythingArgs] like [rootTasks] (no arguments on this ROM, API 33).
     * Null when the ROM reports none. The element is an `ActivityTaskManager.RootTaskInfo`
     * — a `TaskInfo`, so [displayOf] and [packageOf] read it — and a return of any other
     * type throws and names it rather than being cast blind.
     *
     * UNVERIFIED on this ROM until the actions device run; `getAllRootTaskInfos` (the
     * same AIDL, the same ROM) resolved exactly this way.
     */
    private fun focusedRootTask(atm: Any): android.app.TaskInfo? {
        val m = atm.javaClass.methods.firstOrNull { it.name == "getFocusedRootTaskInfo" }
            ?: throw NoSuchMethodException(
                "getFocusedRootTaskInfo not found; candidates=" + signatures(atm, "Focused")
            )
        val raw = m.invoke(atm, *everythingArgs(m)) ?: return null
        return raw as? android.app.TaskInfo
            ?: throw IllegalStateException(
                "getFocusedRootTaskInfo returned ${raw.javaClass.name}, not a TaskInfo"
            )
    }

    /**
     * Turn whatever an enumeration call returned into a `List<T>`, or throw saying
     * precisely what came back instead.
     *
     * `(raw as? List<*>).orEmpty().filterIsInstance<T>()` was wrong in a way that
     * is invisible at the call site: a ROM returning a `ParceledListSlice`, an
     * array, or elements of some other type yielded an EMPTY list, and swap() then
     * reported `ERR no app to swap on display 0 or 4` — a true-sounding sentence
     * naming entirely the wrong cause, in the one file that otherwise works hard
     * to be diagnosable.
     *
     * An empty return is NOT an error: a display really can have nothing on it,
     * and "no app to swap" is then the honest answer. The three distinguishable
     * failures are: returned null, returned a non-list, and returned N entries of
     * which some are unusable.
     *
     * A partly-usable list throws rather than being filtered down, because the
     * elements this tool cannot see are exactly the ones that make a swap unsafe:
     * [swap] picks the FIRST app task per display out of a z-ordered list,
     * and [isApp] exists to exclude the home and pinned tasks whose reparent
     * is destructive. Silently dropping an element could hide one of those and
     * promote the wrong task to "topmost".
     *
     * A `ParceledListSlice` is unwrapped through its `getList()` rather than being
     * rejected, since that is a wrapper around the very list we want, and the
     * method is resolved by name for the same reason every other call here is.
     */
    private fun <T> usableList(raw: Any?, call: String, type: Class<T>): List<T> {
        val elements: List<*> = when {
            raw == null -> throw IllegalStateException(
                "$call returned null, which is not an empty task list"
            )
            raw is List<*> -> raw
            raw is Array<*> -> raw.toList()
            else -> {
                val getList = raw.javaClass.methods
                    .firstOrNull { it.name == "getList" && it.parameterCount == 0 }
                    ?: throw IllegalStateException(
                        "$call returned ${raw.javaClass.name}, which is neither a List, " +
                            "an array, nor something with getList(); it has " +
                            raw.javaClass.methods.filter { it.parameterCount == 0 }
                                .joinToString(",") { it.name }
                    )
                val unwrapped = getList.invoke(raw)
                unwrapped as? List<*>
                    ?: throw IllegalStateException(
                        "$call returned ${raw.javaClass.name} whose getList() gave " +
                            "${unwrapped?.javaClass?.name ?: "null"}, not a List"
                    )
            }
        }
        val usable = elements.count { type.isInstance(it) }
        if (usable != elements.size) {
            throw IllegalStateException(
                "$call returned ${elements.size} entries, $usable usable as " +
                    "${type.name}; element types=" +
                    elements.map { it?.javaClass?.name ?: "null" }.distinct()
                        .joinToString(",")
            )
        }
        @Suppress("UNCHECKED_CAST")
        return elements as List<T>
    }

    // TaskInfo.displayId and TaskInfo.isVisible are @hide FIELDS. No hidden-API
    // checks apply in app_process, so reflection reaches them. Declared on TaskInfo
    // itself, so one lookup serves both the leaf RunningTaskInfos and the
    // RootTaskInfos.
    //
    // Deliberately NOT wrapped in runCatching{}.getOrDefault(…): that idiom cannot
    // tell "this ROM has no such field" from "the field holds the default value",
    // and both of these are decisions, not labels. A visibleOf() that quietly
    // answered `true` for a missing field would call every task visible and the
    // tool would move an arbitrary pair of tasks while reporting success. Let the
    // NoSuchFieldException out: main()'s catch turns it into a one-line ERR that
    // names the field and lists what the ROM does have.
    private fun taskInfoField(name: String): java.lang.reflect.Field =
        try {
            android.app.TaskInfo::class.java.getDeclaredField(name).apply { isAccessible = true }
        } catch (e: NoSuchFieldException) {
            throw NoSuchFieldException(
                "TaskInfo.$name absent on this ROM; TaskInfo has " +
                    android.app.TaskInfo::class.java.declaredFields.joinToString(",") { it.name }
            ).apply { initCause(e) }
        }

    private fun displayOf(t: android.app.TaskInfo): Int = taskInfoField("displayId").getInt(t)

    private fun visibleOf(t: android.app.TaskInfo): Boolean = taskInfoField("isVisible").getBoolean(t)

    /**
     * The topmost LEAF activity's type (`Task.java:3427`:
     * `info.topActivityType = top.getActivityType()`).
     *
     * DIAGNOSTIC ONLY — printed by [list], never decided on. The server's
     * TaskDisplayArea checks tested by [isApp] look at the ROOT task's own
     * type and windowing mode, which are different values; see
     * [ownActivityTypeOf] / [ownWindowingModeOf].
     */
    private fun activityTypeOf(t: android.app.TaskInfo): Int = taskInfoField("topActivityType").getInt(t)

    /**
     * The task's OWN `WindowConfiguration` — the one the server's checks read.
     *
     * Two reflective hops, both forced on us by the public SDK:
     *  - `TaskInfo.configuration` is @hide (`TaskInfo.java:169`,
     *    `public final Configuration configuration`), so it is reached through
     *    [taskInfoField] like `displayId` and `isVisible` are.
     *  - `Configuration.windowConfiguration` is a PUBLIC field
     *    (`Configuration.java:361`), but its type `WindowConfiguration` is
     *    @hide @TestApi as a whole class (`WindowConfiguration.java:51-54`), so
     *    the type cannot be NAMED in code compiled against the public SDK. Hence
     *    the `Any` return and the reflective getters in [windowConfigInt].
     *
     * Like every other read in this file, an absent field throws and names what
     * was missing. A silent default here would be worse than elsewhere: see
     * [isApp].
     */
    private fun windowConfigurationOf(t: android.app.TaskInfo): Any {
        val configuration = taskInfoField("configuration").get(t)
            ?: throw NoSuchFieldException("TaskInfo.configuration is null on this ROM")
        val field = try {
            configuration.javaClass.getDeclaredField("windowConfiguration")
                .apply { isAccessible = true }
        } catch (e: NoSuchFieldException) {
            throw NoSuchFieldException(
                "Configuration.windowConfiguration absent on this ROM; " +
                    "${configuration.javaClass.name} has " +
                    configuration.javaClass.declaredFields.joinToString(",") { it.name }
            ).apply { initCause(e) }
        }
        return field.get(configuration)
            ?: throw NoSuchFieldException(
                "Configuration.windowConfiguration is null on this ROM"
            )
    }

    /**
     * Invoke a no-argument int getter on a [windowConfigurationOf] result.
     *
     * `getActivityType()` and `getWindowingMode()` are both public methods of
     * `WindowConfiguration` (`WindowConfiguration.java:443` and `:410`), but the
     * class is unnameable here, so they are called by name. An absent or
     * non-int-returning method throws and lists what the ROM's class does have.
     */
    private fun windowConfigInt(windowConfiguration: Any, getter: String): Int {
        val cls = windowConfiguration.javaClass
        val m = try {
            cls.getMethod(getter)
        } catch (e: NoSuchMethodException) {
            throw NoSuchMethodException(
                "${cls.name}.$getter() absent on this ROM; it has " +
                    cls.methods.filter { it.parameterCount == 0 }
                        .joinToString(",") { it.name }
            ).apply { initCause(e) }
        }
        return m.invoke(windowConfiguration) as? Int
            ?: throw NoSuchMethodException(
                "${cls.name}.$getter() did not return an int on this ROM; " +
                    "returnType=${m.returnType.name}"
            )
    }

    /**
     * The ROOT task's own activity type — `WINDOWING_MODE`'s sibling enum, and
     * the exact value `TaskDisplayArea.addRootTaskReferenceIfNeeded` tests via
     * `rootTask.isActivityTypeHome()`. Populated separately from
     * `topActivityType` at `Task.java:3419`.
     */
    private fun ownActivityTypeOf(t: android.app.TaskInfo): Int =
        windowConfigInt(windowConfigurationOf(t), "getActivityType")

    /**
     * The ROOT task's own windowing mode — the exact value
     * `TaskDisplayArea.addRootTaskReferenceIfNeeded` tests against
     * `WINDOWING_MODE_PINNED`. Populated at `Task.java:3420`.
     */
    private fun ownWindowingModeOf(t: android.app.TaskInfo): Int =
        windowConfigInt(windowConfigurationOf(t), "getWindowingMode")

    /**
     * Is this root task "the app on a screen"?
     *
     * The topmost visible root task that is neither a home task nor pinned — the one a
     * swap may move, a close may remove and a move may send across. For swap and move
     * the exclusions are forced: a rejected reparent is destructive (below). For close
     * they are a choice: `removeTask` would happily remove a launcher, and a screen with
     * no launcher is a screen with nothing on it. The predicate keys on the root task's
     * own activity TYPE, so a launcher relaunched as an ordinary task (parent spec §9's
     * known edge; Cocoon was measured in that state on the bottom screen, 2026-10-13)
     * passes it — the swap moves it, and [close] refuses it by name: the app side resolves
     * the HOME / SECONDARY_HOME packages with PackageManager and passes them in. Written
     * for the swap first (the paragraphs below are that record) and reused unchanged by
     * [close] and [move].
     *
     * Visible, not the launcher, and not a picture-in-picture window. Both
     * exclusions are not style choices — WindowManager refuses those moves, and
     * refuses them destructively. Measured on the device with root ids in hand,
     * i.e. after the "Unknown rootTaskId" rejection was already fixed:
     *
     *   W ActivityTaskManager: java.lang.IllegalArgumentException:
     *     addRootTaskReferenceIfNeeded: root home task=Task{#1272 type=home}
     *     already exist on display=DefaultTaskDisplayArea rootTask=Task{#1275 type=home}
     *       at com.android.server.wm.TaskDisplayArea.addRootTaskReferenceIfNeeded
     *       at com.android.server.wm.WindowContainer.reparent
     *       at com.android.server.wm.RootWindowContainer.moveRootTaskToDisplay
     *
     * A TaskDisplayArea holds at most ONE home root task, and both displays always
     * have one of their own, so a home root task can never be reparented onto the
     * other screen. Worse, the throw happens half way through the reparent: the
     * task had already been detached from its old display area, so it was left
     * orphaned and then destroyed, and display 4 sat with no home stack at all
     * until one was started by hand. Attempting it is not a harmless failure.
     *
     * Excluding it is also the right behaviour, not just the safe one. A screen
     * showing the launcher is a screen with nothing on it: there is no app to send
     * back the other way, and once the real app leaves, that screen's own home
     * stack surfaces by itself (observed: display 0's root 1272 became visible with
     * no action from us). So the honest outcome is the one-sided move the result
     * lines already distinguish — "moved X@a -> b; display b had no app to send
     * back" — and not a swap, and not a partial failure.
     *
     * PINNED (picture-in-picture) is the SECOND destructive rejection, and it was
     * missed the first time round. `addRootTaskReferenceIfNeeded` has two throw
     * sites, not one (`TaskDisplayArea.java:269-294`): the home one quoted above,
     * and
     *
     *   throw new IllegalArgumentException("addRootTaskReferenceIfNeeded: root
     *     pinned task=" + mRootPinnedTask + " already exist on display=" + …)
     *
     * when `rootTask.getWindowingMode() == WINDOWING_MODE_PINNED` and the target
     * display area already has a pinned root task. `WindowContainer.reparent()`
     * calls `oldParent.removeChild(this)` BEFORE `newParent.addChild(…)`, so this
     * throw orphans the task exactly as the home one does — the destruction
     * documented above applies identically. This device reports
     * `feature:android.software.picture_in_picture`, so the path is reachable
     * here, and a pinned root task is also z-ordered to the TOP of its
     * TaskDisplayArea — so without this exclusion `firstOrNull` would pick the
     * PiP window ahead of the fullscreen app and "swap" the wrong thing while
     * reporting `OK swapped …`.
     *
     * WHICH LEVEL: both tests read the ROOT task's OWN values, not the topmost
     * leaf's. `topActivityType` is the LEAF's type (`Task.java:3427`) and a PiP
     * window's leaf type is STANDARD, so a predicate written on it would have
     * caught neither case. `Task.java:3419-3420` populates the task's own values
     * separately, into `configuration.windowConfiguration`, and those are what
     * the server's `isActivityTypeHome()` / `getWindowingMode()` return. So the
     * predicate below is exactly the server's pair of checks, read at exactly the
     * server's level.
     *
     * Both constants are @hide, so both values are written out above — and they
     * are two constants, not one, despite both being 2: see [WINDOWING_MODE_PINNED].
     *
     * No `runCatching{}.getOrDefault(…)` anywhere in this chain, deliberately. A
     * [isApp] that answered `true` because a field was missing would hand an
     * arbitrary pair of tasks to a reparent that destroys what it rejects, and
     * report success.
     */
    private fun isApp(t: android.app.TaskInfo): Boolean =
        visibleOf(t) &&
            ownActivityTypeOf(t) != ACTIVITY_TYPE_HOME &&
            ownWindowingModeOf(t) != WINDOWING_MODE_PINNED

    private fun packageOf(t: android.app.TaskInfo): String =
        t.topActivity?.packageName ?: t.baseActivity?.packageName ?: "?"

    private fun signatures(obj: Any, needle: String): String =
        obj.javaClass.methods.filter { it.name.contains(needle) }
            .joinToString("; ") { m ->
                "${m.name}(${m.parameterTypes.joinToString(",") { it.simpleName }})"
            }

    // Android RemoteExceptions routinely embed the remote stack trace as literal
    // newlines inside .message ("Remote stack trace:\n\tat …"). The caller reads
    // our result through `grep -m1 -E '^(OK|ERR)'`, which takes only the FIRST
    // physical line, so any newline here silently truncates the result — and
    // whatever follows it (e.g. which display the tasks ended up stranded on)
    // is lost without any visible sign that it happened. Collapse all line
    // breaks to spaces so the result is always exactly one physical line.
    private fun chain(t: Throwable): String {
        val sb = StringBuilder()
        var c: Throwable? = t
        while (c != null) {
            sb.append(c.javaClass.name).append(": ").append(c.message).append(" | ")
            c = c.cause
        }
        return sb.toString().replace('\n', ' ').replace('\r', ' ')
    }

    /** Positional argument [i], or [usage] as the ERR line. */
    private fun arg(args: Array<String>, i: Int, usage: String): String =
        args.getOrNull(i) ?: fail(2, usage)

    /** Positional argument [i] as an int, or [usage] as the ERR line. */
    private fun intArg(args: Array<String>, i: Int, usage: String): Int =
        arg(args, i, usage).toIntOrNull() ?: fail(2, usage)

    // ok() and fail() return Nothing: the process ends inside them, so a caller may
    // write `x ?: fail(…)` and the compiler knows no value is needed after it.
    private fun ok(message: String): Nothing {
        println("OK $message")
        halt(0)
    }

    private fun fail(code: Int, reason: String): Nothing {
        // stdout, not stderr: the pservice bridge returns stdout only.
        println("ERR $reason")
        halt(code)
    }

    /**
     * halt(), not System.exit(): a bare app_process VM has live binder threads and
     * shutdown hooks can hang the process instead of ending it.
     */
    private fun halt(code: Int): Nothing {
        System.out.flush()
        System.err.flush()
        Runtime.getRuntime().halt(code)
        throw AssertionError("Runtime.halt($code) returned")
    }
}
