package com.grardb.thorcuts.core.rules

/** A golden fixture from fixtures/ruletable/, as bytes. */
internal fun fixture(name: String): ByteArray {
    val loader = Thread.currentThread().contextClassLoader
    val stream = checkNotNull(loader.getResourceAsStream("ruletable/$name")) {
        "fixture ruletable/$name is missing from the test classpath; is ../fixtures a resource dir?"
    }
    return stream.use { it.readBytes() }
}
