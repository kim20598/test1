plugins {
    id("com.android.library")
    kotlin("android")
    id("com.lagradost.cloudstream3.gradle")
}

version = 1

repositories {
    google()
    mavenCentral()
}

dependencies {
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}

cloudstream {
    description = "Akwam Arabic streaming provider"
    authors = listOf("Kimo")
    status = 1
    tvTypes = listOf("Movie", "TvSeries")
    requiresResources = false
    language = "ar"
    iconUrl = "https://ak.sv/assets/images/logo.png"
}

android {
    namespace = "com.akwam"
    compileSdk = 35

    defaultConfig {
        minSdk = 21
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

// ✅ This forces the Gradle build to use Java 17 for Kotlin
kotlin {
    jvmToolchain(17)
}
