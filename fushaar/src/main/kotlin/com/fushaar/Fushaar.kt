package com.fushaar

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import java.net.URLEncoder

class Fushaar : MainAPI() {
    override var lang = "ar"
    override var mainUrl = "https://fushaar.com"
    override var name = "Fushaar"
    override val usesWebView = false
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie)

    private fun String.getIntFromText(): Int? {
        return Regex("""\d+""").find(this)?.groupValues?.firstOrNull()?.toIntOrNull()
    }
    
    private fun String.cleanTitle(): String {
        return this.replace("مشاهدة وتحميل فلم|مشاهدة وتحميل|اونلاين|مترجم".toRegex(), "").trim()
    }

    // Store poster URLs when we find them in search/main page 
    private val posterCache = mutableMapOf<String, String>()

    private fun Element.toSearchResponse(): SearchResponse {
        val title = select("h3, h2, .entry-title").text().cleanTitle()
        
        // Get the poster from data-lazy-src or src
        val posterUrl = select("img").attr("data-lazy-src").ifBlank { 
            select("img").attr("src") 
        }
        val href = select("a").attr("href")
        
        // Store the poster URL for later use in load()
        if (posterUrl.isNotBlank()) {
            posterCache[href] = posterUrl
        }
        
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    // Fushaar categories based on analysis
    override val mainPage = mainPageOf(
        "$mainUrl/" to "Latest Movies | أحدث الأفلام",
        "$mainUrl/gerne/action/" to "Action | أكشن",
        "$mainUrl/gerne/adventure/" to "Adventure | مغامرة", 
        "$mainUrl/gerne/animation/" to "Animation | أنيمايشن",
        "$mainUrl/gerne/biography/" to "Biography | سيرة",
        "$mainUrl/gerne/comedy/" to "Comedy | كوميديا",
        "$mainUrl/gerne/crime/" to "Crime | جريمة",
        "$mainUrl/gerne/documentary/" to "Documentary | وثائقي",
        "$mainUrl/gerne/drama/" to "Drama | دراما",
        "$mainUrl/gerne/family/" to "Family | عائلي",
        "$mainUrl/gerne/fantasy/" to "Fantasy | فنتازيا",
        "$mainUrl/gerne/horror/" to "Horror | رعب",
        "$mainUrl/gerne/history/" to "History | تاريخي",
        "$mainUrl/gerne/music/" to "Music | موسيقى",
        "$mainUrl/gerne/mystery/" to "Mystery | غموض",
        "$mainUrl/gerne/romance/" to "Romance | رومنسي",
        "$mainUrl/gerne/sci-fi/" to "Sci-fi | خيال علمي",
        "$mainUrl/gerne/short/" to "Short | قصير",
        "$mainUrl/gerne/sport/" to "Sport | رياضة",
        "$mainUrl/gerne/thriller/" to "Thriller | إثارة",
        "$mainUrl/gerne/war/" to "War | حرب",
        "$mainUrl/gerne/western/" to "Western | غربي"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) "${request.data}page/$page/" else request.data
        val document = app.get(url).document
        
        val home = document.select("article.poster, article").mapNotNull {
            it.toSearchResponse()
        }
        return newHomePageResponse(request.name, home, hasNext = true)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.length < 3) return emptyList()
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val doc = app.get("$mainUrl/?s=$encodedQuery").document
        
        return doc.select("article.poster, article").mapNotNull {
            it.toSearchResponse()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        
        val title = doc.selectFirst("h1.entry-title, h1")?.text()?.cleanTitle() ?: "Unknown Title"

        // Use cached poster or try to extract from movie page
        val posterUrl = posterCache[url] ?: doc.selectFirst(".poster img, .movie-poster img")?.attr("src") ?: ""

        val synopsis = doc.selectFirst(".entry-content, .post-content, .plot")?.text()?.trim() ?: ""
        val year = doc.selectFirst(".year, .labels .year, time")?.text()?.getIntFromText()
        
        val tags = doc.select(".gerne a, .genre a, .labels a").map { it.text() }
        
        val recommendations = doc.select(".related-posts article, .simple-related-posts article, .movie-grid article").mapNotNull { element ->
            element.toSearchResponse()
        }
        
        val youtubeTrailer = doc.selectFirst("iframe[src*='youtube'], iframe[src*='youtu.be']")?.attr("src") ?: ""

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = posterUrl
            this.recommendations = recommendations
            this.plot = synopsis
            this.tags = tags
            this.year = year
            if (youtubeTrailer.isNotBlank()) {
                addTrailer(youtubeTrailer)
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
            
            // Extract from direct MP4 links found in analysis
            doc.select("a[href*='.mp4'], a[href*='.m3u8']").forEach { element ->
                val url = element.attr("href")
                if (url.isNotBlank() && (url.contains(".mp4") || url.contains(".m3u8"))) {
                    foundLinks = true
                    callback.invoke(
                        ExtractorLink(
                            name,
                            "Direct",
                            url,
                            mainUrl,
                            Qualities.Unknown.value,
                            url.contains(".m3u8")
                        )
                    )
                }
            }
            
            // Extract from iframe embeds (found 7 iframes in analysis)
            doc.select("iframe").forEach { iframe ->
                val src = iframe.attr("src")
                if (src.isNotBlank() && !src.startsWith("about:")) {
                    foundLinks = true
                    loadExtractor(src, data, subtitleCallback, callback)
                }
            }
            
            // Try quality-specific links based on analysis
            val qualityPatterns = mapOf(
                "240p" to "240",
                "480p" to "480", 
                "1080p" to "1080",
                "FullHD" to "1080"
            )
            
            doc.select("a").forEach { element ->
                val text = element.text()
                val href = element.attr("href")
                qualityPatterns.forEach { (pattern, quality) ->
                    if (text.contains(pattern, true) && href.isNotBlank()) {
                        foundLinks = true
                        callback.invoke(
                            ExtractorLink(
                                name,
                                "Direct $quality",
                                href,
                                mainUrl,
                                quality.toIntOrNull() ?: Qualities.Unknown.value,
                                false
                            )
                        )
                    }
                }
            }
            
            // Try uptobox links found in analysis
            doc.select("a[href*='uptobox']").forEach { element ->
                val url = element.attr("href")
                if (url.isNotBlank()) {
                    foundLinks = true
                    loadExtractor(url, data, subtitleCallback, callback)
                }
            }
            
            // Try stream.fushaar.link domains found in analysis
            doc.select("a[href*='stream.fushaar']").forEach { element ->
                val url = element.attr("href")
                if (url.isNotBlank()) {
                    foundLinks = true
                    callback.invoke(
                        ExtractorLink(
                            name,
                            "Fushaar Stream",
                            url,
                            mainUrl,
                            Qualities.Unknown.value,
                            false
                        )
                    )
                }
            }

        } catch (e: Exception) {
            // Fallback if everything fails
        }
        
        return foundLinks
    }
}
