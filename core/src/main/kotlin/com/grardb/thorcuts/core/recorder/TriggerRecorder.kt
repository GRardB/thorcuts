package com.grardb.thorcuts.core.recorder

import com.grardb.thorcuts.core.helper.HelperMessage
import com.grardb.thorcuts.core.model.Control
import com.grardb.thorcuts.core.model.Device
import com.grardb.thorcuts.core.model.EvdevType
import com.grardb.thorcuts.core.model.ModifierKey
import com.grardb.thorcuts.core.model.ThorControls

/**
 * What the user is holding and what they have pressed while the editor records a trigger:
 * a value fed the helper's raw events in capture mode. Pure — no clock, no thread, no
 * Android — so the coordinator can hold it and a test can drive it.
 *
 * A control counts from its down. The recording is [complete] once something was pressed
 * and everything is up again; events after that are ignored, and the editor starts a fresh
 * recorder to record again. Keys and the D-pad only: the sticks and the trigger ramps need a
 * threshold the user would have to choose, so their events are ignored (the file format
 * still holds such a control, and a shortcut read from a file keeps it). An up for a
 * control never seen down — held from before the recording started — is ignored.
 */
data class TriggerRecorder(
    val held: Set<Control> = emptySet(),
    val seen: Set<Control> = emptySet(),
) {
    val complete: Boolean get() = seen.isNotEmpty() && held.isEmpty()

    /** The modifiers pressed, in the fixed order. */
    val modifiers: Set<ModifierKey>
        get() = ModifierKey.entries.filterTo(LinkedHashSet()) { it.control in seen }

    /** Everything else pressed. */
    val buttons: Set<Control>
        get() = seen.filterTo(LinkedHashSet()) { !ModifierKey.isModifier(it) }

    fun feed(event: HelperMessage.Event): TriggerRecorder {
        if (complete) return this
        val device = Device.entries.firstOrNull { it.slot == event.device } ?: return this
        return when (event.type) {
            EvdevType.KEY -> when (event.value) {
                1 -> down(Control(device, EvdevType.KEY, event.code, 1))
                0 -> up(Control(device, EvdevType.KEY, event.code, 1))
                else -> this                                            // 2: autorepeat
            }
            EvdevType.ABS -> {
                if (event.code !in ThorControls.DPAD_AXES) return this
                // One axis, two directions: whichever was held is released by any new
                // value, and a non-zero value is the down of that direction.
                val released = held.filter {
                    it.type == EvdevType.ABS && it.device == device && it.code == event.code
                }
                val r = released.fold(this) { acc, c -> acc.up(c) }
                val value = event.value.coerceIn(-1, 1)
                if (value == 0) r else r.down(Control(device, EvdevType.ABS, event.code, value))
            }
            else -> this
        }
    }

    private fun down(c: Control) = copy(held = held + c, seen = seen + c)

    private fun up(c: Control) = if (c in held) copy(held = held - c) else this
}
