package com.grardb.thorcuts.platform.coordinator

/** An [EnabledSwitch] in memory, every write recorded. */
class FakeSwitch(var on: Boolean = false) : EnabledSwitch {

    val writes = mutableListOf<Boolean>()

    override fun read(): Boolean = on

    override fun write(on: Boolean) {
        this.on = on
        writes += on
    }
}
