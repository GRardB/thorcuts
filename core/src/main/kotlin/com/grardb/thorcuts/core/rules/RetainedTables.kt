package com.grardb.thorcuts.core.rules

import com.grardb.thorcuts.core.model.Shortcut

/** Which table a fire refers to, and the Shortcut it names. */
sealed interface FireResolution {
    data class Hit(val shortcut: Shortcut, val table: CompiledTable, val index: Int) :
        FireResolution

    /** The generation matches no retained table: the fire is from a table already pruned. */
    data class Stale(val generation: Int) : FireResolution

    /** The generation is retained but the index is not a rule of it. A helper bug. */
    data class OutOfRange(val generation: Int, val index: Int, val ruleCount: Int) : FireResolution
}

/**
 * The tables the app has pushed and may still receive a fire from. Immutable; every
 * operation returns the next value. Newest last.
 *
 * The retention rule (matcher spec): keep every table pushed since the last one the
 * helper reported `installed`, plus that one. A push mid-session is deferred by the
 * helper until the session closes, and a session only ever fires from the INSTALLED
 * table — so a `T ok … installed` for generation g is proof nothing older than g can
 * fire again, and [installed] prunes exactly that. A `deferred` ack prunes nothing.
 * [MAX_RETAINED] bounds the list for the case that is not real (a session outliving
 * eight pushes); a fire from a dropped table resolves [FireResolution.Stale].
 */
class RetainedTables(val tables: List<CompiledTable> = emptyList()) {

    fun push(next: CompiledTable): RetainedTables =
        RetainedTables((tables + next).takeLast(MAX_RETAINED))

    fun installed(generation: Int): RetainedTables =
        RetainedTables(tables.filter { it.generation >= generation })

    fun resolve(generation: Int, index: Int): FireResolution {
        val table = tables.lastOrNull { it.generation == generation }
            ?: return FireResolution.Stale(generation)
        val shortcut = table.shortcuts.getOrNull(index)
            ?: return FireResolution.OutOfRange(generation, index, table.ruleCount)
        return FireResolution.Hit(shortcut, table, index)
    }

    companion object {
        const val MAX_RETAINED = 8
    }
}
