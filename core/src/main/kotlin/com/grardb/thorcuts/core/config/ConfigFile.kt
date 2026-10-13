package com.grardb.thorcuts.core.config

/**
 * What [ConfigStore] needs from the file system — and nothing else, so the store is a plain
 * JVM class. Implemented on Android by `AndroidConfigFile` (:platform) over the one
 * app-private file, and in tests by an in-memory fake.
 */
interface ConfigFile {
    /** The file's text, or null when there is no file. Throws on an I/O failure. */
    fun read(): String?

    /** Replace atomically: a reader sees the old text or the new, never a torn one. */
    fun write(text: String)

    /** Move an unreadable file out of the way without deleting it. Where it went, or null
     *  when there was nothing to move. Throws if the move failed. */
    fun setAside(): String?
}
