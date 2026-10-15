package com.grardb.thorcuts.platform.helper

import com.grardb.thorcuts.core.rules.CompiledTable

/**
 * The app's side of the helper channel as the coordinator sees it. [HelperSupervisor] is the
 * one real implementation; a fake stands in on the host, so the holder's bookkeeping — what
 * a Ready, an ack, a fire or a loss does to the store and the switch — is testable without
 * a socket. The helper's messages come back through the callback the implementation was
 * built with.
 *
 * [start] and [stop] BLOCK — seconds, on the paths that assess and restore the pad — so
 * call them on Privilege.dispatcher. [pushTable] and [setCapture] are thread-safe and return
 * at once.
 */
interface HelperChannel {

    /** What [start] did. */
    enum class Start {
        /** Bound and launched: a fresh session. */
        STARTED,

        /**
         * Nothing done: a session was already running. For the coordinator, which only
         * asks when it holds no session, this names the supervisor's one race — a session's
         * terminal message is delivered before its own session flag resets — so it is NOT a
         * start, and the caller must not claim one.
         */
        ALREADY_RUNNING,

        /**
         * The socket could not be bound — the one failure that used to reach logcat only;
         * every other failure, a missing binary or a silent pservice included, arrives as a
         * [com.grardb.thorcuts.core.helper.HelperMessage].
         */
        NOT_BOUND,
    }

    /** Bind the socket and launch the helper. */
    fun start(): Start

    /** Ask the helper to go, and wait for it to go. A no-op when nothing runs. */
    fun stop()

    /** Send a compiled rule table. Call it on Ready and never earlier. */
    fun pushTable(table: CompiledTable)

    /** Put the helper in capture mode — every raw event comes up as an Event and nothing but
     *  the panic goes out — or back in match mode. Dropped with a log line when no helper is
     *  connected; the coordinator re-sends on the next Ready. */
    fun setCapture(on: Boolean)
}
