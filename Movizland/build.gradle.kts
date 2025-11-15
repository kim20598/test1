// use an integer for version numbers
version = 1

cloudstream {
    language = "en"
    
    authors = listOf("kim20598")
    
    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     */
    status = 1
    tvTypes = listOf(
        "Movie",
        "TvSeries",
        "Anime",
        "AsianDrama"
    )

    iconUrl = "https://t2.gstatic.com/faviconV2?client=SOCIAL&type=FAVICON&fallback_opts=TYPE,SIZE,URL&url=https://en.movizlands.com&size=%size%"

    isCrossPlatform = true
}
