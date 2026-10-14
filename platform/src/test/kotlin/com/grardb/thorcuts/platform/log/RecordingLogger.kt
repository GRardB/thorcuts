package com.grardb.thorcuts.platform.log

/** A [Logger] that keeps every line, level-prefixed, so a test can pin a token. */
class RecordingLogger : Logger {

    val lines = mutableListOf<String>()

    override fun i(text: String) {
        lines += "I $text"
    }

    override fun w(text: String) {
        lines += "W $text"
    }

    override fun e(text: String, error: Throwable) {
        lines += "E $text: $error"
    }

    fun has(token: String): Boolean = lines.any { token in it }
}
