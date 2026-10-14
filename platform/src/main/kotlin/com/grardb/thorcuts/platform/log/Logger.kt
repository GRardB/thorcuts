package com.grardb.thorcuts.platform.log

/**
 * Where the coordinator's lines go: `android.util.Log` on the device ([AndroidLogger]), a
 * list in a host test — so the tokens the device runs grep for (`CONFIG_LOAD`, `TABLE_ACK`,
 * `FIRED`, `FIRE_OUTCOME`, …) are pinned by a test rather than stubbed away, and the class
 * that emits them imports nothing from `android.*`.
 */
interface Logger {
    fun i(text: String)
    fun w(text: String)
    fun e(text: String, error: Throwable)
}
