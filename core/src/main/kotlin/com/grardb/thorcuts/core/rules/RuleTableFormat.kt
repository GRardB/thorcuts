package com.grardb.thorcuts.core.rules

import com.grardb.thorcuts.core.model.Control
import com.grardb.thorcuts.core.model.Trigger
import com.grardb.thorcuts.core.model.TriggerStyle

/**
 * The rule table's byte layout, and nothing else. Little-endian throughout.
 *
 * ```
 * header, 12 bytes:  "THRC" · version u16 · rule_count u16 · generation u32
 * rule, 32 bytes:    modifiers u8 · button_count u8 · style u8 · 0 · hold_ms u16 · 0 u16
 *                    · buttons[4] × (device u8 · type u8 · code u16 · value i16)
 * ```
 *
 * There is exactly ONE encoding of any table: buttons sorted by [BUTTON_ORDER], reserved
 * bytes and unused slots zero, hex lowercase. The native parser enforces the same, so a
 * fixture file pins both sides to the same bytes. Mirror of native/src/ruletable.h.
 */
object RuleTableFormat {
    const val MAGIC = "THRC"
    const val VERSION = 1
    const val HEADER_BYTES = 12
    const val RULE_BYTES = 32
    const val MAX_RULES = 64
    const val MAX_BYTES = HEADER_BYTES + MAX_RULES * RULE_BYTES   // 2060
    const val MAX_BUTTONS = Trigger.MAX_BUTTONS                    // 4

    /** The longest `T <gen> <hex>` line: "T " + ten digits + " " + two hex chars per byte. */
    const val MAX_LINE_CHARS = 2 + 10 + 1 + 2 * MAX_BYTES          // 4133

    /** The one canonical order of a rule's buttons. Value compares as the signed Int it is. */
    val BUTTON_ORDER: Comparator<Control> =
        compareBy<Control>({ it.device.slot }, { it.type }, { it.code }, { it.value })

    fun encode(triggers: List<Trigger>, generation: Int): ByteArray {
        require(generation >= 0) { "generation must be non-negative, was $generation" }
        require(triggers.size <= MAX_RULES) { "at most $MAX_RULES rules, got ${triggers.size}" }

        val out = ByteArray(HEADER_BYTES + RULE_BYTES * triggers.size)
        var p = 0
        fun u8(v: Int) { out[p++] = v.toByte() }
        fun u16(v: Int) { u8(v and 0xFF); u8((v ushr 8) and 0xFF) }
        fun u32(v: Int) { u16(v and 0xFFFF); u16((v ushr 16) and 0xFFFF) }

        for (c in MAGIC) u8(c.code)
        u16(VERSION)
        u16(triggers.size)
        u32(generation)

        for (t in triggers) {
            u8(t.modifiers.fold(0) { mask, m -> mask or (1 shl m.bit) })
            u8(t.buttons.size)
            u8(t.style.wire)
            u8(0)
            u16(if (t.style == TriggerStyle.HOLD) t.holdMs!! else 0)
            u16(0)
            val sorted = t.buttons.sortedWith(BUTTON_ORDER)
            for (i in 0 until MAX_BUTTONS) {
                val b = sorted.getOrNull(i)
                if (b == null) {
                    u16(0); u16(0); u16(0)
                } else {
                    u8(b.device.slot)
                    u8(b.type)
                    u16(b.code)
                    u16(b.value and 0xFFFF)   // two's complement i16
                }
            }
        }
        check(p == out.size) { "encoder wrote $p of ${out.size} bytes" }
        return out
    }

    private const val HEX_DIGITS = "0123456789abcdef"

    /** Lowercase, two characters per byte. Hand-rolled: the wire wants exactly this. */
    fun hex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX_DIGITS[v ushr 4]).append(HEX_DIGITS[v and 0xF])
        }
        return sb.toString()
    }
}
