// src/Arabseed/build.gradle.kts

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization") // ✅ Added to support @Serializable
}

repositories {
    mavenCentral()
    maven { url = uri("https://jitpack.io") }
}

android {
    namespace = "com.arabseed"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
        freeCompilerArgs = listOf("-XXLanguage:+BreakContinueInInlineLambdas")
    }
}

dependencies {
    // Cloudstream dependency configuration
    val cloudstream by configurations

    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.browser:browser:1.9.0")
    implementation("androidx.room:room-ktx:2.8.0")

    // ✅ Added Kotlin serialization dependency
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    cloudstream("com.lagradost:cloudstream3:pre-release")
}
