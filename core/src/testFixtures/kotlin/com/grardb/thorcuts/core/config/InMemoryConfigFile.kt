package com.grardb.thorcuts.core.config

import java.io.IOException

/** A [ConfigFile] in memory: one slot of text, the writes kept, set-asides counted and their
 *  text kept, and a failure to inject on either side. The store's tests and the coordinator's
 *  (a file, not a file system) share it. */
open class InMemoryConfigFile(var text: String? = null) : ConfigFile {
    val writes = mutableListOf<String>()
    val setAsides = mutableListOf<String>()
    var readFailure: IOException? = null
    var writeFailure: IOException? = null

    override fun read(): String? {
        readFailure?.let { throw it }
        return text
    }

    override fun write(text: String) {
        writeFailure?.let { throw it }
        this.text = text
        writes += text
    }

    override fun setAside(): String? {
        val old = text ?: return null
        text = null
        setAsides += old
        return "config.json.bad-${setAsides.size}"
    }
}
