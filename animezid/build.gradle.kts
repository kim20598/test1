plugins {
    id("java-library")
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
