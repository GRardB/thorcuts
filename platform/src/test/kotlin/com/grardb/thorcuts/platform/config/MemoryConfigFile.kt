package com.grardb.thorcuts.platform.config

import com.grardb.thorcuts.core.config.ConfigFile

/** A [ConfigFile] in memory: one slot of text. The coordinator needs a file, not a file
 *  system; :core's fake is internal to that module's tests. */
class MemoryConfigFile(var text: String? = null) : ConfigFile {

    val writes = mutableListOf<String>()

    override fun read(): String? = text

    override fun write(text: String) {
        this.text = text
        writes += text
    }

    override fun setAside(): String? {
        if (text == null) return null
        text = null
        return "config.json.bad-1"
    }
}
