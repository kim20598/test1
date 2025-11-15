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

    override val mainPage = mainPageOf(
        "$mainUrl/category/افلام-اجنبي/" to "افلام اجنبي",
        "$mainUrl/category/افلام-اجنبية-مدبلجة/" to "افلام مدبلجة",
        "$mainUrl/category/افلام-اسيوي/" to "افلام اسيوية",
        "$mainUrl/category/افلام-تركية/" to "افلام تركية",
        "$mainUrl/category/مسلسلات-اجنبي/" to "مسلسلات اجنبي",
        "$mainUrl/category/مسلسلات-اسيوية/" to "مسلسلات اسيوية",
        "$mainUrl/category/مسلسلات-تركية/" to "مسلسلات تركية",
        "$mainUrl/category/مسلسلات-انمي/" to "مسلسلات انمي",
        "$mainUrl/category/افلام-انمي/" to "افلام انمي",
        "$mainUrl/category/افلام-كرتون/" to "افلام كرتون"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) "${request.data}page/$page/" else request.data
        val document = app.get(url).document

        val items = document.select("article, .poster, .item").mapNotNull {
            it.toSearchResult()
        }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val document = app.get("$mainUrl/?s=$encodedQuery").document

        return document.select("article, .poster, .item").mapNotNull {
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
                    posterUrl = posterUrl
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

        // Try to find iframes
        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotBlank()) {
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

        // Try hidden player divs
        document.select(".player-container, .video-player").forEach { player ->
            val dataUrl = player.attr("data-url").ifBlank { player.attr("data-src") }
            if (dataUrl.isNotBlank()) {
                foundLinks = true
                loadExtractor(dataUrl, data, subtitleCallback, callback)
            }
        }

        return foundLinks
    }
}