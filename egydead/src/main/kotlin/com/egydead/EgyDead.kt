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
        val title = link.selectFirst("h1.BottomTitle, h2, .title")?.text()?.trim() 
            ?: link.attr("title").trim()
            ?: link.ownText().trim()
        val poster = getPoster(this)
        
        if (title.isBlank() || href.isBlank()) return null

        // CLEAR TYPE DETECTION BASED ON URL PATTERNS
        val type = when {
            href.contains("/series-category/") || 
            href.contains("/serie/") || 
            href.contains("/season/") || 
            href.contains("/episode/") -> TvType.TvSeries
            href.contains("/category/") -> TvType.Movie
            else -> TvType.Movie // Default fallback
        }

        return newMovieSearchResponse(title, href, type) {
            this.posterUrl = poster
        }
    }

    // PROPER MAIN PAGE SECTIONS BASED ON ACTUAL WEBSITE STRUCTURE
    override val mainPage = mainPageOf(
        "$mainUrl/" to "أحدث المحتوى",
        "$mainUrl/category/%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d8%a7%d8%ac%d9%86%d8%a8%d9%8a-%d8%a7%d9%88%d9%86%d9%84%d8%a7%d9%8a%d9%86/" to "أفلام أجنبية",
        "$mainUrl/series-category/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%a7%d8%ac%d9%86%d8%a8%d9%8a-1/" to "مسلسلات أجنبية",
        "$mainUrl/series-category/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%a7%d9%86%d9%85%d9%8a/" to "مسلسلات أنمي",
        "$mainUrl/category/%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d8%a7%d9%86%d9%85%d9%8a/" to "أفلام أنمي"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) "${request.data}page/$page/" else request.data
        val document = app.get(url).document

        // TRY DIFFERENT SELECTORS FOR ITEMS
        val items = document.select("li.movieItem, article.post, .item, .post-item").mapNotNull { 
            it.toSearchResponse() 
        }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/?s=$encodedQuery"
        val document = app.get(url).document

        return document.select("li.movieItem, article.post, .item, .post-item").mapNotNull { 
            it.toSearchResponse() 
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        
        val title = document.selectFirst("h1.entry-title, h1.title, h1")?.text()?.trim() 
            ?: "Unknown"
        
        val poster = document.selectFirst("img[src*='wp-content'], .poster img, img.wp-post-image")?.attr("src")?.toAbsolute()
        val plot = document.selectFirst("div.entry-content, .plot, .description, .summary")?.text()?.trim()

        // CLEAR TYPE DETECTION BASED ON URL AND CONTENT
        val isMovie = when {
            url.contains("/category/") -> true
            url.contains("/series-category/") -> false
            url.contains("/serie/") -> false
            url.contains("/season/") -> false
            url.contains("/episode/") -> false
            document.select(".episode-list, .episodes, .season-list").isNotEmpty() -> false
            document.select("a[href*='/episode/'], a[href*='/season/']").isNotEmpty() -> false
            else -> true // Default to movie
        }

        // EPISODE EXTRACTION FOR SERIES
        val episodes = if (!isMovie) {
            document.select(".episode-list a, .episodes a, a[href*='/episode/']").mapNotNull { episodeLink ->
                val episodeUrl = episodeLink.attr("href").toAbsolute()
                val episodeTitle = episodeLink.ownText().trim().ifBlank { 
                    episodeLink.text().trim() 
                }
                val episodeNumber = extractEpisodeNumber(episodeTitle) ?: 1

                newEpisode(episodeUrl) {
                    this.name = if (episodeTitle.isNotBlank()) episodeTitle else "الحلقة $episodeNumber"
                    this.episode = episodeNumber
                    this.posterUrl = poster
                }
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

        // METHOD 1: Download servers list
        document.select("ul.donwload-servers-list li, .download-servers li, .servers-list li").forEach { serverItem ->
            val serverName = serverItem.selectFirst(".ser-name, .server-name")?.text()?.trim() ?: "Unknown"
            val quality = serverItem.selectFirst(".server-info em, .quality")?.text()?.trim() ?: "Unknown"
            val downloadLink = serverItem.selectFirst("a.ser-link, .download-link, a[href]")?.attr("href")?.toAbsolute()
            
            if (!downloadLink.isNullOrBlank()) {
                loadExtractor(downloadLink, data, subtitleCallback, callback)
            }
        }

        // METHOD 2: Embedded players
        document.select("iframe[src], .video-player iframe, .player iframe").forEach { iframe ->
            val iframeUrl = iframe.attr("src").toAbsolute()
            if (iframeUrl.isNotBlank() && !iframeUrl.contains("youtube", ignoreCase = true)) {
                loadExtractor(iframeUrl, data, subtitleCallback, callback)
            }
        }

        // METHOD 3: Direct video sources
        document.select("source[src], video source").forEach { source ->
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
