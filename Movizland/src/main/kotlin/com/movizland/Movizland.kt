package com.movizland

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import java.net.URLEncoder

class Movizland : MainAPI() {
    override var mainUrl = "https://en.movizlands.com"
    override var name = "Movizland"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)
    override val usesWebView = false

    // Add proper user agent to bypass basic protection
    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.5",
        "Accept-Encoding" to "gzip, deflate, br",
        "Connection" to "keep-alive"
    )

    private fun Element.toSearchResult(): SearchResponse? {
        // Try multiple selectors
        val title = selectFirst("h3, h2, .title, .entry-title, .post-title")?.text()?.trim() ?: return null
        val href = selectFirst("a")?.attr("href") ?: return null
        val posterUrl = selectFirst("img")?.attr("src").let { 
            it ?: selectFirst("img")?.attr("data-src")
        }

        val type = when {
            href.contains("/series/") || href.contains("مسلسل") || title.contains("مسلسل") -> TvType.TvSeries
            href.contains("/anime/") || href.contains("انمي") || title.contains("انمي") -> TvType.Anime
            else -> TvType.Movie
        }

        return newMovieSearchResponse(title, href, type) {
            this.posterUrl = posterUrl
        }
    }

    // Simplified main page with fewer categories for testing
    override val mainPage = mainPageOf(
        "$mainUrl/" to "Home | الرئيسية",
        "$mainUrl/category/افلام-اجنبي/" to "Foreign Movies | أفلام أجنبي",
        "$mainUrl/category/مسلسلات-اجنبي/" to "Foreign Series | مسلسلات أجنبي",
        "$mainUrl/category/افلام-انمي/" to "Anime Movies | أفلام أنمي"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) "${request.data}page/$page/" else request.data
        val document = app.get(url, headers = headers).document

        // Try multiple selectors
        val items = document.select("article, section, .movie, .item, .post, .poster").mapNotNull {
            it.toSearchResult()
        }

        // If no items found, try to debug
        if (items.isEmpty()) {
            println("DEBUG: No items found for URL: $url")
            println("DEBUG: Page title: ${document.selectFirst("title")?.text()}")
            println("DEBUG: Found elements: ${document.select("article, section, .movie, .item").size}")
        }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.length < 3) return emptyList()
        
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val document = app.get("$mainUrl/?s=$encodedQuery", headers = headers).document

        return document.select("article, section, .movie, .item, .post").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = headers).document

        val title = document.selectFirst("h1, .entry-title, .post-title")?.text()?.trim() 
            ?.replace("مشاهدة|تحميل|مترجم|فيلم|مسلسل".toRegex(), "")?.trim() 
            ?: "Unknown"
        
        val posterUrl = document.selectFirst(".poster img, .single-thumbnail img, .wp-post-image")?.attr("src")
        val description = document.selectFirst(".entry-content p, .story, .plot, .description")?.text()?.trim()
        val year = document.selectFirst(".year, .date")?.text()?.toIntOrNull()
        val tags = document.select(".genre a, .category a, .tags a").map { it.text() }

        // Check if it's a series by looking for episodes
        val episodes = document.select(".episodes-list a, .episode-link, .episode a").mapNotNull { episodeElement ->
            val epHref = episodeElement.attr("href")
            val epTitle = episodeElement.text().trim()
            val epNum = Regex("\\d+").findAll(epTitle).lastOrNull()?.value?.toIntOrNull()

            if (epHref.isNotBlank()) {
                newEpisode(epHref) {
                    name = epTitle
                    episode = epNum
                }
            } else null
        }

        return if (episodes.isNotEmpty()) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl
                this.plot = description
                this.year = year
                this.tags = tags
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = posterUrl
                this.plot = description
                this.year = year
                this.tags = tags
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, headers = headers).document
        var foundLinks = false

        // Try to find iframes
        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotBlank() && !src.startsWith("about:")) {
                foundLinks = true
                loadExtractor(src, data, subtitleCallback, callback)
            }
        }

        // Try to find download links
        document.select("a[href*='.mp4'], a[href*='.m3u8']").forEach { link ->
            val href = link.attr("href")
            if (href.isNotBlank()) {
                foundLinks = true
                loadExtractor(href, data, subtitleCallback, callback)
            }
        }

        return foundLinks
    }
}
