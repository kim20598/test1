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

    // 🎯 FIXED: Search Response with correct selectors
    private fun Element.toSearchResponse(): SearchResponse? {
        return try {
            // Try multiple title selectors
            val title = selectFirst("h2, h3, .title, .entry-title")?.text()?.cleanTitle() 
                ?: selectFirst("a")?.attr("title")?.cleanTitle()
                ?: return null
            
            val link = selectFirst("a") ?: return null
            val href = link.attr("href").let { 
                if (it.startsWith("http")) it else "$mainUrl$it" 
            }
            
            // Try multiple image selectors
            val posterUrl = selectFirst("img")?.let { img ->
                img.attr("src")
                    .ifBlank { img.attr("data-src") }
                    .ifBlank { img.attr("data-lazy-src") }
            }?.let {
                if (it.startsWith("http")) it else "$mainUrl$it"
            } ?: ""

            // Determine type
            val type = when {
                href.contains("/movie/") || href.contains("/film/") -> TvType.Movie
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
            
            // 🎯 FIXED: Try multiple container selectors
            val items = document.select(
                "article, " +
                ".item, " +
                ".post, " +
                ".anime, " +
                ".movie, " +
                "div[class*='item'], " +
                "div[class*='post']"
            ).mapNotNull { element ->
                element.toSearchResponse()
            }

            newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
        } catch (e: Exception) {
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
                "div[class*='item']"
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
            )?.text()?.cleanTitle() ?: "Unknown"
            
            // 🎯 FIXED: Multiple poster selectors
            val posterUrl = document.selectFirst(
                "img, " +
                ".post-thumbnail img, " +
                ".wp-post-image, " +
                ".thumbnail img"
            )?.let { img ->
                img.attr("src")
                    .ifBlank { img.attr("data-src") }
                    .ifBlank { img.attr("data-lazy-src") }
            }?.let {
                if (it.startsWith("http")) it else "$mainUrl$it"
            } ?: ""
            
            // 🎯 FIXED: Multiple description selectors
            val description = document.selectFirst(
                ".entry-content, " +
                ".content, " +
                ".description, " +
                ".story, " +
                ".plot, " +
                "p"
            )?.text()?.trim() ?: ""
            
            val tags = document.select("a[rel='tag'], .tags a, .genre a").map { it.text() }
            
            val year = document.selectFirst(".year, .date")?.text()?.toIntOrNull()

            // 🎯 FIXED: Episode extraction
            val episodes = mutableListOf<Episode>()
            
            // Try multiple episode list patterns
            document.select(
                ".episodes a, " +
                ".episode-list a, " +
                ".episode-item a, " +
                "ul li a, " +
                ".list-episodes a"
            ).forEach { epElement ->
                val epHref = epElement.attr("href").let {
                    if (it.startsWith("http")) it else "$mainUrl$it"
                }
                val epText = epElement.text().trim()
                
                if (epHref.isNotBlank() && epText.contains(Regex("""حلقة|\d+"""))) {
                    val epNum = Regex("""\d+""").find(epText)?.value?.toIntOrNull() ?: 1
                    
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
                    episodes
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
            // Fallback with minimal info
            newMovieLoadResponse("Unknown", url, TvType.Movie, url) {
                this.posterUrl = ""
                this.plot = "Failed to load content details"
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
            
            // 🎯 FIXED: Multiple iframe patterns
            document.select("iframe").forEach { iframe ->
                val src = iframe.attr("src").let {
                    when {
                        it.startsWith("//") -> "https:$it"
                        it.startsWith("/") -> "$mainUrl$it"
                        else -> it
                    }
                }
                
                if (src.isNotBlank() && src.startsWith("http") && !src.contains("about:blank")) {
                    foundLinks = true
                    loadExtractor(src, data, subtitleCallback, callback)
                }
            }
            
            // 🎯 FIXED: Server buttons with data attributes
            document.select("[data-src], [data-embed], [data-server]").forEach { element ->
                val embedUrl = element.attr("data-src")
                    .ifBlank { element.attr("data-embed") }
                    .ifBlank { element.attr("data-server") }
                    .let {
                        when {
                            it.startsWith("//") -> "https:$it"
                            it.startsWith("/") -> "$mainUrl$it"
                            else -> it
                        }
                    }
                
                if (embedUrl.isNotBlank() && embedUrl.startsWith("http")) {
                    foundLinks = true
                    loadExtractor(embedUrl, data, subtitleCallback, callback)
                }
            }
            
            // 🎯 FIXED: Direct video links
            document.select("a[href*='.mp4'], a[href*='.m3u8'], source[src]").forEach { element ->
                val videoUrl = element.attr("href").ifBlank { element.attr("src") }
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
