package com.grardb.thorcuts.platform.privilege

/**
 * Something that can run a shell command with elevated privilege.
 *
 * An interface with one implementation on purpose: a `su` gateway for already-rooted
 * users is a plausible later addition, and Thorcuts must never *require* root.
 * Shizuku is explicitly not a future implementation — see the design, section 4.
 *
 * Every method BLOCKS. Call them only on [Privilege.dispatcher].
 */
interface PrivilegeGateway {

    /** Human-readable name, for logs and the device runs; nothing renders it since M3. */
    val name: String

    /** True if this gateway is reachable and runs our commands as uid 0. A device-run
     *  diagnostic since M3: nothing in the app calls it. */
    fun probe(): Boolean

    /** Run [command], returning its stdout, or null if the call failed. */
    fun exec(command: String): String?

    /**
     * Run [lines] as a script file (`sh <path>`), returning its stdout, or null if the
     * call failed. The route for anything with a pipe, a redirect or a `&`: a script's
     * operators work where an inline command's may not (parent spec, Appendix B).
     */
    fun execScript(lines: List<String>): String?

    /**
     * Run one of the app's `app_process` entry points as uid 0 with [args], returning
     * its single `OK …` / `ERR …` result line, or null if the call failed. Arguments
     * reach the shell quoted by the implementation; callers pass them raw.
     */
    fun runEntryPoint(className: String, vararg args: String): String?
}
