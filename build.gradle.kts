plugins {
    id("com.android.application") version "9.4.1" apply false
    id("com.android.library") version "9.4.1" apply false
    // org.jetbrains.kotlin.android is deliberately not declared here: AGP 9.0+
    // has built-in Kotlin support and refuses to let this plugin be applied
    // (see app/build.gradle.kts and platform/build.gradle.kts comments).
    id("org.jetbrains.kotlin.jvm") version "2.4.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
}
