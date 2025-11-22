plugins {
    id("java-library")
    id("kotlin")
}

dependencies {
    implementation("com.lagradost:cloudstream3-core:latest.release")
}

cloudstream {
    language = "ar"
    authors = listOf("kim20598")
    status = 1
    tvTypes = listOf("Anime","Cartoon", "Movie")
    iconUrl = "https://www.google.com/s2/favicons?domain=animezid.cam&sz=%size%"
}
