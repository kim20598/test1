package com.fushaar

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import java.net.URLEncoder

class Fushaar : MainAPI() {
    override var lang = "ar"
    override var mainUrl = "https://fushaar.com"
    override var name = "Fushaar"
    override val usesWebView = false
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // 🎯 UTILITY FUNCTIONS
    private fun String.getIntFromText(): Int? {
        return Regex("""\d+""").find(this)?.groupValues?.firstOrNull()?.toIntOrNull()
    }
    
    private fun String.cleanTitle(): String {
        return this.replace("مشاهدة|فيلم|مسلسل|مترجم|كامل|اونلاين|برنامج".toRegex(), "").trim()
    }

    // 🖼️ SEARCH RESPONSE BUILDER - OPTIMIZED FOR FUSHAAR
    private fun Element.toSearchResponse(): SearchResponse {
        val title = select("h2, h3, .post-title").text().cleanTitle()
        val posterUrl = select("img").attr("src")
        val href = select("a").attr("href")
        
        // Determine content type based on URL structure
        val tvType = when {
            href.contains("/movie/", true) -> TvType.Movie
            else -> TvType.TvSeries // Default to series for other content
        }
        
        return newMovieSearchResponse(title, href, tvType) {
            this.posterUrl = posterUrl
        }
    }

    // 🏠 MAIN PAGE SECTIONS - OPTIMIZED FOR FUSHAAR
    override val mainPage = mainPageOf(
        "$mainUrl/" to "أحدث المحتوى",
        "$mainUrl/movies/" to "الأفلام",
        "$mainUrl/series/" to "المسلسلات",
        "$mainUrl/trending/" to "الأكثر مشاهدة"
    )

    // 📄 MAIN PAGE LOADER
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) "${request.data}page/$page/" else request.data
        val document = app.get(url).document
        
        val home = document.select("article").mapNotNull {
            it.toSearchResponse()
        }
        return newHomePageResponse(request.name, home)
    }

    // 🔍 SEARCH FUNCTIONALITY
    override suspend fun search(query: String): List<SearchResponse> {
        if (query.length < 2) return emptyList()
        
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val doc = app.get("$mainUrl/?s=$encodedQuery").document
        
        return doc.select("article").mapNotNull {
            it.toSearchResponse()
        }
    }

    // 📺 LOAD DETAILED CONTENT - OPTIMIZED FOR FUSHAAR
    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        
        val title = doc.selectFirst("h1, .entry-title")?.text()?.cleanTitle() ?: "Unknown Title"
        
        // Determine if movie or series
        val isMovie = url.contains("/movie/", true)

        val posterUrl = doc.selectFirst(".post-thumbnail img, .wp-post-image")?.attr("src") ?: ""
        
        val synopsis = doc.select(".entry-content, .post-content").text()
        
        val year = doc.select(".year, .date").text().getIntFromText()
        val tags = doc.select(".tags a, .categories a, .genre a").map { it.text() }
        
        val recommendations = doc.select(".related-posts article, .similar article").mapNotNull { element ->
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
            
            // Extract episodes from series pages
            doc.select(".episodes a, .episode-list a").forEach {
                val episodeUrl = it.attr("href")
                if (episodeUrl.isNotBlank()) {
                    episodes.add(newEpisode(episodeUrl) {
                        name = it.attr("title").ifBlank { it.text().cleanTitle() }
                        this.season = 1
                        episode = it.text().getIntFromText() ?: 1
                    })
                }
            }
            
            // If no episodes found, create a default episode
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

    // 🔗 LOAD VIDEO LINKS - OPTIMIZED FOR FUSHAAR MEDIA SOURCES
    override suspend fun loadLinks(
        data: String, 
        isCasting: Boolean, 
        subtitleCallback: (SubtitleFile) -> Unit, 
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var foundLinks = false
        
        try {
            val doc = app.get(data).document
            
            // 🎯 METHOD 1: Direct MP4 links from Fushaar CDN (HIGH PRIORITY)
            doc.select("a[href*='.mp4']").forEach { link ->
                val url = link.attr("href")
                val text = link.text().lowercase()
                
                if (url.isNotBlank() && url.contains("stream.fushaar.link")) {
                    foundLinks = true
                    
                    // Determine quality from link text
                    val quality = when {
                        "1080" in text || "fullhd" in text -> Qualities.FullHDP.value
                        "480" in text || "web" in text -> Qualities.480P.value
                        "240" in text || "sd" in text -> Qualities.240P.value
                        else -> Qualities.Unknown.value
                    }
                    
                    callback.invoke(
                        ExtractorLink(
                            name,
                            "Fushaar CDN - ${quality}p",
                            url,
                            mainUrl,
                            quality,
                            false
                        )
                    )
                }
            }
            
            // 🎯 METHOD 2: Embedded players (Voe.sx, Uqload, etc.)
            doc.select("iframe").forEach { iframe ->
                val src = iframe.attr("src")
                if (src.isNotBlank() && !src.contains("about:blank")) {
                    foundLinks = true
                    loadExtractor(src, data, subtitleCallback, callback)
                }
            }
            
            // 🎯 METHOD 3: Download servers
            doc.select("a[href*='fushaar.link/getvid'], a[href*='uptobox.com']").forEach { link ->
                val url = link.attr("href")
                if (url.isNotBlank()) {
                    foundLinks = true
                    loadExtractor(url, data, subtitleCallback, callback)
                }
            }
            
            // 🎯 METHOD 4: Player.php embeds
            doc.select("iframe[src*='player.php']").forEach { iframe ->
                val src = iframe.attr("src")
                if (src.isNotBlank()) {
                    foundLinks = true
                    // Extract movie ID from player URL for direct access
                    val movieId = Regex("""id=(\d+)""").find(src)?.groupValues?.getOrNull(1)
                    movieId?.let { id ->
                        // Create direct CDN links based on movie ID
                        val qualities = listOf(
                            "240p" to Qualities.240P.value,
                            "480p" to Qualities.480P.value,
                            "1080p" to Qualities.FullHDP.value
                        )
                        
                        qualities.forEach { (qualityName, qualityValue) ->
                            val cdnUrl = "https://stream.fushaar.link/movie/$id/$id${if (qualityName != "1080p") "-$qualityName" else ""}.mp4"
                            callback.invoke(
                                ExtractorLink(
                                    name,
                                    "Fushaar Direct - $qualityName",
                                    cdnUrl,
                                    mainUrl,
                                    qualityValue,
                                    false
                                )
                            )
                        }
                    }
                }
            }
            
        } catch (e: Exception) {
            // Log error but don't crash
            println("Fushaar loadLinks error: ${e.message}")
        }
        
        return foundLinks
    }
}