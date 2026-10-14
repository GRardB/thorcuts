package com.grardb.thorcuts.core.config

import com.grardb.thorcuts.core.model.Shortcut
import com.grardb.thorcuts.core.rules.ConflictDetector
import kotlinx.serialization.json.JsonElement

/**
 * The user's shortcut list (parent spec §10), as an immutable value.
 *
 * Ids are unique: an import merges by them and [unknown] is keyed by them. No enabled
 * shortcut repeats an earlier enabled one's trigger where both apply — the matcher would
 * fire the earlier and never the later (conflict-detector spec, Rule 2; the codec drops
 * such an entry on load, the merge skips one on import, and `withEnabled` onto a parked
 * duplicate throws, which the store reports as a rejected write). [unknown] is what the
 * codec read and did not understand, carried so that a write by this version does not
 * strip what a newer one wrote.
 */
data class ThorcutsConfig(
    val shortcuts: List<Shortcut>,
    val unknown: UnknownFields = UnknownFields.NONE,
) {
    init {
        val repeated = shortcuts.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys
        require(repeated.isEmpty()) { "shortcut ids repeat: $repeated" }
        ConflictDetector.firstConflict(shortcuts)?.let {
            throw IllegalArgumentException(it.reason)
        }
    }

    fun shortcut(id: String): Shortcut? = shortcuts.firstOrNull { it.id == id }

    /** The same list with one shortcut's [Shortcut.enabled] changed; the same config when
     *  [id] is absent. */
    fun withEnabled(id: String, enabled: Boolean): ThorcutsConfig =
        copy(shortcuts = shortcuts.map { if (it.id == id) it.copy(enabled = enabled) else it })
}

/**
 * JSON members the codec did not understand, preserved verbatim (parent spec §10): the
 * top-level object's, and each shortcut's keyed by its id.
 *
 * Opaque on purpose. The kotlinx types stay inside :core — the constructor and the maps are
 * `internal` — so a consumer compiles without the JSON artifact on its classpath, and nothing
 * outside the codec and the merge can shape what gets written back.
 */
class UnknownFields internal constructor(
    internal val top: Map<String, JsonElement>,
    internal val perShortcut: Map<String, Map<String, JsonElement>>,
) {
    val isEmpty: Boolean get() = top.isEmpty() && perShortcut.isEmpty()

    override fun equals(other: Any?): Boolean =
        other is UnknownFields && top == other.top && perShortcut == other.perShortcut

    override fun hashCode(): Int = 31 * top.hashCode() + perShortcut.hashCode()

    override fun toString(): String =
        "UnknownFields(top=${top.keys}, perShortcut=${perShortcut.mapValues { it.value.keys }})"

    companion object {
        val NONE = UnknownFields(emptyMap(), emptyMap())
    }
}
