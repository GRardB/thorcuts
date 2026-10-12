package com.grardb.thorcuts.core.helper

import com.grardb.thorcuts.core.rules.RuleTableFormat
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

    /** The helper holds the table we pushed as [generation]; it has [ruleCount] rules. */
    data class TableAccepted(val generation: Int, val ruleCount: Int) : HelperMessage

    /** The helper refused the table pushed as [generation]; nothing changed. [reason] is the
     *  helper's token, e.g. `hold rule 2`, `magic`, `hex`, `line`. */
    data class TableRejected(val generation: Int, val reason: String) : HelperMessage
}

/** Something the app tells the helper. A sealed interface, not an enum, because [PushTable]
 *  carries data. The five fixed commands keep their exact wire strings. */
sealed interface HelperCommand {
    val wire: String

    data object Handshake : HelperCommand { override val wire = "H" }
    data object Ping : HelperCommand { override val wire = "P" }
    data object ModeMatch : HelperCommand { override val wire = "M match" }
    data object ModeCapture : HelperCommand { override val wire = "M capture" }
    data object Stop : HelperCommand { override val wire = "S" }

    /**
     * `T <generation> <hex>`: a compiled rule table. [hex] is the table's bytes, lowercase,
     * at most 2 × [RuleTableFormat.MAX_BYTES] characters — the same limits the helper
     * enforces, checked here so a refusal is an exception at the call site rather than a
     * `T bad` line later. An EMPTY payload is allowed through: it is the helper's to refuse
     * (`short`), and refusing it here would hide that path from the protocol tests.
     */
    data class PushTable(val generation: Int, val hex: String) : HelperCommand {
        init {
            require(generation >= 0) { "generation must be non-negative, was $generation" }
            require(hex.length % 2 == 0) { "hex must have an even length, had ${hex.length}" }
            require(hex.length <= 2 * RuleTableFormat.MAX_BYTES) {
                "table of ${hex.length / 2} bytes exceeds ${RuleTableFormat.MAX_BYTES}"
            }
            require(hex.all { it in '0'..'9' || it in 'a'..'f' }) { "hex must be lowercase 0-9a-f" }
        }

        override val wire: String get() = "T $generation $hex"
    }
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
            "T" -> {
                // "ok <gen> <count>" or "bad <gen> <reason...>". The helper never originates
                // a generation; it echoes ours, so anything outside 0..Int.MAX_VALUE is noise.
                val p = rest.split(' ', limit = 3)
                if (p.size != 3) return null
                val gen = p[1].toIntOrNull() ?: return null
                if (gen < 0) return null
                when (p[0]) {
                    "ok" -> p[2].toIntOrNull()?.takeIf { it >= 0 }
                        ?.let { HelperMessage.TableAccepted(gen, it) }
                    "bad" -> if (p[2].isBlank()) null else HelperMessage.TableRejected(gen, p[2])
                    else -> null
                }
            }
            else -> null
        }
    }
}
