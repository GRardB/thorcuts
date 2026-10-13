package com.grardb.thorcuts.core.config

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The forward migration chain vN → vN+1 (parent spec §10), each step a pure function of the
 * JSON tree, applied in order and stamping `schemaVersion` after each.
 *
 * Injectable so the mechanism is tested now, while [CURRENT] is still empty. `internal`:
 * JsonObject stays out of :core's public surface; [ConfigCodec] is the only caller.
 */
internal class Migrations(private val steps: Map<Int, (JsonObject) -> JsonObject> = CURRENT) {

    /** [doc] at schema version [from], brought to [to]. A hole in the chain is an error. */
    fun migrate(doc: JsonObject, from: Int, to: Int = ConfigCodec.SCHEMA_VERSION): JsonObject {
        require(from in 1..to) { "cannot migrate from schema version $from to $to" }
        var current = doc
        for (version in from until to) {
            val step = steps[version]
                ?: error("no migration from schema version $version to ${version + 1}")
            val stamped = SCHEMA_VERSION_KEY to JsonPrimitive(version + 1)
            current = JsonObject(step(current) + stamped)
        }
        return current
    }

    companion object {
        const val SCHEMA_VERSION_KEY = "schemaVersion"

        /** Empty at schema version 1: there is nothing older to migrate from yet. */
        val CURRENT: Map<Int, (JsonObject) -> JsonObject> = emptyMap()
    }
}
