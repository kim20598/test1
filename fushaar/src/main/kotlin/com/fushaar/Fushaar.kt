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
        val title = select("h3").text().cleanTitle()
        
        // Get the poster from data-lazy-src (the good one from main page)
        val posterUrl = select("img").attr("data-lazy-src")
        val href = select("a").attr("href")
        
        // Store the poster URL for later use in load()
        if (posterUrl.isNotBlank()) {
            posterCache[href] = posterUrl
        }
        
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    // Fushaar categories
    override val mainPage = mainPageOf(
        "$mainUrl" to "الأفلام والمسلسلات",
        "$mainUrl/gerne/action/" to "أفلام الأكشن",
        "$mainUrl/gerne/comedy/" to "أفلام الكوميديا", 
        "$mainUrl/gerne/drama/" to "أفلام الدراما",
        "$mainUrl/gerne/horror/" to "أفلام الرعب",
        "$mainUrl/gerne/thriller/" to "أفلام الإثارة"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) "${request.data}page/$page/" else request.data
        val document = app.get(url).document
        
        val home = document.select("article.poster, article").mapNotNull {
            it.toSearchResponse()
        }
        return newHomePageResponse(request.name, home)
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

        // FIXED: Use the same poster from main page instead of trying to extract from movie page
        val posterUrl = posterCache[url] ?: ""
        
        val synopsis = doc.selectFirst(".entry-content, .post-content")?.text() ?: ""
        val year = doc.selectFirst(".year, .labels .year")?.text()?.getIntFromText()
        
        val tags = doc.select(".gerne a, .genre a").map { it.text() }
        
        val recommendations = doc.select(".related-posts article, .simple-related-posts article").mapNotNull { element ->
            element.toSearchResponse()
        }
        
        val youtubeTrailer = doc.selectFirst("iframe[src*='youtube'], iframe[src*='youtu.be']")?.attr("src") ?: ""
        
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = posterUrl
            this.recommendations = recommendations
            this.plot = synopsis
            this.tags = tags
            this.year = year
            addTrailer(youtubeTrailer)
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
            
            // Try direct video links first
            doc.select("a[href*='.mp4'], a[href*='.m3u8']").forEach { element ->
                val url = element.attr("href")
                if (url.isNotBlank()) {
                    foundLinks = true
                    loadExtractor(url, data, subtitleCallback, callback)
                }
            }
            
            // Try iframe embeds
            doc.select("iframe").forEach { iframe ->
                val src = iframe.attr("src")
                if (src.isNotBlank()) {
                    foundLinks = true
                    loadExtractor(src, data, subtitleCallback, callback)
                }
            }
            
            // If no links found, try POST request
            if (!foundLinks) {
                try {
                    val postDoc = app.post(data, data = mapOf("view" to "1")).document
                    
                    postDoc.select("a[href*='.mp4'], a[href*='.m3u8']").forEach { element ->
                        val url = element.attr("href")
                        if (url.isNotBlank()) {
                            foundLinks = true
                            loadExtractor(url, data, subtitleCallback, callback)
                        }
                    }
                } catch (e: Exception) {
                    // POST failed, continue
                }
            }
            
        } catch (e: Exception) {
            // Fallback if everything fails
        }
        
        return foundLinks
    }
}
