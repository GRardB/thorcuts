package com.grardb.thorcuts.platform.helper

import com.grardb.thorcuts.core.helper.HelperMessage
import com.grardb.thorcuts.core.rules.CompiledTable

/**
 * A [HelperChannel] that records what the coordinator asked of it and lets a test play the
 * helper: [emit] delivers a message through the callback the channel was built with, from
 * the test thread, as the supervisor's reader thread would. [outcome] is what [start]
 * answers; [onStart] runs inside [start] and [onStop] inside [stop], so a test can lose the
 * helper mid-start, or have it exit during a requested stop.
 */
class FakeChannel(private val onMessage: (HelperMessage) -> Unit) : HelperChannel {

    /** "start", "stop", "capture on" and "capture off", in order. */
    val calls = mutableListOf<String>()
    val pushed = mutableListOf<CompiledTable>()
    var outcome = HelperChannel.Start.STARTED
    var onStart: (() -> Unit)? = null
    var onStop: (() -> Unit)? = null

    override fun start(): HelperChannel.Start {
        calls += "start"
        onStart?.invoke()
        return outcome
    }

    override fun stop() {
        calls += "stop"
        onStop?.invoke()
    }

    override fun pushTable(table: CompiledTable) {
        pushed += table
    }

    override fun setCapture(on: Boolean) {
        calls += if (on) "capture on" else "capture off"
    }

    fun emit(message: HelperMessage) = onMessage(message)
}
