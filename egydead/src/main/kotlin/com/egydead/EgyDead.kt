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

        // Better type detection
        val type = when {
            href.contains("/episode/") || href.contains("/season/") || 
            link.selectFirst(".episode-count, .season-count") != null -> TvType.TvSeries
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

        // BETTER TYPE DETECTION - Check for movie indicators
        val isMovie = document.selectFirst("a[href*='/movie/'], .movie-meta, .film-meta") != null ||
                     title.contains("فيلم") || // "film" in Arabic
                     url.contains("/movie/") ||
                     document.select("div.episode-list, .episodes-list").isEmpty()

        // BETTER EPISODE PARSING - Only for actual series
        val episodes = if (!isMovie) {
            document.select("div.episode-list a, .episodes-list a, a.episode-link").mapNotNull { episodeLink ->
                val episodeUrl = episodeLink.attr("href").toAbsolute()
                val episodeTitle = episodeLink.ownText().trim().ifBlank { 
                    episodeLink.text().trim() 
                }
                // Clean episode title and extract number
                val cleanTitle = episodeTitle.replace(Regex("""[\s\.]+"""), " ").trim()
                val episodeNumber = extractEpisodeNumber(cleanTitle) ?: 1

                newEpisode(episodeUrl) {
                    this.name = cleanTitle.ifBlank { "الحلقة $episodeNumber" }
                    this.episode = episodeNumber
                    this.posterUrl = poster
                }
            }
        } else {
            emptyList()
        }

        return if (!isMovie && episodes.isNotEmpty()) {
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

    private fun extractEpisodeNumber(text: String): Int? {
        // Match patterns like: "الحلقة 1", "Episode 1", "1", "EP 1", etc.
        val patterns = listOf(
            Regex("""الحلقة\s*(\d+)"""),
            Regex("""حلقة\s*(\d+)"""),
            Regex("""Episode\s*(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""EP\s*(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""\b(\d+)\b""")
        )
        
        patterns.forEach { pattern ->
            pattern.find(text)?.groupValues?.get(1)?.toIntOrNull()?.let {
                return it
            }
        }
        return null
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
            Regex("""(https?:[^"'\s]*\.(?:mp4|m3u8)[^"'\s]*)""").findAll(scriptContent).forEach { match ->
                val videoUrl = match.groupValues[1].toAbsolute()
                loadExtractor(videoUrl, data, subtitleCallback, callback)
            }
        }

        return true
    }
}
