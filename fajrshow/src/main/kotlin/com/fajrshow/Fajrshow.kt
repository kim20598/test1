package com.fajrshow

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import java.net.URLEncoder

class Fajrshow : MainAPI() {
    override var lang = "ar"
    override var mainUrl = "https://fajer.show"
    override var name = "Fajrshow"
    override val usesWebView = true  // Cloudflare protection
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private fun String.getIntFromText(): Int? {
        return Regex("""\d+""").find(this)?.groupValues?.firstOrNull()?.toIntOrNull()
    }
    
    private fun String.cleanTitle(): String {
        return this.replace("مشاهدة وتحميل|اونلاين|مترجم".toRegex(), "").trim()
    }

    private val posterCache = mutableMapOf<String, String>()

    private fun Element.toMovieSearchResponse(): SearchResponse {
        val title = select("h3").text().cleanTitle()
        val posterUrl = select("img").attr("src")
        val href = select("a").attr("href")
        
        if (posterUrl.isNotBlank()) {
            posterCache[href] = posterUrl
        }
        
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    private fun Element.toTvSearchResponse(): SearchResponse {
        val title = select("h3").text().cleanTitle()
        val posterUrl = select("img").attr("src")
        val href = select("a").attr("href")
        
        if (posterUrl.isNotBlank()) {
            posterCache[href] = posterUrl
        }
        
        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
        }
    }

    // Updated main page based on actual structure
    override val mainPage = mainPageOf(
        "$mainUrl/movies/" to "آخر الأفلام",
        "$mainUrl/tvshows/" to "آخر المسلسلات", 
        "$mainUrl/episodes/" to "آخر الحلقات",
        "$mainUrl/genre/arabic-movies/" to "أفلام عربية",
        "$mainUrl/genre/english-movies/" to "أفلام أجنبية",
        "$mainUrl/genre/turkish-series/" to "مسلسلات تركية",
        "$mainUrl/genre/ramadan2025/" to "رمضان 2025"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) "${request.data}page/$page/" else request.data
        val document = app.get(url).document
        
        val home = document.select("article.item").mapNotNull {
            try {
                // Check if it's a movie or TV show
                val typeClass = it.attr("class")
                when {
                    typeClass.contains("movies") -> it.toMovieSearchResponse()
                    typeClass.contains("tvshows") -> it.toTvSearchResponse()
                    else -> it.toMovieSearchResponse() // Default to movie
                }
            } catch (e: Exception) {
                null
            }
        }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.length < 3) return emptyList()
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val doc = app.get("$mainUrl/?s=$encodedQuery").document
        
        return doc.select("article.item").mapNotNull {
            try {
                val typeClass = it.attr("class")
                when {
                    typeClass.contains("movies") -> it.toMovieSearchResponse()
                    typeClass.contains("tvshows") -> it.toTvSearchResponse()
                    else -> it.toMovieSearchResponse()
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        
        // Determine content type
        val isTvSeries = url.contains("/tvshows/") || doc.select("#seasons").isNotEmpty()
        
        val title = doc.selectFirst("h1.entry-title, h1")?.text()?.cleanTitle() ?: "Unknown Title"
        val posterUrl = posterCache[url] ?: doc.selectFirst(".poster img")?.attr("src") ?: ""
        val synopsis = doc.selectFirst(".wp-content, .entry-content")?.text() ?: ""
        
        val year = doc.selectFirst(".year, .date")?.text()?.getIntFromText()
        val rating = doc.selectFirst(".rating")?.text()?.toFloatOrNull()
        
        val tags = doc.select(".genre a, .genres a").map { it.text() }
        
        if (isTvSeries) {
            // TV Series loading
            val episodes = mutableListOf<Episode>()
            
            // Load seasons and episodes
            doc.select("#seasons .se-c, .season").forEach { seasonElement ->
                val seasonNum = seasonElement.select(".se-t, .season-title").text().getIntFromText() ?: 1
                
                seasonElement.select(".episodios li, .episode-item").forEach { episodeElement ->
                    val episodeNum = episodeElement.select(".episodiotitle, .episode-num").text().getIntFromText()
                    val episodeTitle = episodeElement.select("a").text().cleanTitle()
                    val episodeUrl = episodeElement.select("a").attr("href")
                    
                    if (episodeUrl.isNotBlank()) {
                        episodes.add(Episode(episodeUrl, episodeTitle, seasonNum, episodeNum))
                    }
                }
            }
            
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl
                this.plot = synopsis
                this.tags = tags
                this.year = year
                this.rating = rating
            }
        } else {
            // Movie loading
            val recommendations = doc.select(".related-posts article, .similar article").mapNotNull { element ->
                try {
                    element.toMovieSearchResponse()
                } catch (e: Exception) {
                    null
                }
            }
            
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = posterUrl
                this.recommendations = recommendations
                this.plot = synopsis
                this.tags = tags
                this.year = year
                this.rating = rating
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var foundLinks = false
        
        try {
            val doc = app.get(data).document
            
            // Look for video players and iframes
            doc.select("iframe, .dooplay_player iframe, .video-player iframe").forEach { iframe ->
                val src = iframe.attr("src")
                if (src.isNotBlank() && (src.contains("youtube") || src.contains("vimeo") || src.contains("dailymotion") || src.contains("m3u8") || src.contains("mp4"))) {
                    foundLinks = true
                    loadExtractor(src, data, subtitleCallback, callback)
                }
            }
            
            // Look for direct video links in scripts
            doc.select("script").forEach { script ->
                val scriptContent = script.html()
                // Extract m3u8 and mp4 links from scripts
                val videoPatterns = listOf(
                    """['"](https?://[^'"]*\.m3u8[^'"]*)['"]""",
                    """['"](https?://[^'"]*\.mp4[^'"]*)['"]""",
                    """file:\s*['"](https?://[^'"]*)['"]"""
                )
                
                videoPatterns.forEach { pattern ->
                    Regex(pattern).findAll(scriptContent).forEach { match ->
                        val url = match.groupValues[1]
                        if (url.isNotBlank()) {
                            foundLinks = true
                            loadExtractor(url, data, subtitleCallback, callback)
                        }
                    }
                }
            }
            
        } catch (e: Exception) {
            // Log error but don't crash
        }
        
        return foundLinks
    }
}