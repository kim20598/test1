package com.moviztime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import java.net.URLEncoder

class MovizTime : MainAPI() {
    override var lang = "ar"
    override var mainUrl = "https://moviz-time.live"
    override var name = "Moviz Time"
    override val usesWebView = false
    override val hasMainPage = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )

    private fun String.getIntFromText(): Int? {
        return Regex("""\d+""").find(this)?.groupValues?.firstOrNull()?.toIntOrNull()
    }
    
    private fun String.cleanTitle(): String {
        return this.replace("مشاهدة وتحميل|اونلاين|مترجم|برابط مباشر".toRegex(), "").trim()
    }

    private val posterCache = mutableMapOf<String, String>()

    private fun Element.toSearchResponse(): SearchResponse {
        val title = select("h2, h3, .title, .entry-title").text().cleanTitle()
        
        val posterUrl = select("img").attr("data-lazy-src").ifBlank {
            select("img").attr("data-src").ifBlank {
                select("img").attr("src")
            }
        }
        val href = select("a").attr("href")
        
        if (posterUrl.isNotBlank()) {
            posterCache[href] = posterUrl
        }
        
        val isSeries = href.contains("/series/") || 
                       href.contains("/مسلسل/") || 
                       title.contains("حلقة") || 
                       title.contains("موسم")
        
        val isAnime = href.contains("/anime/") || 
                      href.contains("/انمي/") || 
                      title.contains("انمي") || 
                      title.contains("anime")

        val type = when {
            isAnime -> TvType.Anime
            isSeries -> TvType.TvSeries
            else -> TvType.Movie
        }

        return when (type) {
            TvType.Anime -> newAnimeSearchResponse(title, href) {
                this.posterUrl = posterUrl
            }
            TvType.TvSeries -> newTvSeriesSearchResponse(title, href) {
                this.posterUrl = posterUrl
            }
            else -> newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl" to "الأفلام المضافة حديثاً",
        "$mainUrl/category/افلام-عربي/" to "أفلام عربي",
        "$mainUrl/category/افلام-اجنبي/" to "أفلام أجنبي", 
        "$mainUrl/category/افلام-انمي/" to "أنمي",
        "$mainUrl/category/مسلسلات/" to "مسلسلات",
        "$mainUrl/category/مسلسلات-عربي/" to "مسلسلات عربي",
        "$mainUrl/category/مسلسلات-اجنبي/" to "مسلسلات أجنبي"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) "${request.data}page/$page/" else request.data
        val document = app.get(url).document
        
        val home = document.select("article, .post, .movie, .item, .grid-item, .card").mapNotNull {
            it.toSearchResponse()
        }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.length < 3) return emptyList()
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val doc = app.get("$mainUrl/?s=$encodedQuery").document
        
        return doc.select("article, .post, .movie, .item, .grid-item, .card").mapNotNull {
            it.toSearchResponse()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        
        val title = doc.selectFirst("h1, .entry-title")?.text()?.cleanTitle() ?: "Unknown Title"
        val posterUrl = posterCache[url] ?: doc.selectFirst(".poster img, .cover img, .wp-post-image")?.attr("src") ?: ""
        val synopsis = doc.selectFirst(".content, .entry-content, .description, .plot")?.text() ?: ""
        val year = doc.selectFirst(".year, .date")?.text()?.getIntFromText()
        val tags = doc.select(".genre a, .category a, .tags a").map { it.text() }
        val recommendations = doc.select(".related-posts article, .related article").mapNotNull { element ->
            element.toSearchResponse()
        }
        val youtubeTrailer = doc.selectFirst("iframe[src*='youtube'], iframe[src*='youtu.be']")?.attr("src") ?: ""
        
        // FIXED: Use newEpisode with proper parameters
        val episodes = doc.select(".episode, .episodes-list a, .episode-item").mapNotNull { episodeElement ->
            val epTitle = episodeElement.selectFirst(".title, h3, h4")?.text()?.trim() ?: "Episode"
            val epUrl = episodeElement.attr("href")
            val epNumber = episodeElement.selectFirst(".number, .episode-num")?.text()?.toIntOrNull() ?: 1
            
            if (epUrl.isNotBlank()) {
                newEpisode(epUrl) {
                    this.episode = epNumber
                    this.name = epTitle
                }
            } else null
        }

        val hasEpisodes = episodes.isNotEmpty() || 
                         doc.select(".season, .episodes, [class*='episode']").isNotEmpty() ||
                         url.contains("/series/") || 
                         url.contains("/مسلسل/")

        val isAnime = url.contains("/anime/") || 
                     url.contains("/انمي/") || 
                     title.contains("انمي") || 
                     title.contains("anime")

        return when {
            hasEpisodes && isAnime -> {
                newAnimeLoadResponse(title, url, TvType.Anime, episodes) {
                    this.posterUrl = posterUrl
                    this.recommendations = recommendations
                    this.plot = synopsis
                    this.tags = tags
                    this.year = year
                    addTrailer(youtubeTrailer)
                }
            }
            hasEpisodes -> {
                newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                    this.posterUrl = posterUrl
                    this.recommendations = recommendations
                    this.plot = synopsis
                    this.tags = tags
                    this.year = year
                    addTrailer(youtubeTrailer)
                }
            }
            else -> {
                newMovieLoadResponse(title, url, TvType.Movie, url) {
                    this.posterUrl = posterUrl
                    this.recommendations = recommendations
                    this.plot = synopsis
                    this.tags = tags
                    this.year = year
                    addTrailer(youtubeTrailer)
                }
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
        
        // Try video tags
        doc.select("video source").forEach { source ->
            val src = source.attr("src")
            if (src.isNotBlank()) {
                foundLinks = true
                loadExtractor(src, data, subtitleCallback, callback)
            }
        }
        
        return foundLinks
    }
}
