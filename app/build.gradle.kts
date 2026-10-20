// org.jetbrains.kotlin.android is not applied here: AGP 9.0+ bundles Kotlin
// support directly, and applying the standalone plugin fails the build with
// "The 'org.jetbrains.kotlin.android' plugin is no longer required for
// Kotlin support since AGP 9.0." (https://kotl.in/gradle/agp-built-in-kotlin).
// jvmTarget is likewise set via compileOptions below, not `kotlinOptions {}`,
// which no longer exists without that plugin.
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing: a `keystore.properties` at the repo root names the keystore and its
// passwords (keystore.properties.example lists the keys). Without the file the release build
// is simply unsigned, and the debug build is untouched either way. The file and the keystore
// are gitignored: a leaked key lets anyone ship an update over every install.
val keystoreProperties: Properties? = rootProject.file("keystore.properties")
    .takeIf { it.exists() }
    ?.let { f -> Properties().apply { f.inputStream().use(::load) } }

android {
    namespace = "com.grardb.thorcuts"
    compileSdk = 37
    compileSdkMinor = 2

    defaultConfig {
        applicationId = "com.grardb.thorcuts"
        minSdk = 30
        targetSdk = 37
        versionCode = 3
        versionName = "0.3.0"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        if (keystoreProperties != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed when keystore.properties is present (above); unsigned otherwise.
            signingConfig = signingConfigs.findByName("release")
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

    // Host tests for the app's pure logic (FocusedWindow, EditorDraft) and for the ViewModel
    // over :platform's fakes: a plain JVM, JUnit 5 as in :core and :platform. Nothing under
    // test calls into android.* (an android.net.Uri is only ever a parameter type).
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

    // The ViewModel test builds the real coordinator from :platform's fakes and :core's
    // fixtures, with Main swapped for a test dispatcher (kotlinx-coroutines-test).
    testImplementation(testFixtures(project(":core")))
    testImplementation(testFixtures(project(":platform")))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5:2.4.20")
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
