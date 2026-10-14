package com.grardb.thorcuts.core.config

import com.grardb.thorcuts.core.model.Shortcut
import com.grardb.thorcuts.core.rules.ConflictDetector

/** What an import did: the merged list, and which ids were added, skipped, or dropped. */
data class MergeResult(
    val config: ThorcutsConfig,
    val added: List<String>,
    val skipped: List<String>,
    val dropped: List<Dropped>,
)

/**
 * Import as a merge that never replaces (parent spec §10). Identity is the `id`, not the
 * trigger — but a config may not hold two enabled shortcuts with one trigger where both
 * apply ([ThorcutsConfig], Rule 2), so an imported shortcut that would repeat an accepted
 * one's trigger is skipped exactly as an id already present is: the merge decides nothing
 * silently, and the report names it.
 */
object ConfigMerge {

    /**
     * [current] plus every imported shortcut whose id is new and whose trigger repeats none
     * of the accepted ones ([current], then the ones added before it), in import order. An
     * id already present, or a trigger that would conflict, is skipped. [current]'s unknown
     * top-level members are kept and the imported file's are not merged; an added shortcut
     * brings its own unknown members, a skipped one leaves the current one's in place.
     * [imported]'s drops are passed through for the report.
     */
    fun merge(current: ThorcutsConfig, imported: DecodeResult.Ok): MergeResult {
        val have = current.shortcuts.mapTo(HashSet()) { it.id }
        val accepted = ArrayList(current.shortcuts)
        val added = ArrayList<Shortcut>()
        val skipped = ArrayList<String>()
        for (s in imported.config.shortcuts) {
            if (s.id in have || ConflictDetector.conflict(accepted, s) != null) {
                skipped += s.id
            } else {
                added += s
                accepted += s
            }
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
