package com.grardb.thorcuts.core.config

/** A golden fixture from fixtures/config/, as text. The JSON lives at the repo root (shared
 *  with native/test/run.sh) and is a test resource dir of :core only; the values those files
 *  mean are in src/testFixtures (`ConfigFixtures.kt`). */
internal fun configFixture(name: String): String {
    val loader = Thread.currentThread().contextClassLoader
    val stream = checkNotNull(loader.getResourceAsStream("config/$name")) {
        "fixture config/$name is missing from the test classpath; is ../fixtures a resource dir?"
    }
    return stream.use { it.readBytes().toString(Charsets.UTF_8) }
}
