package com.moviztime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class MovizTime : MainAPI() {
    override var mainUrl = "https://moviz-time.live"
    override var name = "Moviz Time"
    override val usesWebView = false
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie)

    override val mainPage = mainPageOf(
        "$mainUrl" to "Latest Movies"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(request.data).document
        val home = document.select("article").mapNotNull { element ->
            val title = element.select("h2, h3").text()
            val href = element.select("a").attr("href")
            val poster = element.select("img").attr("src")
            
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
            }
        }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("article").mapNotNull { element ->
            val title = element.select("h2, h3").text()
            val href = element.select("a").attr("href")
            val poster = element.select("img").attr("src")
            
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1")?.text() ?: "Unknown"
        val poster = document.selectFirst("img")?.attr("src") ?: ""
        val description = document.selectFirst(".content, .entry-content")?.text() ?: ""
        
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
        
        // Extract iframes
        document.select("iframe").forEach { iframe ->
            val url = iframe.attr("src")
            if (url.isNotBlank()) {
                loadExtractor(url, data, subtitleCallback, callback)
                return true
            }
        }
        
        // Extract video links
        document.select("a[href*='.mp4'], a[href*='.m3u8']").forEach { link ->
            val url = link.attr("href")
            if (url.isNotBlank()) {
                loadExtractor(url, data, subtitleCallback, callback)
                return true
            }
        }
        
        return false
    }
}
