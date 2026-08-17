import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Personal, non-secret targets (goal date, phase label, tally start) live in /config.properties
// at the repo root and are baked into BuildConfig at build time. If the file is missing, the
// neutral defaults below are used so a fresh clone still builds.
val personalConfig = Properties().apply {
    val f = rootProject.file("config.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.example.macrowidget"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.macrowidget"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "GOAL_DATE",       "\"${personalConfig.getProperty("GOAL_DATE", "2026-12-31")}\"")
        buildConfigField("String", "GOAL_LABEL",      "\"${personalConfig.getProperty("GOAL_LABEL", "Dec 31")}\"")
        buildConfigField("String", "CHALLENGE_START", "\"${personalConfig.getProperty("CHALLENGE_START", "2026-01-01")}\"")
        buildConfigField("String", "PHASE_LABEL",     "\"${personalConfig.getProperty("PHASE_LABEL", "Cutting Phase")}\"")
        // Strength-session budget shown on the Energy page. GYM_TOTAL = 0 hides the block entirely,
        // which is the neutral default so a fresh clone renders the page exactly as before.
        buildConfigField("String", "GYM_START",       "\"${personalConfig.getProperty("GYM_START", "2026-01-01")}\"")
        buildConfigField("int",    "GYM_TOTAL",       personalConfig.getProperty("GYM_TOTAL", "0"))
    }
    buildFeatures { buildConfig = true }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions { jvmTarget = "1.8" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
