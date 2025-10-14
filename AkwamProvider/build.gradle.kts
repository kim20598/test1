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
}

dependencies {
    implementation("com.lagradost.cloudstream3:cloudstream3:1.0.0")
}
