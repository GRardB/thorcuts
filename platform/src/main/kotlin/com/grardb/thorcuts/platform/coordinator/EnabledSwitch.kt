package com.grardb.thorcuts.platform.coordinator

/**
 * The persisted "shortcuts on" switch. Device state, not config: the config file is the
 * user's shortcut list and what import/export carries, and a boot receiver has to read this
 * before any store has loaded. [read] is synchronous and cheap — the coordinator reads it at
 * construction, on whatever thread builds it.
 */
interface EnabledSwitch {
    fun read(): Boolean
    fun write(on: Boolean)
}
