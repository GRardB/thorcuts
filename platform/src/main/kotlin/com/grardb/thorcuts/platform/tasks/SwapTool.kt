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
        val tasks = runningTasks(atm)
        // Detail first, result line last: the caller greps for the FIRST ^(OK|ERR),
        // and pservice hands back only one line, so the summary must be on it.
        tasks.forEach {
            println("T ${it.taskId} display=${displayOf(it)} visible=${visibleOf(it)} pkg=${packageOf(it)}")
        }
        val summary = tasks.filter { visibleOf(it) }
            .joinToString(";") { "${it.taskId}@${displayOf(it)}=${packageOf(it)}" }
        ok("visible $summary")
    }

    /**
     * Six distinct, honestly-reported outcomes:
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
        val tasks = runningTasks(atm)

        // An app can own several tasks, and getTasks walks display by display, so
        // "the first task for this package" can be a hidden one on the wrong screen.
        // Take the VISIBLE task on each display.
        val onA = tasks.firstOrNull { displayOf(it) == displayA && visibleOf(it) }
        val onB = tasks.firstOrNull { displayOf(it) == displayB && visibleOf(it) }
        if (onA == null && onB == null) {
            return fail(4, "no visible task on display $displayA or $displayB")
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
        ok("moved ${task.taskId}@$from -> $to; no visible task was on display $to")
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
        return (m.invoke(atm, *args) as? List<*>).orEmpty()
            .filterIsInstance<ActivityManager.RunningTaskInfo>()
    }

    // TaskInfo.displayId and TaskInfo.isVisible are @hide FIELDS. No hidden-API
    // checks apply in app_process, so reflection reaches them.
    private fun displayOf(t: ActivityManager.RunningTaskInfo): Int =
        runCatching {
            android.app.TaskInfo::class.java.getDeclaredField("displayId")
                .apply { isAccessible = true }.getInt(t)
        }.getOrDefault(-1)

    private fun visibleOf(t: ActivityManager.RunningTaskInfo): Boolean =
        runCatching {
            android.app.TaskInfo::class.java.getDeclaredField("isVisible")
                .apply { isAccessible = true }.getBoolean(t)
        }.getOrDefault(true)

    private fun packageOf(t: ActivityManager.RunningTaskInfo): String =
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
