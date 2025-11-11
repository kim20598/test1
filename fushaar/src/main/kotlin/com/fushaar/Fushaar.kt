package com.fushaarcom

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class Fushaar : MainAPI() {
    override var mainUrl = "https://fushaar.com"
    override var name = "Fushaar"
    override val usesWebView = false
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "ar"

    override val mainPage = mainPageOf(
        "https://fushaar.com/" to "Latest Content"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document
        val home = document.select("article").mapNotNull { element ->
            val title = element.select("h1, h2, h3, .title").firstOrNull()?.text()?.trim()
            val href = element.select("a").attr("href")
            val poster = element.select("img").attr("src")
            if (title != null && href != null) {
                MovieSearchResponse(title, href, name, TvType.Movie, poster)
            } else null
        }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.length < 3) return emptyList()
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("article").mapNotNull { element ->
            val title = element.select("h1, h2, h3, .title").firstOrNull()?.text()?.trim()
            val href = element.select("a").attr("href")
            val poster = element.select("img").attr("src")
            if (title != null && href != null) {
                MovieSearchResponse(title, href, name, TvType.Movie, poster)
            } else null
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1")?.text()?.trim() ?: "Unknown Title"
        val poster = document.selectFirst("img")?.attr("src") ?: ""
        val description = document.selectFirst("[class*='content'], [class*='description'], .plot")?.text()?.trim() ?: ""
        
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
        
        # Direct MP4 links
        document.select("a[href*='.mp4']").forEach { link ->
            val url = link.attr("href")
            if (url.isNotBlank()) {
                callback.invoke(
                    ExtractorLink(
                        name,
                        "$name - Direct",
                        url,
                        "$mainUrl/",
                        0,
                        false
                    )
                )
                foundLinks = true
            }
        }
        
        # Embedded players
        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotBlank()) {
                loadExtractor(src, data, subtitleCallback, callback)
                foundLinks = true
            }
        }
        
        # HLS streams
        document.select("a[href*='.m3u8']").forEach { link ->
            val url = link.attr("href")
            if (url.isNotBlank()) {
                callback.invoke(
                    ExtractorLink(
                        name,
                        "$name - HLS",
                        url,
                        "$mainUrl/",
                        0,
                        true
                    )
                )
                foundLinks = true
            }
        }
        
        return foundLinks
    }
}
