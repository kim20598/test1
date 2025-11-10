package com.fushaar

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import java.net.URLEncoder

class Fushaar : MainAPI() {
    // 🔧 BASIC CONFIGURATION
    override var lang = "ar"
    override var mainUrl = "https://fushaar.com"
    override var name = "Fushaar"
    override val usesWebView = false
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    // 🎯 UTILITY FUNCTIONS
    private fun String.getIntFromText(): Int? {
        return Regex("""\d+""").find(this)?.groupValues?.firstOrNull()?.toIntOrNull()
    }
    
    private fun String.cleanTitle(): String {
        return this.replace("مشاهدة|فيلم|مسلسل|مترجم|كامل|اونلاين|برنامج".toRegex(), "").trim()
    }

    // 🖼️ SEARCH RESPONSE BUILDER
    private fun Element.toSearchResponse(): SearchResponse {
        val title = select(".post-title, h2, h3, .title").text().cleanTitle()
        val posterUrl = select("img").attr("src")
        val href = select("a").attr("href")
        
        // Determine content type based on URL structure
        val tvType = when {
            href.contains("/movie/", true) || 
            href.contains("/فيلم/", true) -> TvType.Movie
            href.contains("/series/", true) || 
            href.contains("/مسلسل/", true) || 
            href.contains("/مسلسلات/", true) -> TvType.TvSeries
            else -> TvType.Movie // Default fallback
        }
        
        return newMovieSearchResponse(title, href, tvType) {
            this.posterUrl = posterUrl
        }
    }

    // 🏠 MAIN PAGE SECTIONS
    override val mainPage = mainPageOf(
        "$mainUrl/movies/" to "أفلام",
        "$mainUrl/series/" to "مسلسلات", 
        "$mainUrl/trending/" to "الأكثر مشاهدة",
        "$mainUrl/latest/" to "أحدث الإضافات"
    )

    // 📄 MAIN PAGE LOADER
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) "${request.data}page/$page/" else request.data
        val document = app.get(url).document
        
        val home = document.select(".movie, .post, article, .item").mapNotNull {
            it.toSearchResponse()
        }
        return newHomePageResponse(request.name, home)
    }

    // 🔍 SEARCH FUNCTIONALITY
    override suspend fun search(query: String): List<SearchResponse> {
        if (query.length < 2) return emptyList()
        
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val doc = app.get("$mainUrl/?s=$encodedQuery").document
        
        return doc.select(".movie, .post, article, .item").mapNotNull {
            it.toSearchResponse()
        }
    }

    // 📺 LOAD DETAILED CONTENT
    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        
        val title = doc.select("h1, .entry-title, .title").text().cleanTitle()
        
        // Determine if movie or series
        val isMovie = !url.contains("/series/|/مسلسل/|/مسلسلات/".toRegex())

        val posterUrl = doc.select(".post-thumbnail img, .entry-content img, .wp-post-image").attr("src")
        
        val synopsis = doc.select(".entry-content, .post-content, .description").text()
        
        val year = doc.select(".year, .date").text().getIntFromText()
        val tags = doc.select(".tags, .categories a, .genre a").map { it.text() }
        
        val recommendations = doc.select(".related-posts .post, .similar .item").mapNotNull { element ->
            element.toSearchResponse()
        }
        
        val youtubeTrailer = doc.select("iframe[src*='youtube']").attr("src")
        
        return if (isMovie) {
            // 🎬 MOVIE LOAD RESPONSE
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = posterUrl
                this.recommendations = recommendations
                this.plot = synopsis
                this.tags = tags
                this.year = year
                addTrailer(youtubeTrailer)
            }
        } else {
            // 📺 TV SERIES LOAD RESPONSE
            val episodes = arrayListOf<Episode>()
            
            // Look for season lists
            val seasonList = doc.select(".seasons a, .season-list a, .tabs a").reversed()
            
            if(seasonList.isNotEmpty()) {
                // Multiple seasons
                seasonList.forEachIndexed { index, season ->
                    val seasonUrl = season.attr("href")
                    if (seasonUrl.isNotBlank()) {
                        try {
                            val seasonDoc = app.get(seasonUrl).document
                            // Extract episodes from season page
                            seasonDoc.select(".episodes a, .episode-list a, .episode-item a").forEach {
                                episodes.add(newEpisode(it.attr("href")) {
                                    name = it.attr("title").ifBlank { it.text().cleanTitle() }
                                    this.season = index + 1
                                    episode = it.text().getIntFromText() ?: 1
                                })
                            }
                        } catch (e: Exception) {
                            // If season page fails, try to extract from current page
                        }
                    }
                }
            }
            
            // If no seasons found or no episodes extracted, try direct episode extraction
            if (episodes.isEmpty()) {
                doc.select(".episodes a, .episode-list a, .episode-item a").forEach {
                    episodes.add(newEpisode(it.attr("href")) {
                        name = it.attr("title").ifBlank { it.text().cleanTitle() }
                        this.season = 1
                        episode = it.text().getIntFromText() ?: 1
                    })
                }
            }
            
            // If still no episodes, create a default episode
            if (episodes.isEmpty()) {
                episodes.add(newEpisode(url) {
                    name = "الحلقة 1"
                    this.season = 1
                    episode = 1
                })
            }
            
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.distinct().sortedBy { it.episode }) {
                this.posterUrl = posterUrl
                this.tags = tags
                this.plot = synopsis
                this.recommendations = recommendations
                this.year = year
                addTrailer(youtubeTrailer)
            }
        }
    }

    // 🔗 LOAD VIDEO LINKS
    override suspend fun loadLinks(
        data: String, 
        isCasting: Boolean, 
        subtitleCallback: (SubtitleFile) -> Unit, 
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var foundLinks = false
        
        try {
            val doc = app.get(data).document
            
            // Extract from download servers
            doc.select(".download-servers li, .servers-list li, .server-item").forEach { element ->
                val url = element.select("a").attr("href")
                if (url.isNotBlank() && url.contains("http")) {
                    foundLinks = true
                    loadExtractor(url, data, subtitleCallback, callback)
                }
            }
            
            // Extract from iframes/embeds
            doc.select("iframe, [data-src], .video-frame").forEach { element ->
                val iframeUrl = element.attr("src").ifBlank { element.attr("data-src") }
                if (iframeUrl.isNotBlank() && iframeUrl.contains("http")) {
                    foundLinks = true
                    loadExtractor(iframeUrl, data, subtitleCallback, callback)
                }
            }
            
            // Extract from video players
            doc.select("video source").forEach { source ->
                val videoUrl = source.attr("src")
                if (videoUrl.isNotBlank() && videoUrl.contains("http")) {
                    foundLinks = true
                    loadExtractor(videoUrl, data, subtitleCallback, callback)
                }
            }
            
            // Extract from script embeds
            doc.select("script").forEach { script ->
                val scriptContent = script.html()
                // Look for video URLs in scripts
                val videoPatterns = listOf(
                    """src:\s*["']([^"']+\.(mp4|m3u8))["']""",
                    """file:\s*["']([^"']+\.(mp4|m3u8))["']""",
                    """videoUrl:\s*["']([^"']+)["']"""
                )
                
                videoPatterns.forEach { pattern ->
                    Regex(pattern).findAll(scriptContent).forEach { match ->
                        val videoUrl = match.groupValues[1]
                        if (videoUrl.isNotBlank() && videoUrl.contains("http")) {
                            foundLinks = true
                            loadExtractor(videoUrl, data, subtitleCallback, callback)
                        }
                    }
                }
            }
            
        } catch (e: Exception) {
            // Fallback if request fails
        }
        
        return foundLinks
    }
}