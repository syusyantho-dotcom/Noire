plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.noir.reader"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.noir.reader"
        minSdk = 26
        targetSdk = 35
        // Always increases with each CI run so every new build can install over the previous one.
        versionCode = 1000 + (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0)
        versionName = "1.6.1"
    }
    // One fixed signing key for every build, so updates install over the old app without uninstalling.
    signingConfigs {
        create("noir") {
            storeFile = file("noir-release.jks")
            storePassword = "noir-sideload"
            keyAlias = "noir"
            keyPassword = "noir-sideload"
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("noir") }
        // Non-debuggable build: ART compiles it with baseline profiles, which is much smoother than a debug APK.
        getByName("release") {
            signingConfig = signingConfigs.getByName("noir")
            isDebuggable = false
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }
    lint { checkReleaseBuilds = false; abortOnError = false }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-opt-in=org.koitharu.kotatsu.parsers.DirectParsersAccess")
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("com.squareup.okhttp3:okhttp:5.3.2")
    implementation("com.github.clquwu:kotatsu-parsers-redo:3432a85ac0") { exclude(group = "org.json", module = "json") }
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    implementation("io.coil-kt:coil-compose:2.7.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
