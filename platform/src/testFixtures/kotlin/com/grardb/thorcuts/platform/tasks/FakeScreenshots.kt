package com.grardb.thorcuts.platform.tasks

import java.io.File

/**
 * A [Screenshots] for the host tests: a fixed frame path (what the bottom script names), a
 * canned `save` line, and a record of every `save` and `notify`.
 */
class FakeScreenshots(
    private val saveLine: String = "OK saved Pictures/Screenshots/Screenshot_20261019-182426_bottom.png",
) : Screenshots {

    override val frame: File = File("/data/user/0/com.grardb.thorcuts/cache/screenshot.png")

    /** How many times `save` ran. */
    var saves: Int = 0
        private set

    /** Every line handed to `notify`, in order. */
    val notified = mutableListOf<String>()

    override fun save(): String {
        saves++
        return saveLine
    }

    override fun notify(line: String) {
        notified += line
    }
}
