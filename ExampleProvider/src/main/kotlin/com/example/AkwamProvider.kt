package com.example

import com.lagradost.cloudstream3.*

class AkwamProvider : MainAPI() {
    override var mainUrl = "https://ak.sv"
    override var name = "Akwam-tesr"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "ar"

    override val hasMainPage = true // Disable main page for now

    override suspend fun search(query: String): List<SearchResponse> {
        // Return empty for testing
        return emptyList()
    }
}
