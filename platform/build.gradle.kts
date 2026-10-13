// org.jetbrains.kotlin.android is not applied here: AGP 9.0+ bundles Kotlin
// support directly, and applying the standalone plugin fails the build with
// "The 'org.jetbrains.kotlin.android' plugin is no longer required for
// Kotlin support since AGP 9.0." (https://kotl.in/gradle/agp-built-in-kotlin).
// jvmTarget is likewise set via compileOptions below, not `kotlinOptions {}`,
// which no longer exists without that plugin.
plugins {
    id("com.android.library")
}

android {
    namespace = "com.grardb.thorcuts.platform"
    compileSdk = 37
    compileSdkMinor = 2

    defaultConfig {
        minSdk = 30
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Host tests for the app side of the actions (TaskActions, ActionDispatcher): a plain
    // JVM, JUnit 5 as in :core. The classes under test import nothing from android.*.
    testOptions {
        unitTests.all { it.useJUnitPlatform() }
    }
}

dependencies {
    implementation(project(":core"))
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5:2.4.20")
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
