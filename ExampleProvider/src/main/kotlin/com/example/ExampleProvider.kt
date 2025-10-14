package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink

class ExampleProvider : MainAPI() {
    override var mainUrl = "https://example.com/"
    override var name = "Example Provider"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "en"
    override val hasMainPage = true

    // Main page with featured content
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = listOf(
            HomePageList(
                "Featured Movies", 
                listOf(
                    MovieSearchResponse(
                        "Example Movie 1",
                        "$mainUrl/movie1",
                        this.name,
                        TvType.Movie,
                        "$mainUrl/poster1.jpg",
                        null,
                        null
                    ),
                    MovieSearchResponse(
                        "Example Movie 2", 
                        "$mainUrl/movie2",
                        this.name,
                        TvType.Movie,
                        "$mainUrl/poster2.jpg",
                        null,
                        null
                    )
                )
            ),
            HomePageList(
                "Popular Series", 
                listOf(
                    TvSeriesSearchResponse(
                        "Example Series",
                        "$mainUrl/series1", 
                        this.name,
                        TvType.TvSeries,
                        "$mainUrl/series-poster.jpg",
                        null,
                        null
                    )
                )
            )
        )
        return HomePageResponse(items)
    }

    // Search function
    override suspend fun search(query: String): List<SearchResponse> {
        // Mock search results for demonstration
        return if (query.contains("example", ignoreCase = true)) {
            listOf(
                MovieSearchResponse(
                    "Example Movie Result",
                    "$mainUrl/search-result",
                    this.name,
                    TvType.Movie,
                    "$mainUrl/poster.jpg",
                    null,
                    null
                )
            )
        } else {
            emptyList()
        }
    }

    // Load details for a movie/series
    override suspend fun load(url: String): LoadResponse {
        return MovieLoadResponse(
            "Example Movie Title",
            url,
            this.name,
            TvType.Movie,
            "$mainUrl/poster.jpg",
            null,
            "This is a plot description for the example movie.",
            null,
            null,
            listOf("Action", "Adventure"),
            "2023",
            null,
            listOf(
                Episode(
                    "$mainUrl/video1",
                    "Episode 1",
                    1,
                    1
                )
            )
        )
    }

    // Load video links
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Example for direct video link:
        callback.invoke(
            ExtractorLink(
                this.name,
                this.name,
                "https://example.com/video.mp4",
                mainUrl,
                getQuality("720p"),
                false
            )
        )
        
        return true
    }
}
