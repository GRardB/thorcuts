package com.grardb.thorcuts.platform.tasks

import android.app.ActivityManager
import android.os.IBinder

/**
 * Runs in a bare `app_process` VM as uid 0.
 *
 * A root shell can run `am`, but `am` exposes no command for
 * IActivityTaskManager.moveRootTaskToDisplay — the call that reparents a LIVE task
 * to another display without relaunching it, so the app keeps running and keeps its
 * state. This makes that binder call directly.
 *
 * Because this is not an app process, ART's hidden-API enforcement does not apply
 * and the reflected framework calls resolve freely, exactly as the `am` binary's do.
 *
 * Invoked as:
 *   CLASSPATH=<apk> app_process /system/bin \
 *       com.grardb.thorcuts.platform.tasks.SwapTool list
 *   CLASSPATH=<apk> app_process /system/bin \
 *       com.grardb.thorcuts.platform.tasks.SwapTool swap <displayA> <displayB>
 *
 * Contract with the caller: the first line matching ^(OK|ERR) is the result.
 * Everything goes to stdout, because the pservice bridge returns stdout only.
 */
object SwapTool {

    private const val MAX_TASKS = 200

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
                "swap" -> {
                    val a = args.getOrNull(1)?.toIntOrNull()
                    val b = args.getOrNull(2)?.toIntOrNull()
                    if (a == null || b == null) fail(2, "usage: swap <displayA> <displayB>")
                    else swap(atm, a, b)
                }
                else -> fail(2, "usage: list | swap <displayA> <displayB>")
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
        val roots = rootTasks(atm)
        roots.forEach {
            // type= is the topmost LEAF's activity type, kept because it is useful
            // when reading this output by eye. rootType= and mode= are the ROOT
            // task's OWN values — the two swappable() actually decides on, and the
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
        val summary = roots.filter { swappable(it) }
            .joinToString(";") { "${it.taskId}@${displayOf(it)}=${packageOf(it)}" }
        ok(if (summary.isEmpty()) "swappable none" else "swappable $summary")
    }

    /**
     * Six distinct, honestly-reported outcomes. "A visible task" below means a
     * SWAPPABLE one — see [swappable]: the launcher and a picture-in-picture
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
        // wrong screen. Take the topmost SWAPPABLE root task on each display.
        val onA = tasks.firstOrNull { displayOf(it) == displayA && swappable(it) }
        val onB = tasks.firstOrNull { displayOf(it) == displayB && swappable(it) }
        if (onA == null && onB == null) {
            return fail(4, "no app to swap on display $displayA or $displayB")
        }

        val move = atm.javaClass.methods.firstOrNull { it.name == "moveRootTaskToDisplay" }
            ?: return fail(6, "moveRootTaskToDisplay not found; candidates=" + signatures(atm, "Display"))

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
                move.invoke(atm, onA.taskId, displayB)
            } catch (t: Throwable) {
                return fail(
                    7,
                    "nothing moved; task ${onA.taskId}@$displayA -> $displayB failed: ${chain(t)}",
                )
            }
            try {
                move.invoke(atm, onB.taskId, displayA)
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
            move.invoke(atm, task.taskId, to)
        } catch (t: Throwable) {
            // Unlike the two-sided branch, there is no partial state to describe
            // here: this move either happens or it doesn't.
            return fail(9, "nothing moved; task ${task.taskId}@$from -> $to failed: ${chain(t)}")
        }
        ok("moved ${task.taskId}@$from -> $to; display $to had no app to send back")
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
        val args = m.parameterTypes.map<Class<*>, Any?> { t ->
            when {
                t == Int::class.javaPrimitiveType -> -1
                t == Boolean::class.javaPrimitiveType -> false
                else -> null
            }
        }.toTypedArray()
        return usableList(
            m.invoke(atm, *args),
            "getAllRootTaskInfos",
            android.app.TaskInfo::class.java,
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
     * [swap] picks the FIRST swappable task per display out of a z-ordered list,
     * and [swappable] exists to exclude the home and pinned tasks whose reparent
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
     * TaskDisplayArea checks tested by [swappable] look at the ROOT task's own
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
     * [swappable].
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
     * Is this root task one a swap may move at all?
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
     * [swappable] that answered `true` because a field was missing would hand an
     * arbitrary pair of tasks to a reparent that destroys what it rejects, and
     * report success.
     */
    private fun swappable(t: android.app.TaskInfo): Boolean =
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

    private fun ok(message: String) {
        println("OK $message")
        halt(0)
    }

    private fun fail(code: Int, reason: String) {
        // stdout, not stderr: the pservice bridge returns stdout only.
        println("ERR $reason")
        halt(code)
    }

    /**
     * halt(), not System.exit(): a bare app_process VM has live binder threads and
     * shutdown hooks can hang the process instead of ending it.
     */
    private fun halt(code: Int) {
        System.out.flush()
        System.err.flush()
        Runtime.getRuntime().halt(code)
    }
}
