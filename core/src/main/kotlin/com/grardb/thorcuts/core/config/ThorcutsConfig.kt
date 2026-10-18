package com.grardb.thorcuts.core.config

import com.grardb.thorcuts.core.model.Shortcut
import com.grardb.thorcuts.core.rules.ConflictDetector
import kotlinx.serialization.json.JsonElement

/**
 * The user's shortcut list (parent spec §10), as an immutable value.
 *
 * Ids are unique: an import merges by them and [unknown] is keyed by them. No enabled
 * shortcut repeats an earlier enabled one's trigger where both apply — the matcher would
 * run only one of the two (conflict-detector spec, Rule 2; the codec drops
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
        require(repeated.isEmpty()) { "Shortcut names repeat: ${repeated.joinToString(", ")}" }
        ConflictDetector.firstConflict(shortcuts)?.let {
            throw IllegalArgumentException(it.reason)
        }
    }

    fun shortcut(id: String): Shortcut? = shortcuts.firstOrNull { it.id == id }

    /** The same list with one shortcut's [Shortcut.enabled] changed; the same config when
     *  [id] is absent. */
    fun withEnabled(id: String, enabled: Boolean): ThorcutsConfig =
        copy(shortcuts = shortcuts.map { if (it.id == id) it.copy(enabled = enabled) else it })

    /** The same list with [shortcut] appended. A name already in the list is refused here,
     *  naming it (`init` would say "names repeat"); a repeated trigger where both apply is
     *  refused by `init` — the store reports either as a rejected write. */
    fun added(shortcut: Shortcut): ThorcutsConfig {
        require(shortcuts.none { it.id == shortcut.id }) {
            "A shortcut named '${shortcut.id}' already exists"
        }
        return copy(shortcuts = shortcuts + shortcut)
    }

    /**
     * The same list with the shortcut named [replacing] replaced in place by [shortcut] —
     * a rename when the two ids differ, the row keeping its place and the unknown members the
     * file kept under the old name moving to the new one. An absent [replacing] is refused:
     * the editor replaces what it opened, and silently appending would hide a shortcut that
     * went away under it. A new name another shortcut already has is refused by name, as
     * [added] refuses one.
     */
    fun replaced(shortcut: Shortcut, replacing: String = shortcut.id): ThorcutsConfig {
        require(shortcuts.any { it.id == replacing }) { "No shortcut '$replacing' to replace" }
        require(shortcut.id == replacing || shortcuts.none { it.id == shortcut.id }) {
            "A shortcut named '${shortcut.id}' already exists"
        }
        return copy(
            shortcuts = shortcuts.map { if (it.id == replacing) shortcut else it },
            unknown = unknown.renamed(replacing, shortcut.id),
        )
    }

    /** The same list without the shortcut [id], and without the unknown members the file
     *  kept under that id — they are keyed by it and would otherwise outlive it (config spec,
     *  T2-4). The same config when [id] is absent. */
    fun without(id: String): ThorcutsConfig =
        copy(shortcuts = shortcuts.filter { it.id != id }, unknown = unknown.without(id))
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

    /** The same members without those kept under the shortcut [id]. */
    internal fun without(id: String): UnknownFields = UnknownFields(top, perShortcut - id)

    /** The same members, those kept under [from] now under [to]; the same when the two are
     *  one name or nothing was kept under [from]. */
    internal fun renamed(from: String, to: String): UnknownFields {
        if (from == to) return this
        val moved = perShortcut[from] ?: return this
        return UnknownFields(top, perShortcut - from + (to to moved))
    }

    override fun equals(other: Any?): Boolean =
        other is UnknownFields && top == other.top && perShortcut == other.perShortcut

    override fun hashCode(): Int = 31 * top.hashCode() + perShortcut.hashCode()

    override fun toString(): String =
        "UnknownFields(top=${top.keys}, perShortcut=${perShortcut.mapValues { it.value.keys }})"

    companion object {
        val NONE = UnknownFields(emptyMap(), emptyMap())
    }
}
