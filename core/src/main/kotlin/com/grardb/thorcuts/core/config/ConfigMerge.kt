package com.grardb.thorcuts.core.config

import com.grardb.thorcuts.core.model.Shortcut

/** What an import did: the merged list, and which ids were added, skipped, or dropped. */
data class MergeResult(
    val config: ThorcutsConfig,
    val added: List<String>,
    val skipped: List<String>,
    val dropped: List<Dropped>,
)

/**
 * Import as a merge that never replaces (parent spec §10). Identity is the `id`, not the
 * trigger: two shortcuts with the same trigger in the same scope are a conflict for the
 * editor to warn about, not for an import to decide silently.
 */
object ConfigMerge {

    /**
     * [current] plus every imported shortcut whose id is new, in import order. An id already
     * present is skipped. [current]'s unknown top-level members are kept and the imported
     * file's are not merged; an added shortcut brings its own unknown members, a skipped one
     * leaves the current one's in place. [imported]'s drops are passed through for the report.
     */
    fun merge(current: ThorcutsConfig, imported: DecodeResult.Ok): MergeResult {
        val have = current.shortcuts.mapTo(HashSet()) { it.id }
        val added = ArrayList<Shortcut>()
        val skipped = ArrayList<String>()
        for (s in imported.config.shortcuts) {
            if (s.id in have) skipped += s.id else added += s
        }
        val carried = added.mapNotNull { s ->
            imported.config.unknown.perShortcut[s.id]?.let { s.id to it }
        }
        val unknown = UnknownFields(current.unknown.top, current.unknown.perShortcut + carried)
        return MergeResult(
            config = ThorcutsConfig(current.shortcuts + added, unknown),
            added = added.map { it.id },
            skipped = skipped,
            dropped = imported.dropped,
        )
    }
}
