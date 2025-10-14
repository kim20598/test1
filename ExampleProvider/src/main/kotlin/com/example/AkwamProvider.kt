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

    // Load details
    override suspend fun load(url: String): LoadResponse {
        return MovieLoadResponse(
            "Akwam Content",
            url,
            this.name,
            TvType.Movie,
            "",
            null,
            "Content from Akwam",
            null
        )
    }

    // Load video links
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return true
    }
}
