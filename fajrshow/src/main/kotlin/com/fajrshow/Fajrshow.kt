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
    override var name = "Fajrshow1"
    override val usesWebView = false
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie)

    private fun String.getIntFromText(): Int? {
        return Regex("""\d+""").find(this)?.groupValues?.firstOrNull()?.toIntOrNull()
    }
    
    private fun String.cleanTitle(): String {
        return this.replace("مشاهدة وتحميل فلم|مشاهدة وتحميل|اونلاين|مترجم".toRegex(), "").trim()
    }

    private val posterCache = mutableMapOf<String, String>()

    private fun Element.toSearchResponse(): SearchResponse {
        val title = select("h3").text().cleanTitle()
        val posterUrl = select("img").attr("data-lazy-src")
        val href = select("a").attr("href")
        
        if (posterUrl.isNotBlank()) {
            posterCache[href] = posterUrl
        }
        
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    // TODO: Update main page categories based on fajer.show structure
    override val mainPage = mainPageOf(
        "$mainUrl/page/" to "Movies | أفلام",
        "$mainUrl/category/action/" to "Action | أكشن",
        "$mainUrl/category/comedy/" to "Comedy | كوميديا",
        "$mainUrl/category/drama/" to "Drama | دراما",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) "${request.data}page/$page/" else request.data
        val document = app.get(url).document
        
        val home = document.select("article.poster, article, .movie-item").mapNotNull {
            it.toSearchResponse()
        }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.length < 3) return emptyList()
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val doc = app.get("$mainUrl/?s=$encodedQuery").document
        
        return doc.select("article.poster, article, .movie-item").mapNotNull {
            it.toSearchResponse()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        
        val title = doc.selectFirst("h1.entry-title, h1, .title")?.text()?.cleanTitle() ?: "Unknown Title"
        val posterUrl = posterCache[url] ?: ""
        val synopsis = doc.selectFirst(".entry-content, .post-content, .description")?.text() ?: ""
        val year = doc.selectFirst(".year, .labels .year, .release-year")?.text()?.getIntFromText()
        
        val tags = doc.select(".gerne a, .genre a, .category a").map { it.text() }
        
        val recommendations = doc.select(".related-posts article, .similar-movies .movie-item").mapNotNull { element ->
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
        
        val doc = app.get(data).document
        
        // Extract video links
        doc.select("a[href*='.mp4'], a[href*='.m3u8'], iframe").forEach { element ->
            val url = element.attr("href").ifBlank { element.attr("src") }
            if (url.isNotBlank()) {
                foundLinks = true
                loadExtractor(url, data, subtitleCallback, callback)
            }
        }
        
        return foundLinks
    }
}