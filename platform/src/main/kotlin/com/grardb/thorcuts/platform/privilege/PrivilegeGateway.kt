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

    /** Human-readable name, for the diagnostics screen. */
    val name: String

    /** True if this gateway is reachable and runs our commands as uid 0. */
    fun probe(): Boolean

    /** Run [command], returning its stdout, or null if the call failed. */
    fun exec(command: String): String?
}
