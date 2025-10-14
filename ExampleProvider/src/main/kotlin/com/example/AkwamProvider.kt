package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink

class AkwamProvider : MainAPI() {
    override var mainUrl = "https://ak.sv"
    override var name = "Akwam"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "ar"

    override val hasMainPage = true

    // Main page - return empty for now
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return HomePageResponse(listOf())
    }

    // Search function - return empty for now
    override suspend fun search(query: String): List<SearchResponse> {
        return emptyList()
    }

    // Load details - using the correct API method
    override suspend fun load(url: String): LoadResponse {
        return newMovieLoadResponse(
            "Akwam Content",
            url,
            TvType.Movie,
            url
        ) {
            posterUrl = ""
            plot = "Content from Akwam"
            year = 2023
        }
    }

    // Load video links - fixed signature
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return true
    }
}
