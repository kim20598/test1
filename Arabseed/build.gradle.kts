// use an integer for version numbers
version = 4


cloudstream {
    language = "ar"
    // All of these properties are optional, you can safely remove them

    authors = listOf("kim20598")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1 // will be 3 if unspecified
    tvTypes = listOf(
        "Movie",
        "TvSerise",
        " Anime"
    )

    iconUrl = "https://t2.gstatic.com/faviconV2?client=SOCIAL&type=FAVICON&fallback_opts=TYPE,SIZE,URL&url=https://a.asd.homes&size=%size%"
    
    dependencies {
    val cloudstream by configurations

    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.browser:browser:1.9.0")
    implementation("androidx.room:room-ktx:2.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    isCrossPlatform = true
}
