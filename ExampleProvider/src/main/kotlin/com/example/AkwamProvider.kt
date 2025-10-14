package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.Jsoup

class AkwamProvider : MainAPI() {
    override var mainUrl = "https://ak.sv"
    override var name = "Akwam"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "ar"

    override val hasMainPage = true

    // Main page - let's add some test content first
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = mutableListOf<HomePageList>()
        
        // TEST: Add some dummy content to see if it shows up
        val testItems = listOf(
            newMovieSearchResponse("Test Movie 1", "$mainUrl/test1", TvType.Movie) {
                posterUrl = "https://via.placeholder.com/300x450/008000/FFFFFF?text=Movie+1"
            },
            newMovieSearchResponse("Test Movie 2", "$mainUrl/test2", TvType.Movie) {
                posterUrl = "https://via.placeholder.com/300x450/000080/FFFFFF?text=Movie+2"
            },
            newTvSeriesSearchResponse("Test Series 1", "$mainUrl/series1", TvType.TvSeries) {
                posterUrl = "https://via.placeholder.com/300x450/800080/FFFFFF?text=Series+1"
            }
        )
        
        items.add(HomePageList("Test Content", testItems))
        
        return HomePageResponse(items)
    }

    // Search function - let's test with some dummy results first
    override suspend fun search(query: String): List<SearchResponse> {
        // Return test results for any search
        return listOf(
            newMovieSearchResponse("Search Result: $query", "$mainUrl/search", TvType.Movie) {
                posterUrl = "https://via.placeholder.com/300x450/FF0000/FFFFFF?text=Search"
            }
        )
    }

    // Load details
    override suspend fun load(url: String): LoadResponse {
        return newMovieLoadResponse(
            "Test Content Details",
            url,
            TvType.Movie,
            url
        ) {
            posterUrl = "https://via.placeholder.com/300x450/000000/FFFFFF?text=Details"
            plot = "This is a test content from Akwam provider. If you can see this, the provider is working!"
            year = 2024
            tags = listOf("Action", "Test")
        }
    }

    // Load video links
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // For testing, return true but no actual video
        return true
    }
}
