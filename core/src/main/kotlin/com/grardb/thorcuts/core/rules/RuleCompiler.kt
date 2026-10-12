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
     * `null` means NO scoping information — there is no foreground monitor until M4, so
     * every push until then is `null`. Global rules apply (their exclusions cannot be
     * evaluated, so none is); app-scoped rules need a positive match they cannot get.
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
        require(selected.size <= MAX_RULES) {
            "${selected.size} shortcuts apply here; the table holds at most $MAX_RULES"
        }
        val bytes = RuleTableFormat.encode(selected.map { it.trigger }, generation)
        return CompiledTable(generation, selected, bytes)
    }
}
