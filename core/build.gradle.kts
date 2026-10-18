plugins {
    id("org.jetbrains.kotlin.jvm")
    // src/testFixtures: the config fixtures and the in-memory file, shared with :platform's
    // and :app's tests (`testImplementation(testFixtures(project(":core")))`).
    id("java-test-fixtures")
}

kotlin {
    // JDK 17 is not installed on this machine (only system JDK 19 and Android
    // Studio's bundled JBR, JDK 25, are present). Raise the toolchain to the
    // JDK that is actually available rather than removing it.
    //
    // The toolchain is the JDK that COMPILES. Pin the emitted bytecode target
    // separately, or jvmToolchain(N) also sets jvmTarget to N and :core drifts
    // away from :app / :platform, which compile to 17 via compileOptions.
    // Mismatched bytecode versions surface later as an opaque D8 failure.
    jvmToolchain(25)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// kotlin("jvm") applies the Java plugin under the hood, which creates
// compileJava / compileTestJava tasks even though :core has zero .java
// files. Left alone, those tasks default to the toolchain's JDK (25),
// which no longer matches the jvmTarget pinned above (17) and trips
// Kotlin's JVM-target consistency check before Kotlin compilation even
// starts. Pin them to the same target; they never compile anything, so
// this only satisfies the validator.
tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

dependencies {
    // The config codec walks a JSON tree; no compiler plugin, no @Serializable (config spec).
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    // ConfigStore is a coroutine actor on its own single thread (parent spec §8). `api`, not
    // `implementation`: StateFlow and CoroutineDispatcher are in its public signature, so a
    // consumer must compile against the same artifact, not whatever lifecycle drags in.
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")

    // The fixtures build a JSON tree for the kitchen-sink config's unknown members.
    testFixturesImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "failed", "skipped") }
}

// The golden rule-table fixtures live at the repo root so native/test/run.sh reads the
// very same bytes. On the test classpath they appear as /ruletable/<name>.bin.
sourceSets {
    named("test") {
        resources.srcDir("../fixtures")
    }
}
