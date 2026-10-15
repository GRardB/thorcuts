package com.grardb.thorcuts.core.model

/**
 * The Thor's controls by name: the one place `:core` names a control, and the editor's whole
 * vocabulary. The codes are the measured ones (M1 findings, the control table), the same the
 * modifiers carry in [ModifierKey]. A control this table does not know — one read from a
 * file — still gets a name, so nothing the editor shows is ever blank.
 */
object ThorControls {
    /** The D-pad's two axes, at −1 / +1: the only ABS controls the recorder takes. */
    const val HAT0X = 16
    const val HAT0Y = 17
    val DPAD_AXES: Set<Int> = setOf(HAT0X, HAT0Y)

    private val MODIFIERS = mapOf(
        ModifierKey.AYN to "AYN",
        ModifierKey.HOME to "Home",
        ModifierKey.BACK to "Back",
        ModifierKey.START to "Start",
        ModifierKey.SELECT to "Select",
    )

    private val PAD_KEYS = mapOf(
        304 to "A", 305 to "B", 307 to "X", 308 to "Y",
        310 to "L1", 311 to "R1", 312 to "L2", 313 to "R2",
        317 to "L3", 318 to "R3",
    )

    private val GPIO_KEYS = mapOf(115 to "Volume +")

    /** The buttons' order in a description: by device, type, code, value — the table's. */
    private val ORDER =
        compareBy<Control>({ it.device.slot }, { it.type }, { it.code }, { it.value })

    fun name(modifier: ModifierKey): String = MODIFIERS.getValue(modifier)

    fun name(control: Control): String {
        ModifierKey.entries.firstOrNull { it.control == control }?.let { return name(it) }
        return when {
            control.type == EvdevType.KEY && control.device == Device.PAD ->
                PAD_KEYS[control.code] ?: "key ${control.code}"
            control.type == EvdevType.KEY -> GPIO_KEYS[control.code] ?: "key ${control.code}"
            control.code == HAT0X -> if (control.value < 0) "D-pad left" else "D-pad right"
            control.code == HAT0Y -> if (control.value < 0) "D-pad up" else "D-pad down"
            else -> "axis ${control.code} at ${control.value}"
        }
    }

    /** `AYN + X`, `Select + R1 · timer 400 ms`: the modifiers in the fixed order, then the
     *  buttons in [ORDER], then the timer when there is one. */
    fun describe(trigger: Trigger): String {
        val modifiers = ModifierKey.entries.filter { it in trigger.modifiers }.map { name(it) }
        val buttons = trigger.buttons.sortedWith(ORDER).map { name(it) }
        val combo = (modifiers + buttons).joinToString(" + ")
        return if (trigger.style == TriggerStyle.HOLD) "$combo · timer ${trigger.holdMs} ms"
        else combo
    }
}
