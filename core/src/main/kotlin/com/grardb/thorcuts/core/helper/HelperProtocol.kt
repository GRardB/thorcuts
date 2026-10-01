package com.grardb.thorcuts.core.helper

import com.grardb.thorcuts.core.safety.CommandSafety

/** Something the helper said. */
sealed interface HelperMessage {
    /** Devices resolved and opened; the helper is live. */
    data class Ready(val deviceCount: Int) : HelperMessage

    /** One raw evdev event. [value] is signed: ABS_HAT0X reports -1 / 0 / +1. */
    data class Event(val device: Int, val type: Int, val code: Int, val value: Int) : HelperMessage

    /** A rule matched. */
    data class Fired(val ruleId: Int) : HelperMessage

    /** Diagnostic text. */
    data class Log(val text: String) : HelperMessage

    /** The helper is shutting down, having released every device. */
    data class Exiting(val reason: String) : HelperMessage
}

/** Something the app tells the helper. */
enum class HelperCommand(val wire: String) {
    Handshake("H"),
    Ping("P"),
    ModeMatch("M match"),
    ModeCapture("M capture"),
    Stop("S"),
}

/**
 * The line-oriented protocol between the app and the C helper.
 *
 * One message per line, strictly. A value containing a line break would inject a
 * second message, so any such line is rejected outright rather than cleaned up.
 */
object HelperProtocol {

    fun encode(command: HelperCommand): String = command.wire

    fun parse(line: String): HelperMessage? {
        if (!CommandSafety.isSingleLine(line)) return null
        if (line.isEmpty()) return null

        val tag = line.substringBefore(' ')
        val rest = if (line.length > tag.length) line.substring(tag.length + 1) else ""

        return when (tag) {
            "R" -> rest.toIntOrNull()?.let { HelperMessage.Ready(it) }
            "F" -> rest.toIntOrNull()?.let { HelperMessage.Fired(it) }
            "E" -> {
                val p = rest.split(' ')
                if (p.size != 4) return null
                val nums = p.map { it.toIntOrNull() ?: return null }
                HelperMessage.Event(nums[0], nums[1], nums[2], nums[3])
            }
            "L" -> if (rest.isEmpty()) null else HelperMessage.Log(rest)
            "X" -> if (rest.isEmpty()) null else HelperMessage.Exiting(rest)
            else -> null
        }
    }
}
