package com.egydead

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

class EgyDead : MainAPI() {
    override var mainUrl = "https://egydead.skin"
    override var name = "EgyDead"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(
        TvType.Movie, TvType.TvSeries, TvType.Anime
    )

    private fun String.toAbsolute(): String {
        return when {
            this.startsWith("http") -> this
            this.startsWith("//") -> "https:$this"
            else -> "$mainUrl${if (this.startsWith("/")) "" else "/"}$this"
        }
    }

    private fun getPoster(element: Element?): String? {
        return element?.selectFirst("img")?.let {
            it.attr("data-src").ifBlank { it.attr("src") }
        }?.toAbsolute()
    }

    // Let me analyze the site structure first to implement the scraping logic
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // TODO: Implement after analyzing site structure
        throw ErrorLoadingException("Not implemented yet")
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // TODO: Implement after analyzing site structure
        return emptyList()
    }

    override suspend fun load(url: String): LoadResponse {
        // TODO: Implement after analyzing site structure
        throw ErrorLoadingException("Not implemented yet")
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // TODO: Implement after analyzing site structure
        return false
    }
}