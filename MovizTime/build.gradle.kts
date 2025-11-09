// use an integer for version numbers
version = 1

cloudstream {
    language = "ar"
    
    authors = listOf("your_name") // Change this
    
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
        "TvSeries",
        "Anime"
    )

    iconUrl = "https://t2.gstatic.com/faviconV2?client=SOCIAL&type=FAVICON&fallback_opts=TYPE,SIZE,URL&url=https://moviz-time.live&size=%size%"

    isCrossPlatform = true
}