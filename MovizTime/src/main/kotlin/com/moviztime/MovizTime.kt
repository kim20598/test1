package com.moviztime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor

class MovizTime : MainAPI() {
    override var mainUrl = "https://moviz-time.live"
    override var name = "Moviz Time"
    override val usesWebView = false
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie)

    override val mainPage = mainPageOf(
        "$mainUrl" to "Latest Movies",
        "$mainUrl/category/2025-movies/" to "2025 Movies",
        "$mainUrl/category/2024-movies/" to "2024 Movies"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page > 1) "${request.data}page/$page/" else request.data
        val document = app.get(url).document
        
        val home = document.select("article").mapNotNull { element ->
            val title = element.select("h2, h3").firstOrNull()?.text()?.trim() ?: return@mapNotNull null
            var href = element.select("a").firstOrNull()?.attr("href") ?: return@mapNotNull null
            
            var poster = element.select("img").firstOrNull()?.attr("src") ?: ""
            
            // Fix URLs
            if (href.isNotBlank() && !href.startsWith("http")) {
                href = if (href.startsWith("/")) "$mainUrl$href" else "$mainUrl/$href"
            }
            
            if (poster.isNotBlank() && !poster.startsWith("http")) {
                poster = if (poster.startsWith("/")) "$mainUrl$poster" else "$mainUrl/$poster"
            }
            
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
            }
        }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=$query"
        val document = app.get(searchUrl).document
        
        return document.select("article").mapNotNull { element ->
            val title = element.select("h2, h3").firstOrNull()?.text()?.trim() ?: return@mapNotNull null
            var href = element.select("a").firstOrNull()?.attr("href") ?: return@mapNotNull null
            
            var poster = element.select("img").firstOrNull()?.attr("src") ?: ""
            
            if (href.isNotBlank() && !href.startsWith("http")) {
                href = if (href.startsWith("/")) "$mainUrl$href" else "$mainUrl/$href"
            }
            
            if (poster.isNotBlank() && !poster.startsWith("http")) {
                poster = if (poster.startsWith("/")) "$mainUrl$poster" else "$mainUrl/$poster"
            }
            
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        
        val title = document.selectFirst("h1")?.text()?.trim() ?: "Unknown"
        val poster = document.selectFirst("img")?.attr("src") ?: ""
        val description = document.selectFirst(".entry-content")?.text()?.trim() ?: ""
        
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        var foundLinks = false
        
        // Find all iframes
        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
            if (src.isNotBlank()) {
                foundLinks = true
                loadExtractor(src, data, subtitleCallback, callback)
            }
        }
        
        return foundLinks
    }
}
