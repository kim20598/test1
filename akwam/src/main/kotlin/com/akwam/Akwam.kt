package com.akwam

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class Akwam : MainAPI() {
    override var mainUrl = "https://ak.sv"
    override var name = "Akwam"
    override var lang = "ar"
    override val hasMainPage = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // minimal stub for it to load correctly
    override suspend fun search(query: String): List<SearchResponse> {
        return listOf(
            newMovieSearchResponse("Test Akwam", "$mainUrl/test", TvType.Movie) {
                this.posterUrl = "$mainUrl/logo.png"
            }
        )
    }
}
