package com.grardb.thorcuts.core.model

/**
 * Which grabbed device a control lives on. Mirrors TC_DEV_PAD / TC_DEV_GPIO in
 * native/src/matcher.h; the rule table carries [slot].
 */
enum class Device(val slot: Int) {
    /** The AYN gamepad: face, shoulders, sticks, D-pad, Home, Back, Start, Select. */
    PAD(0),

    /** gpio-keys: the AYN button and Volume +. */
    GPIO(1),
}

/** The evdev event types a control may have. Mirrors TC_EV_KEY / TC_EV_ABS. */
object EvdevType {
    const val KEY = 1
    const val ABS = 3
}

/**
 * One bindable thing: a key's press, or an axis at a value. ABS_HAT0X at -1 is as
 * bindable as BTN_SOUTH at 1 (parent spec §6). How an ABS value is COMPARED at match
 * time is the matcher's business; the table carries it as a signed 16-bit value.
 */
data class Control(val device: Device, val type: Int, val code: Int, val value: Int) {
    init {
        require(type == EvdevType.KEY || type == EvdevType.ABS) {
            "type must be EV_KEY (${EvdevType.KEY}) or EV_ABS (${EvdevType.ABS}), was $type"
        }
        require(code in 0..0xFFFF) { "code must fit a u16, was $code" }
        require(value in -32768..32767) { "value must fit an i16, was $value" }
        if (type == EvdevType.KEY) {
            require(value == 1) { "a key is bound on its press (1), was $value" }
        }
        if (type == EvdevType.ABS) {
            require(value != 0) { "an axis is bound at a non-zero threshold; 0 is always pressed" }
        }
    }
}

/**
 * The fixed five modifiers (parent spec §6). Not user-extensible.
 *
 * [bit] is the wire contract — the rule table's modifier bitmask — and must match
 * TC_MOD_* in native/src/ruletable.h. The codes are MEASURED on the device:
 * docs/superpowers/findings/2026-09-30-m1-device-recon.md, control table. The C side
 * carries the same five in tc_modifier_controls; nothing pins the two to each other
 * but that table, so change both or neither.
 */
enum class ModifierKey(val bit: Int, val control: Control) {
    AYN(0, Control(Device.GPIO, EvdevType.KEY, 194, 1)),      // KEY_F24, on gpio-keys
    HOME(1, Control(Device.PAD, EvdevType.KEY, 102, 1)),      // KEY_HOME
    BACK(2, Control(Device.PAD, EvdevType.KEY, 158, 1)),      // KEY_BACK
    START(3, Control(Device.PAD, EvdevType.KEY, 315, 1)),     // BTN_START
    SELECT(4, Control(Device.PAD, EvdevType.KEY, 314, 1));    // BTN_SELECT

    companion object {
        /** True if [c] is one of the five. A modifier can never also be a button. */
        fun isModifier(c: Control): Boolean = entries.any { it.control == c }
    }
}
