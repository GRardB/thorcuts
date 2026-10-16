package com.grardb.thorcuts.core.rules

import com.grardb.thorcuts.core.model.ModifierKey
import com.grardb.thorcuts.core.model.Scope
import com.grardb.thorcuts.core.model.Shortcut
import com.grardb.thorcuts.core.model.Trigger
import com.grardb.thorcuts.core.model.TriggerStyle

/**
 * What the matcher would do with two shortcuts in one table, decided before the table
 * exists (the matcher spec's "Ties": same sets, the lowest index fires and the other never
 * does), and parent spec §6's one residual warning. Pure functions over the model; no
 * state, no Android, no file.
 *
 * `ThorcutsConfig` refuses a list with a [Conflict] in it; the codec drops the later
 * shortcut on load and the merge skips an imported one. [warnings] is the editor's to call
 * on the trigger being recorded (conflict-detector spec).
 */
object ConflictDetector {

    /** What the editor should say about a trigger on its own. Data only: the editor writes
     *  the copy. */
    enum class Warning {
        /** A timed trigger on Start or Select with a button: the helper must wait out holdMs
         *  before it can pass the button through, so the combo reaches a game late (§6's
         *  residual limitation). A PRESS on the same keys is decided on the down, and a bare
         *  timed Select adds nothing to the deferral every Select rule already carries. */
        HOLD_ON_GAMEPLAY_MODIFIER,
    }

    /** [later] repeats [earlier]'s trigger where both apply ([where]); only one of them ever
     *  fires — [earlier], unless [later] is a PRESS and [earlier] a HOLD on the same sets, where
     *  the matcher prefers the PRESS (see [sameTrigger]). Only a file written before this rule,
     *  or by hand, can hold such a pair. */
    data class Conflict(val earlier: Shortcut, val later: Shortcut, val where: Scope) {
        /** One text for the model's `require`, the codec's `Dropped` and the store's
         *  `Rejected`: the two ids (what the editor shows) and where they meet. */
        val reason: String
            get() = "'${later.id}' repeats the trigger of '${earlier.id}' " + when (where) {
                is Scope.Global ->
                    if (where.excluded.isEmpty()) "everywhere"
                    else "everywhere except ${where.excluded.sorted().joinToString(", ")}"
                is Scope.Apps -> "in ${where.included.sorted().joinToString(", ")}"
            }
    }

    /**
     * Whether the matcher would treat [a] and [b] as one rule: the same modifiers and the
     * same buttons, and either the same style or at least one button. A bare PRESS and a
     * bare HOLD on the same modifiers are tap-versus-hold and both fire (§6's `tap AYN` /
     * `hold AYN 500ms`); with a button the PRESS fires on the down and the HOLD never arms,
     * whichever is listed first. holdMs never matters: the lower index arms, the other never.
     */
    fun sameTrigger(a: Trigger, b: Trigger): Boolean =
        a.modifiers == b.modifiers && a.buttons == b.buttons &&
            (a.style == b.style || a.buttons.isNotEmpty())

    /**
     * Where [a] and [b] both apply, as a scope of its own; null where they never meet. Two
     * globals always meet (everywhere outside both exclusion sets); a global and an app list
     * meet in the listed apps the global does not exclude; two app lists meet in the apps
     * they share. This is `RuleCompiler.select`'s predicate, asked of both at once.
     */
    fun overlap(a: Scope, b: Scope): Scope? = when {
        a is Scope.Global && b is Scope.Global -> Scope.Global(a.excluded + b.excluded)
        a is Scope.Global && b is Scope.Apps -> apps(b.included - a.excluded)
        a is Scope.Apps && b is Scope.Global -> apps(a.included - b.excluded)
        a is Scope.Apps && b is Scope.Apps -> apps(a.included intersect b.included)
        else -> error("unreachable: Scope is sealed")
    }

    /** The first of [accepted] that [candidate] would repeat where both apply, or null.
     *  Disabled shortcuts are in no table and conflict with nothing, on either side. */
    fun conflict(accepted: List<Shortcut>, candidate: Shortcut): Conflict? {
        if (!candidate.enabled) return null
        for (earlier in accepted) {
            if (!earlier.enabled || !sameTrigger(earlier.trigger, candidate.trigger)) continue
            val where = overlap(earlier.scope, candidate.scope) ?: continue
            return Conflict(earlier, candidate, where)
        }
        return null
    }

    /** The first conflict in list order: each shortcut against the ones before it. */
    fun firstConflict(shortcuts: List<Shortcut>): Conflict? {
        for (i in shortcuts.indices) {
            conflict(shortcuts.subList(0, i), shortcuts[i])?.let { return it }
        }
        return null
    }

    /** The warnings [trigger] carries on its own. A list so a second kind can join later. */
    fun warnings(trigger: Trigger): List<Warning> {
        val gameplay = trigger.modifiers.any { it in GAMEPLAY }
        val late = trigger.style == TriggerStyle.HOLD && trigger.buttons.isNotEmpty() && gameplay
        return if (late) listOf(Warning.HOLD_ON_GAMEPLAY_MODIFIER) else emptyList()
    }

    private fun apps(included: Set<String>): Scope? =
        if (included.isEmpty()) null else Scope.Apps(included)

    /** §6's gameplay modifiers: the two a game reads as buttons of its own. */
    private val GAMEPLAY = setOf(ModifierKey.START, ModifierKey.SELECT)
}
