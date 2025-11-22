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
            "مشاهدة|تحميل|انمي|مترجم|اون لاين|بجودة عالية|الحلقة|مسلسل".toRegex(),
            ""
        ).trim()
    }

    private fun String.getIntFromText(): Int? {
        return try {
            Regex("""\d+""").find(this)?.groupValues?.firstOrNull()?.toIntOrNull()
        } catch (e: Exception) {
            null
        }
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        return try {
            val titleElement = selectFirst("h3, h2, .title") ?: return null
            val title = titleElement.text().cleanTitle()
            
            val linkElement = selectFirst("a") ?: return null
            val href = linkElement.attr("href").let {
                if (it.startsWith("http")) it else "$mainUrl$it"
            }
            
            val posterUrl = selectFirst("img")?.let { img ->
                img.attr("src").ifBlank { img.attr("data-src") }
            }?.let {
                if (it.startsWith("http")) it else "$mainUrl$it"
            } ?: ""

            // Determine type based on URL
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
        "$mainUrl/anime/" to "أنمي",
        "$mainUrl/cartoon/" to "كرتون", 
        "$mainUrl/ongoing/" to "مستمر",
        "$mainUrl/completed/" to "مكتمل",
        "$mainUrl/movies/" to "أفلام أنمي",
        "$mainUrl/category/japanese-anime/" to "أنمي ياباني",
        "$mainUrl/category/chinese-anime/" to "أنمي صيني",
        "$mainUrl/category/korean-anime/" to "أنمي كوري"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return try {
            val url = if (page > 1) "${request.data}page/$page/" else request.data
            val document = app.get(url).document
            
            val items = document.select("article, .anime-item, .post-item").mapNotNull { element ->
                element.toSearchResponse()
            }

            newHomePageResponse(request.name, items)
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
            
            document.select("article, .anime-item, .post-item").mapNotNull { element ->
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
            
            val title = document.selectFirst("h1, .entry-title, .post-title")?.text()?.cleanTitle() ?: "Unknown"
            
            val posterUrl = document.selectFirst("img")?.let { img ->
                img.attr("src").ifBlank { img.attr("data-src") }
            }?.let {
                if (it.startsWith("http")) it else "$mainUrl$it"
            } ?: ""
            
            val description = document.selectFirst(".entry-content, .story, p")?.text()?.trim() ?: ""
            
            val tags = document.select("a[rel=tag], .tags a").map { it.text() }
            
            val year = document.selectFirst(".year, .date")?.text()?.getIntFromText()

            // Extract episodes for series
            val episodes = mutableListOf<Episode>()
            
            // Look for episode lists
            document.select(".episode-list a, .episodes a, ul li a").forEach { epElement ->
                val epHref = epElement.attr("href").let {
                    if (it.startsWith("http")) it else "$mainUrl$it"
                }
                val epTitle = epElement.text().trim()
                val epNum = epTitle.getIntFromText() ?: 1
                
                if (epHref.isNotBlank()) {
                    episodes.add(
                        newEpisode(epHref) {
                            this.name = epTitle.ifBlank { "الحلقة $epNum" }
                            this.episode = epNum
                            this.season = 1
                        }
                    )
                }
            }

            val isSeries = episodes.isNotEmpty() || url.contains("/anime/") || url.contains("/cartoon/")

            if (isSeries) {
                // If no episodes found but it's a series, create default episode
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
            // Fallback response
            newMovieLoadResponse("Error Loading", url, TvType.Movie, url) {
                this.posterUrl = ""
                this.plot = "Failed to load content"
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
            
            // Method 1: Direct iframes
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
            
            // Method 2: Server links
            document.select(".server-list a, [data-server]").forEach { serverElement ->
                val embedUrl = serverElement.attr("data-server")
                    .ifBlank { serverElement.attr("href") }
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
            
            // Method 3: Direct video links
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
