// org.jetbrains.kotlin.android is not applied here: AGP 9.0+ bundles Kotlin
// support directly, and applying the standalone plugin fails the build with
// "The 'org.jetbrains.kotlin.android' plugin is no longer required for
// Kotlin support since AGP 9.0." (https://kotl.in/gradle/agp-built-in-kotlin).
// jvmTarget is likewise set via compileOptions below, not `kotlinOptions {}`,
// which no longer exists without that plugin.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.grardb.thorcuts"
    compileSdk = 37
    compileSdkMinor = 2

    defaultConfig {
        applicationId = "com.grardb.thorcuts"
        minSdk = 30
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0-M1"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // The helper ships as jniLibs/arm64-v8a/libthorcutsd.so. Legacy packaging
    // sets extractNativeLibs=true, so Android unpacks it into the app's
    // nativeLibraryDir, which permits exec. Without this the file stays mapped
    // inside the APK and cannot be run.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Host tests for the app's pure logic (FocusedWindow, EditorDraft): a plain JVM, JUnit 5
    // as in :core and :platform. The classes under test import nothing from android.*.
    testOptions {
        unitTests.all { it.useJUnitPlatform() }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":platform"))

    implementation("androidx.core:core-ktx:1.19.1")
    // Dispatchers.Main.immediate for ThorcutsApp.writes (the editor's final review, E7-3):
    // declared here, not left to arrive transitively through :core / :platform.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")

    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5:2.4.20")
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
