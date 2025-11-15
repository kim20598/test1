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

    private fun Element.toSearchResult(): SearchResponse? {
        val title = selectFirst("h3, .title")?.text()?.trim() ?: return null
        val href = selectFirst("a")?.attr("href") ?: return null
        val posterUrl = selectFirst("img")?.attr("src")

        val type = when {
            href.contains("/series/") || href.contains("مسلسل") -> TvType.TvSeries
            href.contains("/anime/") || href.contains("انمي") -> TvType.Anime
            else -> TvType.Movie
        }

        return newMovieSearchResponse(title, href, type) {
            this.posterUrl = posterUrl
        }
    }

    // Updated mainPage based on analysis with proper categories
    override val mainPage = mainPageOf(
        "$mainUrl/category/%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d8%a7%d8%ac%d9%86%d8%a8%d9%8a/" to "افلام اجنبي",
        "$mainUrl/category/%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d8%a7%d8%ac%d9%86%d8%a8%d9%8a%d8%a9-%d9%85%d8%af%d8%a8%d9%84%d8%ac%d8%a9/" to "افلام مدبلجة",
        "$mainUrl/category/%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d8%a7%d8%b3%d9%8a%d9%88%d9%8a/" to "افلام اسيوية",
        "$mainUrl/category/%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d8%aa%d8%b1%d9%83%d9%8a%d8%a9/" to "افلام تركية",
        "$mainUrl/category/%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d9%88%d8%ab%d8%a7%d8%a6%d9%82%d9%8a%d8%a9/" to "افلام وثائقية",
        "$mainUrl/category/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%a7%d8%ac%d9%86%d8%a8%d9%8a/" to "مسلسلات اجنبي",
        "$mainUrl/category/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%a7%d8%b3%d9%8a%d9%88%d9%8a%d8%a9/" to "مسلسلات اسيوية",
        "$mainUrl/category/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%aa%d8%b1%d9%83%d9%8a%d8%a9/" to "مسلسلات تركية",
        "$mainUrl/category/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%aa%d8%b1%d9%83%d9%8a%d8%a9-%d9%85%d8%af%d8%a8%d9%84%d8%ac%d8%a9/" to "مسلسلات تركية مدبلجة",
        "$mainUrl/category/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d9%87%d9%86%d8%af%d9%8a%d8%a9/" to "مسلسلات هندية",
        "$mainUrl/category/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d9%84%d8%a7%d8%aa%d9%8a%d9%86%d9%8a%d8%a9/" to "مسلسلات لاتينية",
        "$mainUrl/category/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d9%88%d8%ab%d8%a7%d8%a6%d9%82%d9%8a%d8%a9/" to "مسلسلات وثائقية",
        "$mainUrl/category/%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d8%a7%d9%86%d9%85%d9%8a/" to "افلام انمي",
        "$mainUrl/category/%d8%a7%d9%86%d9%85%d9%8a-%d9%85%d8%aa%d8%b1%d8%ac%d9%85/" to "انمي مترجم",
        "$mainUrl/category/%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d9%83%d8%b1%d8%aa%d9%88%d9%86/" to "افلام كرتون",
        "$mainUrl/category/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%a7%d9%86%d9%85%d9%8a/" to "مسلسلات انمي",
        "$mainUrl/category/%d8%b9%d8%b1%d9%88%d8%b6-%d9%88%d8%ad%d9%81%d9%84%d8%a7%d8%aa/" to "عروض وحفلات",
        "$mainUrl/last/" to "المضاف حديثا"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) "${request.data}page/$page/" else request.data
        val document = app.get(url).document

        val items = document.select("section, article, .poster, .item").mapNotNull {
            it.toSearchResult()
        }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val document = app.get("$mainUrl/?s=$encodedQuery").document

        return document.select("section, article, .poster, .item").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1, .entry-title")?.text()?.trim() 
            ?.replace("مشاهدة|تحميل|مترجم|فيلم|مسلسل".toRegex(), "")?.trim() 
            ?: "Unknown"
        
        val posterUrl = document.selectFirst(".poster img, .single-thumbnail img")?.attr("src")
        val description = document.selectFirst(".entry-content p, .story")?.text()?.trim()
        val year = document.selectFirst(".year")?.text()?.toIntOrNull()
        val tags = document.select(".genre a, .category a").map { it.text() }

        // Check if it's a series by looking for episodes
        val episodes = document.select(".episodes-list a, .episode-link").mapNotNull { episodeElement ->
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
        val document = app.get(data).document
        var foundLinks = false

        // Try watch and download pages first (from analysis)
        val watchUrl = data.replace("/$", "/watch/")
        val downloadUrl = data.replace("/$", "/download/")
        
        // Try watch page
        try {
            val watchDoc = app.get(watchUrl).document
            extractLinksFromDocument(watchDoc, data, subtitleCallback, callback).let { 
                if (it) foundLinks = true 
            }
        } catch (e: Exception) {
            // Continue if watch page fails
        }

        // Try download page
        try {
            val downloadDoc = app.get(downloadUrl).document
            extractLinksFromDocument(downloadDoc, data, subtitleCallback, callback).let { 
                if (it) foundLinks = true 
            }
        } catch (e: Exception) {
            // Continue if download page fails
        }

        // Try main page as fallback
        if (!foundLinks) {
            extractLinksFromDocument(document, data, subtitleCallback, callback).let { 
                if (it) foundLinks = true 
            }
        }

        return foundLinks
    }

    private fun extractLinksFromDocument(
        doc: org.jsoup.nodes.Document,
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var foundLinks = false

        // Try to find iframes
        doc.select("iframe").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotBlank() && !src.startsWith("about:")) {
                foundLinks = true
                loadExtractor(src, data, subtitleCallback, callback)
            }
        }

        // Try to find download links
        doc.select("a[href*='.mp4'], a[href*='.m3u8']").forEach { link ->
            val href = link.attr("href")
            if (href.isNotBlank()) {
                foundLinks = true
                loadExtractor(href, data, subtitleCallback, callback)
            }
        }

        // Try hidden player divs
        doc.select(".player-container, .video-player, [data-url], [data-src]").forEach { player ->
            val dataUrl = player.attr("data-url").ifBlank { player.attr("data-src") }
            if (dataUrl.isNotBlank()) {
                foundLinks = true
                loadExtractor(dataUrl, data, subtitleCallback, callback)
            }
        }

        // Try AJAX endpoints (from analysis)
        doc.select("a[href*='watch'], a[href*='download']").forEach { link ->
            val href = link.attr("href")
            if (href.isNotBlank() && (href.contains("/watch/") || href.contains("/download/"))) {
                foundLinks = true
                loadExtractor(href, data, subtitleCallback, callback)
            }
        }

        return foundLinks
    }
}