package com.grardb.thorcuts.core.rules

import com.grardb.thorcuts.core.model.Scope
import com.grardb.thorcuts.core.model.Shortcut

/**
 * What was pushed: the bytes, and the list the rule indices mean. Rule `i` of the
 * table is `shortcuts[i]`; [generation] says which list, so a later `F <gen> <index>`
 * resolves against the right one even if a newer table has since been pushed.
 */
class CompiledTable(val generation: Int, val shortcuts: List<Shortcut>, val bytes: ByteArray) {
    val hex: String = RuleTableFormat.hex(bytes)
    val ruleCount: Int get() = shortcuts.size
}

object RuleCompiler {
    const val MAX_RULES = RuleTableFormat.MAX_RULES

    /**
     * The shortcuts that apply in [foreground], in input order.
     *
     * `null` means NO scoping information — no monitor, nothing focused, or the app in front
     * not yet known (the store's `foreground`). Global rules apply (their exclusions cannot
     * be evaluated, so none is); app-scoped rules need a positive match they cannot get. A
     * package no scope names selects the same list as `null`.
     */
    fun select(shortcuts: List<Shortcut>, foreground: String?): List<Shortcut> =
        shortcuts.filter { s ->
            s.enabled && when (val scope = s.scope) {
                is Scope.Global -> foreground == null || foreground !in scope.excluded
                is Scope.Apps -> foreground != null && foreground in scope.included
            }
        }

    /** [select], then encode. The caller owns [generation]: whoever decides to recompile. */
    fun compile(shortcuts: List<Shortcut>, foreground: String?, generation: Int): CompiledTable {
        val selected = select(shortcuts, foreground)
        require(selected.size <= MAX_RULES) { overflow(selected.size, foreground) }
        val bytes = RuleTableFormat.encode(selected.map { it.trigger }, generation)
        return CompiledTable(generation, selected, bytes)
    }

    /**
     * Every selection [shortcuts] can produce fits the table, or a throw naming the first
     * that does not: the selection at `null` and at each package some scope names. A package
     * no scope names selects exactly what `null` does — every global rule, no app rule — so
     * those are all the selections there are. The store's `update` validates with this, so
     * an edit can never write a list that compiles at one app and overflows at another
     * (config spec, Important 2).
     */
    fun check(shortcuts: List<Shortcut>) {
        val named = shortcuts.flatMap { s ->
            when (val scope = s.scope) {
                is Scope.Global -> scope.excluded
                is Scope.Apps -> scope.included
            }
        }.toSortedSet()
        for (where in listOf<String?>(null) + named) {
            val n = select(shortcuts, where).size
            require(n <= MAX_RULES) { overflow(n, where) }
        }
    }

    /** The refusal [compile] and [check] share, naming the app when there is one: a loaded
     *  file can overflow for the app in front, and the status line must say which. */
    private fun overflow(n: Int, where: String?) =
        "$n shortcuts apply ${if (where == null) "here" else "in $where"}; " +
            "the table holds at most $MAX_RULES"
}
