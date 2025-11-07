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

    private fun Element.toSearchResponse(): SearchResponse? {
        val link = this.selectFirst("a") ?: return null
        val href = link.attr("href").toAbsolute()
        
        // EXACT SELECTOR FROM HTML: <h1> inside .sliderBoxInfo
        val title = this.selectFirst("h1")?.text()?.trim() 
            ?: link.attr("title").trim()
            ?: "Unknown"
            
        // EXACT SELECTOR FROM HTML: <img> directly inside item
        val poster = this.selectFirst("img")?.attr("src")?.toAbsolute()
        
        if (title.isBlank() || href.isBlank()) return null

        // CLEAR TYPE DETECTION BASED ON TITLE AND URL
        val type = when {
            title.contains("مسلسل") || href.contains("/series-category/") -> TvType.TvSeries
            title.contains("فيلم") || href.contains("/category/") -> TvType.Movie
            else -> TvType.Movie // Default fallback
        }

        return newMovieSearchResponse(title, href, type) {
            this.posterUrl = poster
        }
    }

    // PROPER MAIN PAGE SECTIONS
    override val mainPage = mainPageOf(
        "$mainUrl/" to "أحدث المحتوى",
        "$mainUrl/category/%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d8%a7%d8%ac%d9%86%d8%a8%d9%8a-%d8%a7%d9%88%d9%86%d9%84%d8%a7%d9%8a%d9%86/" to "أفلام أجنبية",
        "$mainUrl/series-category/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%a7%d8%ac%d9%86%d8%a8%d9%8a-1/" to "مسلسلات أجنبية",
        "$mainUrl/series-category/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%a7%d9%86%d9%85%d9%8a/" to "مسلسلات أنمي"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) "${request.data}page/$page/" else request.data
        val document = app.get(url).document

        // EXACT SELECTOR FROM HTML: <div class="item">
        val items = document.select("div.item").mapNotNull { 
            it.toSearchResponse() 
        }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/?s=$encodedQuery"
        val document = app.get(url).document

        // EXACT SELECTOR FROM HTML: <div class="item">
        return document.select("div.item").mapNotNull { 
            it.toSearchResponse() 
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        
        // EXACT SELECTOR FROM HTML: <h1 class="entry-title"> or <h1>
        val title = document.selectFirst("h1.entry-title, h1")?.text()?.trim() 
            ?: "Unknown"
        
        // EXACT SELECTOR FROM HTML: <img> with wp-content
        val poster = document.selectFirst("img[src*='wp-content']")?.attr("src")?.toAbsolute()
        
        // EXACT SELECTOR FROM HTML: <div class="entry-content">
        val plot = document.selectFirst("div.entry-content")?.text()?.trim()

        // CLEAR TYPE DETECTION
        val isMovie = when {
            url.contains("/category/") || title.contains("فيلم") -> true
            url.contains("/series-category/") || title.contains("مسلسل") -> false
            document.select(".episode-list, .episodes").isNotEmpty() -> false
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
            }.ifEmpty {
                // If no episodes found but it's a series, create default episode
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

        // METHOD 1: Download servers list (EXACT SELECTOR)
        document.select("ul.donwload-servers-list li").forEach { serverItem ->
            val serverName = serverItem.selectFirst(".ser-name")?.text()?.trim() ?: "Unknown"
            val quality = serverItem.selectFirst(".server-info em")?.text()?.trim() ?: "Unknown"
            val downloadLink = serverItem.selectFirst("a.ser-link")?.attr("href")?.toAbsolute()
            
            if (!downloadLink.isNullOrBlank()) {
                loadExtractor(downloadLink, data, subtitleCallback, callback)
            }
        }

        // METHOD 2: Embedded players
        document.select("iframe[src]").forEach { iframe ->
            val iframeUrl = iframe.attr("src").toAbsolute()
            if (iframeUrl.isNotBlank() && !iframeUrl.contains("youtube", ignoreCase = true)) {
                loadExtractor(iframeUrl, data, subtitleCallback, callback)
            }
        }

        // METHOD 3: Direct video sources
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
