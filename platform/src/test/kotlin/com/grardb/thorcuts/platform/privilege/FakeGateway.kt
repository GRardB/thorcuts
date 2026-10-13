package com.grardb.thorcuts.platform.privilege

/**
 * A [PrivilegeGateway] that records every call and answers from a queue of canned
 * replies, so `TaskActions` and `ActionDispatcher` can be tested on the host: which
 * command they build, and how they shape what comes back. A null reply is legal — it
 * is what the real gateway returns when pservice does not answer.
 */
class FakeGateway(vararg replies: String?) : PrivilegeGateway {

    sealed interface Call {
        data class Exec(val command: String) : Call
        data class Script(val lines: List<String>) : Call
        data class EntryPoint(val className: String, val args: List<String>) : Call
    }

    override val name: String = "fake"

    /** Every call, in order. */
    val calls = mutableListOf<Call>()

    private val queue = ArrayDeque<String?>(replies.toList())

    override fun probe(): Boolean = true

    override fun exec(command: String): String? {
        calls += Call.Exec(command)
        return next()
    }

    override fun execScript(lines: List<String>): String? {
        calls += Call.Script(lines)
        return next()
    }

    override fun runEntryPoint(className: String, vararg args: String): String? {
        calls += Call.EntryPoint(className, args.toList())
        return next()
    }

    private fun next(): String? {
        check(queue.isNotEmpty()) { "no reply queued for ${calls.last()}" }
        return queue.removeFirst()
    }
}
