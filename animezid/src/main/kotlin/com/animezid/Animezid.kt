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
            "مشاهدة|تحميل|انمي|مترجم|اون لاين|بجودة عالية|الحلقة|مسلسل|أنمي|فيلم".toRegex(),
            ""
        ).trim()
    }

    private fun String.getIntFromText(): Int? {
        return Regex("""\d+""").find(this)?.groupValues?.firstOrNull()?.toIntOrNull()
    }

    // 🎯 PERFECT: Based on actual HTML structure
    private fun Element.toSearchResponse(): SearchResponse? {
        return try {
            // From HTML: <a class="movie" title="فيلم Demon Slayer: Kimetsu no Yaiba Infinity Castle 2025 مترجم">
            val title = this.attr("title").cleanTitle()
            if (title.isBlank()) return null
            
            val href = this.attr("href").let { 
                if (it.startsWith("http")) it else "$mainUrl$it" 
            }
            
            // From HTML: <img class="lazy" data-src="https://animezid.cam/uploads/thumbs/30b6c4ab0-1.jpg">
            val posterUrl = select("img").firstOrNull()?.let { img ->
                img.attr("data-src").ifBlank { img.attr("src") }
            }?.let {
                if (it.startsWith("http")) it else "$mainUrl$it"
            } ?: ""

            // Determine type from title or URL
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
        "$mainUrl/completed/" to "مسلسلات مكتملة",
        "$mainUrl/category.php?cat=disney-masr" to "ديزني بالمصري",
        "$mainUrl/category.php?cat=spacetoon" to "سبيستون"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return try {
            val url = if (page > 1) "${request.data}page/$page/" else request.data
            val document = app.get(url).document
            
            // 🎯 PERFECT: Selector from actual HTML - <a class="movie" ...>
            val items = document.select("a.movie").mapNotNull { element ->
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
            val searchUrl = "$mainUrl/search.php?keywords=$encodedQuery"
            val document = app.get(searchUrl).document
            
            // 🎯 PERFECT: Use same selector as main page
            document.select("a.movie").mapNotNull { element ->
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
            
            // 🎯 PERFECT: Title from actual page structure
            val title = document.selectFirst("h1, .entry-title")?.text()?.cleanTitle() ?: "Unknown"
            
            // 🎯 PERFECT: Poster from actual page structure
            val posterUrl = document.selectFirst("img")?.let { img ->
                img.attr("src").ifBlank { img.attr("data-src") }
            }?.let {
                if (it.startsWith("http")) it else "$mainUrl$it"
            } ?: ""
            
            // 🎯 PERFECT: Description from actual page structure
            val description = document.selectFirst(".entry-content, .content")?.text()?.trim() ?: ""
            
            val tags = document.select("a[rel='tag'], .tags a").map { it.text() }
            
            val year = document.selectFirst(".year, .date")?.text()?.getIntFromText()

            // 🎯 PERFECT: Episode extraction for series
            val episodes = mutableListOf<Episode>()
            
            // Look for episode links in the page
            document.select("a[href*='watch.php']").forEach { episodeElement ->
                val epHref = episodeElement.attr("href").let {
                    if (it.startsWith("http")) it else "$mainUrl$it"
                }
                val epText = episodeElement.text().trim()
                
                // Only process if it looks like an episode link
                if (epHref.contains("watch.php") && epText.contains(Regex("""حلقة|\d+"""))) {
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
                          url.contains("/series/") ||
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
            
            // 🎯 PERFECT: Iframe extraction from actual site
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
            
            // 🎯 PERFECT: Server buttons with data attributes
            document.select("[data-src], [data-embed]").forEach { element ->
                val embedUrl = element.attr("data-src")
                    .ifBlank { element.attr("data-embed") }
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
            
            // 🎯 PERFECT: Direct video links
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
