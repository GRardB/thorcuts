package com.grardb.thorcuts.platform.privilege

import android.content.Context
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import com.grardb.thorcuts.core.safety.CommandSafety
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * Client for AYN's own root daemon, which registers the binder service
 * "PServerBinder" and runs shell commands as root via popen. It is the backend of
 * the firmware's Settings -> Thor -> "Run Script as Root" feature.
 *
 * An ordinary app can reach it because the Thor ships with SELinux permissive
 * ("Force SELinux" off, the default). If SELinux is switched to enforcing the
 * service becomes unreachable and Thorcuts cannot function.
 *
 * Transaction contract: service "PServerBinder", code 0,
 * data = writeStringArray([command, "0"]), NO interface token,
 * reply = createByteArray() -> the command's stdout.
 *
 * All methods BLOCK. Call them on [Privilege.dispatcher] only.
 */
class PserviceGateway(private val appContext: Context) : PrivilegeGateway {

    override val name: String = "pservice (PServerBinder)"

    private val lock = Any()
    private val seq = AtomicLong()

    @Volatile private var cached: IBinder? = null

    private fun binder(): IBinder? {
        cached?.let { if (it.isBinderAlive) return it }
        return try {
            val b = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java)
                .invoke(null, SERVICE) as? IBinder
            cached = b
            b
        } catch (t: Throwable) {
            Log.w(TAG, "getService($SERVICE): ${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }

    // NOTE: the brief's code listing for this file did not implement `probe()`,
    // leaving the class unable to satisfy PrivilegeGateway (compile error:
    // ABSTRACT_MEMBER_NOT_IMPLEMENTED). Added minimally, consistent with the
    // interface doc ("runs our commands as uid 0") and the same uid=0 check the
    // ViewModel already performs via exec("id"). Flagged in the Task 4 report.
    override fun probe(): Boolean = exec("id")?.contains("uid=0") == true

    override fun exec(command: String): String? {
        if (!CommandSafety.isSingleLine(command)) {
            Log.w(TAG, "refused a command containing a line break")
            return null
        }
        // Serialised: overlapping transactions come back empty.
        return synchronized(lock) { transact(command) }
    }

    private fun transact(command: String): String? {
        val b = binder() ?: return null
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeStringArray(arrayOf(command, "0"))
            // A `false` return (as opposed to a thrown exception) leaves `cached`
            // un-nulled even though it may now be stale. That's safe: binder()
            // re-checks isBinderAlive() on the next call and re-fetches if needed.
            if (!b.transact(CODE_EXEC, data, reply, 0)) return null
            reply.createByteArray()?.let { String(it) } ?: ""
        } catch (t: Throwable) {
            Log.w(TAG, "transact: ${t.javaClass.simpleName}: ${t.message}")
            cached = null
            null
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /**
     * Run [lines] as a script file, via `sh <path>`.
     *
     * Required for anything non-trivial, because of three pservice quirks: it
     * silently drops commands over roughly 300 characters, it does not reliably
     * interpret inline shell operators (redirects, `&`, a trailing `sleep`), and it
     * returns only the FIRST line of output. `sh <file>` is a plain two-token
     * command it runs faithfully, and the script's own operators then work.
     *
     * One file per call: two callers sharing a path overwrite each other's script.
     */
    fun execScript(lines: List<String>): String? {
        val script = File(appContext.cacheDir, "tc_${seq.incrementAndGet()}.sh")
        return try {
            script.writeText(lines.joinToString(separator = "\n", postfix = "\n"))
            exec("sh ${script.absolutePath}")
        } catch (t: Throwable) {
            Log.w(TAG, "execScript: ${t.javaClass.simpleName}: ${t.message}")
            null
        } finally {
            script.delete()
        }
    }

    /**
     * Run one of the app's `app_process` entry points as uid 0, returning its
     * `OK …` / `ERR …` result line.
     *
     * A bare root shell can run `am`, but `am` exposes no command for
     * IActivityTaskManager.moveRootTaskToDisplay. Running our own class in an
     * app_process VM does, and because that is not an app process, ART's
     * hidden-API enforcement does not apply.
     *
     * CLASSPATH is deliberately unquoted: the APK path contains no spaces, and
     * quoting it has been observed to stop app_process starting. className is a
     * different case: it is validated with [CommandSafety.isClassName] below,
     * then quoted like everything else that reaches this shell — validation
     * alone would be enough, since the allowed character set contains nothing a
     * shell reads, but quoting costs nothing here and keeps className from being
     * a silent exception to the "validate, then quote" rule.
     */
    fun runEntryPoint(className: String, vararg args: String): String? {
        if (!CommandSafety.isClassName(className)) {
            Log.w(TAG, "refused an invalid className")
            return null
        }
        val apk = appContext.applicationInfo.sourceDir
        val quotedClassName = CommandSafety.quote(className)
        val quoted = args.joinToString(" ") { CommandSafety.quote(it) }
        return execScript(
            listOf(
                "CLASSPATH=$apk app_process /system/bin $quotedClassName $quoted 2>&1 " +
                    "| grep -m1 -E '^(OK|ERR)'"
            )
        )?.trim()
    }

    private companion object {
        const val TAG = "ThorcutsPservice"
        const val SERVICE = "PServerBinder"
        const val CODE_EXEC = 0
    }
}
