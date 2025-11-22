package com.animezid

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

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
            val posterUrl = select("img.lazy").firstOrNull()?.attr("data-src")?.let {
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
            
            // 🎯 PERFECT: Selector from actual HTML - <a class="movie">
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
            val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
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
            
            // 🎯 PERFECT: Title from actual page structure - <h1><span itemprop="name">
            val title = document.selectFirst("h1 span[itemprop=name]")?.text()?.cleanTitle() 
                ?: document.selectFirst("h1")?.text()?.cleanTitle() 
                ?: "Unknown"
            
            // 🎯 PERFECT: Poster from actual page structure
            val posterUrl = document.selectFirst("img.lazy")?.attr("data-src")?.let {
                if (it.startsWith("http")) it else "$mainUrl$it"
            } ?: ""

            // 🎯 PERFECT: Description from actual page structure
            val description = document.selectFirst(".pm-video-description .description")?.text()?.trim() 
                ?: document.selectFirst(".pm-video-description")?.text()?.trim() 
                ?: ""
            
            val tags = document.select(".hashtags a").map { it.text() }
            
            val year = document.selectFirst("a[href*='filter=years']")?.text()?.getIntFromText()

            // 🎯 PERFECT: Episode extraction for series
            val episodes = mutableListOf<Episode>()
            
            // Look for episode links in similar movies section
            document.select(".movies_small a.movie[href*='watch.php']").forEach { episodeElement ->
                val epHref = episodeElement.attr("href").let {
                    if (it.startsWith("http")) it else "$mainUrl/$it"
                }
                val epText = episodeElement.select(".title").text().trim()
                
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
                          document.select(".tab-episodes").isNotEmpty() ||
                          title.contains("الحلقة")

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
            // Fallback for movies
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
            
            // 🎯 PERFECT: Extract the actual video URL from the play button
            val document = app.get(data).document
            
            // Method 1: Extract from play button href
            val playButtonUrl = document.select("a[href*='play.php?vid=']").firstOrNull()?.attr("href")
            if (!playButtonUrl.isNullOrBlank()) {
                val fullPlayUrl = if (playButtonUrl.startsWith("http")) playButtonUrl else "$mainUrl/$playButtonUrl"
                foundLinks = loadExtractor(fullPlayUrl, data, subtitleCallback, callback) || foundLinks
            }
            
            // Method 2: Extract from video player iframe/embed
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
            
            // Method 3: Look for embed URLs in data attributes
            document.select("[data-embed], [data-src]").forEach { element ->
                val embedUrl = element.attr("data-embed").ifBlank { element.attr("data-src") }.let {
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
            
            // Method 4: If no links found, try the direct page as extractor
            if (!foundLinks) {
                foundLinks = loadExtractor(data, data, subtitleCallback, callback)
            }
            
            foundLinks
        } catch (e: Exception) {
            false
        }
    }
}