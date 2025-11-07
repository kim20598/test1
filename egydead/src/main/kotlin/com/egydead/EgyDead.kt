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

    private fun Element.toSearchResponse(requestName: String? = null): SearchResponse? {
        val link = this.selectFirst("a") ?: return null
        val href = link.attr("href").toAbsolute()
        val title = link.selectFirst("h1.BottomTitle")?.text()?.trim() 
            ?: link.attr("title").trim()
        val poster = getPoster(this)
        
        if (title.isBlank() || href.isBlank()) return null

        // BETTER TYPE DETECTION WITH SECTION AWARENESS
        val type = when {
            // Force type based on main page section
            requestName == "أحدث الحلقات" || requestName == "أحدث المواسم" -> TvType.TvSeries
            requestName == "أحدث الأفلام" -> TvType.Movie
            // URL-based detection
            href.contains("/episode/") || href.contains("/season/") -> TvType.TvSeries
            href.contains("/movie/") -> TvType.Movie
            // DOM-based detection
            link.parents().any { it.hasClass("episode") || it.selectFirst(".episode-count") != null } -> TvType.TvSeries
            link.parents().any { it.hasClass("movie") || it.selectFirst(".movie-meta") != null } -> TvType.Movie
            // Default fallback
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

        val items = document.select("li.movieItem").mapNotNull { element ->
            // PASS SECTION NAME TO HELP WITH TYPE DETECTION
            element.toSearchResponse(request.name)
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

        // IMPROVED TYPE DETECTION
        val isMovie = when {
            // Clear movie indicators
            url.contains("/movie/") -> true
            document.selectFirst("a[href*='/movie/']") != null -> true
            title.contains("فيلم") -> true
            document.selectFirst(".movie-meta, .film-meta") != null -> true
            
            // Clear series indicators
            url.contains("/episode/") || url.contains("/season/") -> false
            document.selectFirst(".episode-list, .episodes-list, .season-list") != null -> false
            document.select("a[href*='/episode/'], a[href*='/season/']").isNotEmpty() -> false
            
            // Default to movie if uncertain
            else -> true
        }

        // BETTER EPISODE PARSING
        val episodes = if (!isMovie) {
            document.select("div.episode-list a, .episodes-list a, a.episode-link, a[href*='/episode/']").mapNotNull { episodeLink ->
                val episodeUrl = episodeLink.attr("href").toAbsolute()
                val episodeTitle = episodeLink.ownText().trim().ifBlank { 
                    episodeLink.text().trim() 
                }
                val cleanTitle = episodeTitle.replace(Regex("""<[^>]*>"""), "").trim() // Remove HTML tags
                val episodeNumber = extractEpisodeNumber(cleanTitle) ?: 1

                newEpisode(episodeUrl) {
                    this.name = cleanTitle.ifBlank { "الحلقة $episodeNumber" }
                    this.episode = episodeNumber
                    this.posterUrl = poster
                }
            }.ifEmpty {
                // Fallback: if no episodes found but it's a series, create default episodes
                listOf(newEpisode(url) {
                    this.name = "الحلقة 1"
                    this.episode = 1
                    this.posterUrl = poster
                })
            }
        } else {
            emptyList()
        }

        return if (!isMovie) {
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        // METHOD 1: Extract from download servers list
        document.select("ul.donwload-servers-list li").forEach { serverItem ->
            val serverName = serverItem.selectFirst(".ser-name")?.text()?.trim() ?: "Unknown"
            val quality = serverItem.selectFirst(".server-info em")?.text()?.trim() ?: "Unknown"
            val downloadLink = serverItem.selectFirst("a.ser-link")?.attr("href")?.toAbsolute()
            
            if (!downloadLink.isNullOrBlank()) {
                // Send to CloudStream's extractor system
                loadExtractor(downloadLink, data, subtitleCallback, callback)
            }
        }

        // METHOD 2: Look for embedded iframes (fallback)
        document.select("iframe[src]").forEach { iframe ->
            val iframeUrl = iframe.attr("src").toAbsolute()
            if (iframeUrl.isNotBlank() && !iframeUrl.contains("youtube", ignoreCase = true)) {
                loadExtractor(iframeUrl, data, subtitleCallback, callback)
            }
        }

        // METHOD 3: Look for direct video sources
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

        return true
    }
}
