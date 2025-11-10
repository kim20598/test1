package com.fajrshow

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class FajerProvider : MainAPI() {
    override var mainUrl = "https://fajer.show"
    override var name = "Fajer"
    override val usesWebView = true
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)
    override var lang = "ar"

    private fun Element.toSearchResponse(): SearchResponse? {
        return try {
            val title = select("h1, h2, h3, .title").firstOrNull()?.text()?.trim() ?: "Unknown Title"
            val href = select("a").attr("href")
            val posterUrl = select("img").attr("src")
            
            if (title.isBlank() || href.isBlank()) return null
            
            // Determine type from URL or title
            val type = when {
                href.contains("/tvshows/") || title.contains("مسلسل") -> TvType.TvSeries
                else -> TvType.Movie
            }
            
            if (type == TvType.TvSeries) {
                newTvSeriesSearchResponse(title, href, type) {
                    this.posterUrl = posterUrl
                }
            } else {
                newMovieSearchResponse(title, href, type) {
                    this.posterUrl = posterUrl
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/movies/" to "أفلام",
        "$mainUrl/tvshows/" to "مسلسلات"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return try {
            val url = if (page > 1) "${request.data}page/$page/" else request.data
            val document = app.get(url).document
            
            val home = document.select("article.item, .movie, .series").mapNotNull { element ->
                element.toSearchResponse()
            }
            
            newHomePageResponse(request.name, home)
        } catch (e: Exception) {
            newHomePageResponse(request.name, emptyList())
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            if (query.length < 3) return emptyList()
            val document = app.get("$mainUrl/?s=$query").document
            
            document.select("article.item, .movie, .series").mapNotNull { element ->
                element.toSearchResponse()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        return try {
            val document = app.get(url).document
            
            val title = document.selectFirst("h1")?.text()?.trim() ?: "Unknown Title"
            val posterUrl = document.selectFirst("img")?.attr("src") ?: ""
            val description = document.selectFirst(".content, .description, .plot")?.text()?.trim() ?: ""
            
            val isTvSeries = url.contains("/tvshows/") || document.select("#seasons").isNotEmpty()
            
            if (isTvSeries) {
                newTvSeriesLoadResponse(title, url, TvType.TvSeries, emptyList()) {
                    this.posterUrl = posterUrl
                    this.plot = description
                }
            } else {
                newMovieLoadResponse(title, url, TvType.Movie, url) {
                    this.posterUrl = posterUrl
                    this.plot = description
                }
            }
        } catch (e: Exception) {
            newMovieLoadResponse("Error", url, TvType.Movie, url) {
                this.posterUrl = ""
                this.plot = "Failed to load content"
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            var foundLinks = false
            val document = app.get(data).document
            
            // Look for iframes first (most common)
            document.select("iframe").forEach { iframe ->
                val src = iframe.attr("src")
                if (src.isNotBlank()) {
                    foundLinks = true
                    loadExtractor(src, data, subtitleCallback, callback)
                }
            }
            
            // Look for direct video links
            document.select("a[href*='.mp4'], a[href*='.m3u8']").forEach { link ->
                val url = link.attr("href")
                if (url.isNotBlank()) {
                    foundLinks = true
                    loadExtractor(url, data, subtitleCallback, callback)
                }
            }
            
            foundLinks
        } catch (e: Exception) {
            false
        }
    }
}
