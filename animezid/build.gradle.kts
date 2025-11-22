plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.animezid"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

cloudstream {
    authors = listOf("YourName")
    language = "ar"
    tvTypes = listOf("Anime", "Movie")
    iconUrl = "https://www.google.com/s2/favicons?domain=animezid.cam&sz=%size%"
}

dependencies {
    implementation("com.lagradost:cloudstream3-core:latest.release")
}
