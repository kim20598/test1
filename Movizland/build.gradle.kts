// use an integer for version numbers
version = 1

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    // For parsing JSON responses from the site's API
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.0"
}

repositories {
    google()
    mavenCentral()
    maven { url = uri("https://jitpack.io") }
}

android {
    namespace = "com.movizland"
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
    }
}

dependencies {
    val cloudstream by configurations
    // Dependency for JSON parsing
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    cloudstream("com.lagradost:cloudstream3:pre-release")
}

cloudstream {
    // This provider targets the English version of the site
    language = "en"
    authors = listOf("kim20598")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     **/
    status = 1 // will be 3 if unspecified
    tvTypes = listOf(
        "Movie",
        "TvSeries"
    )

    iconUrl = "https://www.google.com/s2/favicons?sz=64&domain=movizlands.com"
    isCrossPlatform = true
}
