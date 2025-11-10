package com.fajrshow

import com.lagradost.cloudstream3.*

class Fajrshow : MainAPI() {
    override var mainUrl = "https://fajer.show"
    override var name = "Fajrshow"
    override val usesWebView = true
    override val hasMainPage = false  // No main page - direct browsing only
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "ar"
    override val instantLinkLoading = true

    // 🚨 COMPLETELY BYPASS ALL AUTOMATION
    // Let user browse directly in WebView
    
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return newHomePageResponse("Browse Manually", emptyList())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // Direct search URL that opens in WebView
        return listOf(
            newMovieSearchResponse("Search: $query", "$mainUrl/?s=$query", TvType.Movie) {
                this.posterUrl = ""
            }
        )
    }

    override suspend fun load(url: String): LoadResponse {
        // Just pass the URL to WebView
        return newMovieLoadResponse("Protected Site", url, TvType.Movie, url)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Let WebView handle the video extraction
        return loadExtractor(data, data, subtitleCallback, callback)
    }
}