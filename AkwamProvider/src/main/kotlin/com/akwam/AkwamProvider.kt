package com.akwam

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.akwam.utils.*
import org.jsoup.Jsoup

class AkwamProvider : MainAPI() {
    override var mainUrl = "https://ak.sv"
    override var name = "Akwam"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "ar"
    override val hasMainPage = true

    override suspend fun search(query: String): List<SearchResponse> {
        return AkwamParser.searchMovies(query)
    }

    override suspend fun load(url: String): LoadResponse? {
        return AkwamParser.loadMovie(url)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (loadSubtitleFile) -> Unit,
        callback: (loadExtractorLinks) -> Unit
    ): Boolean {
        return AkwamExtractor.extractLinks(data, callback)
    }
}
