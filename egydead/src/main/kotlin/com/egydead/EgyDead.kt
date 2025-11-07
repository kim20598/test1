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
        TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AsianDrama
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

    private fun Element.toSearchResponse(): SearchResponse? {
        val link = this.selectFirst("a") ?: return null
        val href = link.attr("href").toAbsolute()
        val title = link.selectFirst("h1.BottomTitle")?.text()?.trim() 
            ?: link.attr("title").trim()
        val poster = getPoster(this)
        
        if (title.isBlank() || href.isBlank()) return null

        // Determine type based on URL and content
        val type = when {
            href.contains("/episode/") -> TvType.TvSeries
            href.contains("/season/") -> TvType.TvSeries
            else -> TvType.Movie
        }

        return newMovieSearchResponse(title, href, type) {
            this.posterUrl = poster
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/" to "المضاف حديثا",
        "$mainUrl/page/movies/" to "أحدث الأفلام", 
        "$mainUrl/episode/" to "أحدث الحلقات",
        "$mainUrl/season/" to "أحدث المواسم"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) "${request.data}?page=$page" else request.data
        val document = app.get(url).document

        val items = document.select("li.movieItem").mapNotNull { 
            it.toSearchResponse() 
        }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/?s=$encodedQuery"
        val document = app.get(url).document

        return document.select("li.movieItem").mapNotNull { 
            it.toSearchResponse() 
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        
        val title = document.selectFirst("h1.entry-title")?.text()?.trim() 
            ?: document.selectFirst("h1.BottomTitle")?.text()?.trim() 
            ?: "Unknown"
        
        val poster = document.selectFirst("img[src*='wp-content']")?.attr("src")?.toAbsolute()
        val plot = document.selectFirst("div.entry-content p")?.text()?.trim()

        // Check if it's a series by looking for episodes
        val episodes = document.select("div.episode-list a, a[href*='/episode/']").mapNotNull { episodeLink ->
            val episodeUrl = episodeLink.attr("href").toAbsolute()
            val episodeTitle = episodeLink.text().trim()
            val episodeNumber = episodeTitle.getIntFromText()

            newEpisode(episodeUrl) {
                name = episodeTitle
                episode = episodeNumber
                posterUrl = poster
            }
        }

        val isSeries = episodes.isNotEmpty() || url.contains("/episode/") || url.contains("/season/")

        return if (isSeries) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
            }
        }
    }

    private fun String.getIntFromText(): Int? {
        return Regex("""\d+""").find(this)?.groupValues?.firstOrNull()?.toIntOrNull()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        // Method 1: Look for embedded iframes
        document.select("iframe[src]").forEach { iframe ->
            val iframeUrl = iframe.attr("src").toAbsolute()
            if (iframeUrl.isNotBlank()) {
                loadExtractor(iframeUrl, data, subtitleCallback, callback)
            }
        }

        // Method 2: Look for video sources
        document.select("source[src]").forEach { source ->
            val videoUrl = source.attr("src").toAbsolute()
            if (videoUrl.isNotBlank() && (videoUrl.contains(".mp4") || videoUrl.contains(".m3u8"))) {
                val qualityAttr = source.attr("size").ifBlank { source.attr("label") }
                
                callback(
                    newExtractorLink(
                        source = this.name,
                        name = "${this.name} ${qualityAttr.ifBlank { "Direct" }}",
                        url = videoUrl
                    ) {
                        this.referer = data
                        this.quality = getQualityFromName(qualityAttr)
                        this.type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    }
                )
            }
        }

        // Method 3: Look for video links in scripts
        document.select("script").forEach { script ->
            val scriptContent = script.html()
            // Look for MP4 and M3U8 links in scripts
            Regex("""(https?:[^"'\s]*\.(?:mp4|m3u8)[^"'\s]*)""").findAll(scriptContent).forEach { match ->
                val videoUrl = match.groupValues[1].toAbsolute()
                loadExtractor(videoUrl, data, subtitleCallback, callback)
            }
        }

        return true
    }
}
