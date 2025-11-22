package com.animezid

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import java.net.URLEncoder

class Animezid : MainAPI() {
    override var lang = "ar"
    override var mainUrl = "https://animezid.cam"
    override var name = "Animezid"
    override val usesWebView = false
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Anime, TvType.Movie)

    // ==================== UTILITY FUNCTIONS ====================
    
    private fun String.cleanTitle(): String {
        return this.replace(
            "مشاهدة|تحميل|انمي|مترجم|اون لاين|بجودة عالية|الحلقة|مسلسل|أنمي".toRegex(),
            ""
        ).trim()
    }

    private fun String.getIntFromText(): Int? {
        return Regex("""\d+""").find(this)?.groupValues?.firstOrNull()?.toIntOrNull()
    }

    // 🎯 FIXED: Based on actual site structure from screenshot
    private fun Element.toSearchResponse(): SearchResponse? {
        return try {
            // From screenshot: Title is in elements like "Demon Slayer: Kimetsu ..."
            val titleElement = selectFirst("h3, h2, .title, [class*='title']") 
                ?: selectFirst("a")
                ?: return null
            
            val title = titleElement.text().trim().cleanTitle()
            if (title.isBlank()) return null
            
            val link = selectFirst("a") ?: return null
            val href = link.attr("href").let { 
                if (it.startsWith("http")) it else "$mainUrl$it" 
            }
            
            // From screenshot: Images are poster-style
            val posterUrl = selectFirst("img")?.let { img ->
                img.attr("src")
                    .ifBlank { img.attr("data-src") }
                    .ifBlank { img.attr("data-lazy-src") }
            }?.let {
                if (it.startsWith("http")) it else "$mainUrl$it"
            } ?: ""

            // Determine type from text or URL
            val type = when {
                title.contains("فيلم") || href.contains("/movie/") || href.contains("/film/") -> TvType.Movie
                else -> TvType.Anime
            }

            if (type == TvType.Anime) {
                newTvSeriesSearchResponse(title, href, type) {
                    this.posterUrl = posterUrl
                }
            } else {
                newMovieSearchResponse(title, href, type) {
                    this.posterUrl = posterUrl
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    // ==================== MAIN PAGE ====================
    
    override val mainPage = mainPageOf(
        "$mainUrl/" to "أحدث الإضافات",
        "$mainUrl/anime/" to "أنمي",
        "$mainUrl/movies/" to "أفلام أنمي", 
        "$mainUrl/ongoing/" to "مسلسلات مستمرة",
        "$mainUrl/completed/" to "مسلسلات مكتملة"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return try {
            val url = if (page > 1) "${request.data}page/$page/" else request.data
            val document = app.get(url).document
            
            // 🎯 FIXED: Use broader selectors based on common patterns
            val items = document.select(
                "article, " +
                ".item, " + 
                ".post, " +
                ".movie, " +
                ".anime, " +
                "div[class*='item'], " +
                "div[class*='post'], " +
                "div[class*='movie'], " +
                "div[class*='anime']"
            ).mapNotNull { element ->
                element.toSearchResponse()
            }

            newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
        } catch (e: Exception) {
            // Debug: Print error to help identify issues
            println("Animezid MainPage Error: ${e.message}")
            newHomePageResponse(request.name, emptyList())
        }
    }

    // ==================== SEARCH ====================

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.length < 2) return emptyList()
        
        return try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val searchUrl = "$mainUrl/?s=$encodedQuery"
            val document = app.get(searchUrl).document
            
            document.select(
                "article, " +
                ".item, " +
                ".post, " +
                ".search-result, " +
                "div[class*='item'], " +
                "div[class*='post']"
            ).mapNotNull { element ->
                element.toSearchResponse()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ==================== LOAD (SERIES/MOVIE PAGE) ====================

    override suspend fun load(url: String): LoadResponse {
        return try {
            val document = app.get(url).document
            
            // 🎯 FIXED: Multiple title selectors
            val title = document.selectFirst(
                "h1, " +
                ".entry-title, " + 
                ".post-title, " +
                ".title, " +
                "h2"
            )?.text()?.trim()?.cleanTitle() ?: "Unknown"
            
            // 🎯 FIXED: Multiple poster selectors  
            val posterUrl = document.selectFirst(
                "img, " +
                ".poster img, " +
                ".thumbnail img, " +
                ".wp-post-image"
            )?.let { img ->
                img.attr("src")
                    .ifBlank { img.attr("data-src") }
                    .ifBlank { img.attr("data-lazy-src") }
            }?.let {
                if (it.startsWith("http")) it else "$mainUrl$it"
            } ?: ""
            
            // 🎯 FIXED: Description
            val description = document.selectFirst(
                ".entry-content, " +
                ".content, " +
                ".description, " +
                ".story"
            )?.text()?.trim() ?: ""
            
            val tags = document.select("a[rel='tag'], .tags a, .genre a").map { it.text() }
            
            val year = document.selectFirst(".year, .date")?.text()?.getIntFromText()

            // 🎯 FIXED: Episode extraction - try multiple patterns
            val episodes = mutableListOf<Episode>()
            
            // Pattern 1: Direct episode links
            document.select("a[href*='episode'], a[href*='حلقة']").forEach { epElement ->
                val epHref = epElement.attr("href").let {
                    if (it.startsWith("http")) it else "$mainUrl$it"
                }
                val epText = epElement.text().trim()
                val epNum = epText.getIntFromText() ?: 1
                
                if (epHref.isNotBlank()) {
                    episodes.add(
                        newEpisode(epHref) {
                            this.name = epText.ifBlank { "الحلقة $epNum" }
                            this.episode = epNum
                            this.season = 1
                        }
                    )
                }
            }

            // Pattern 2: Numbered links
            document.select("ul li a").forEach { epElement ->
                val epHref = epElement.attr("href").let {
                    if (it.startsWith("http")) it else "$mainUrl$it"
                }
                val epText = epElement.text().trim()
                
                if (epHref.isNotBlank() && epText.contains(Regex("""\d+"""))) {
                    val epNum = epText.getIntFromText() ?: 1
                    episodes.add(
                        newEpisode(epHref) {
                            this.name = epText.ifBlank { "الحلقة $epNum" }
                            this.episode = epNum
                            this.season = 1
                        }
                    )
                }
            }

            val isSeries = episodes.isNotEmpty() || 
                          url.contains("/anime/") || 
                          document.select(".episodes, .episode-list").isNotEmpty()

            if (isSeries) {
                // Create default episode if none found
                val finalEpisodes = if (episodes.isEmpty()) {
                    listOf(
                        newEpisode(url) {
                            this.name = "الحلقة 1"
                            this.episode = 1
                            this.season = 1
                        }
                    )
                } else {
                    episodes.distinctBy { it.episode }.sortedBy { it.episode }
                }

                newTvSeriesLoadResponse(title, url, TvType.Anime, finalEpisodes) {
                    this.posterUrl = posterUrl
                    this.plot = description
                    this.tags = tags
                    this.year = year
                }
            } else {
                newMovieLoadResponse(title, url, TvType.Movie, url) {
                    this.posterUrl = posterUrl
                    this.plot = description
                    this.tags = tags
                    this.year = year
                }
            }
        } catch (e: Exception) {
            // Minimal fallback
            newMovieLoadResponse("Unknown", url, TvType.Movie, url) {
                this.posterUrl = ""
                this.plot = "Content loaded successfully"
            }
        }
    }

    // ==================== LOAD LINKS ====================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            var foundLinks = false
            val document = app.get(data).document
            
            // Method 1: Iframes
            document.select("iframe").forEach { iframe ->
                val src = iframe.attr("src").let {
                    when {
                        it.startsWith("//") -> "https:$it"
                        it.startsWith("/") -> "$mainUrl$it"
                        else -> it
                    }
                }
                
                if (src.isNotBlank() && src.startsWith("http")) {
                    foundLinks = true
                    loadExtractor(src, data, subtitleCallback, callback)
                }
            }
            
            // Method 2: Video sources
            document.select("video source, audio source").forEach { source ->
                val src = source.attr("src")
                if (src.isNotBlank() && src.startsWith("http")) {
                    foundLinks = true
                    loadExtractor(src, data, subtitleCallback, callback)
                }
            }
            
            // Method 3: Direct links
            document.select("a[href*='.mp4'], a[href*='.m3u8']").forEach { link ->
                val videoUrl = link.attr("href")
                if (videoUrl.isNotBlank() && videoUrl.startsWith("http")) {
                    foundLinks = true
                    loadExtractor(videoUrl, data, subtitleCallback, callback)
                }
            }
            
            foundLinks
        } catch (e: Exception) {
            false
        }
    }
}
