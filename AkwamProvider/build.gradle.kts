plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "com.akwam"
    compileSdk = 35

    defaultConfig {
        minSdk = 21
    }

    buildFeatures {
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

repositories {
    google()
    mavenCentral()
    // ✅ Required for Cloudstream plugin SDK
    maven("https://jitpack.io")
}

dependencies {
    // ✅ Core Cloudstream plugin SDK (latest snapshot)
    implementation("com.github.recloudstream:cloudstream:master-SNAPSHOT")
}
