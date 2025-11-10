package com.fushaar

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import java.net.URLEncoder

class fushaar : MainAPI() {
    override var mainUrl = "https://fushaar.com"
    override var name = "fushaar"
    override val usesWebView = false
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "en"

    private fun Element.toSearchResponse(): SearchResponse? {
        return try {
            val title = select("h1, h2, h3, .title, .name, a").firstOrNull()?.text()?.trim() ?: return null
            val href = select("a").attr("href") ?: return null
            val fullUrl = if (href.startsWith("http")) href else mainUrl + href
            val posterUrl = select("img").attr("src")
            val fullPoster = if (posterUrl.startsWith("http")) posterUrl else mainUrl + posterUrl
            
            val type = when {
                fullUrl.contains("/series/") || fullUrl.contains("/tv/") -> TvType.TvSeries
                else -> TvType.Movie
            }
            
            if (type == TvType.TvSeries) {
                newTvSeriesSearchResponse(title, fullUrl, type) {
                    this.posterUrl = fullPoster
                }
            } else {
                newMovieSearchResponse(title, fullUrl, type) {
                    this.posterUrl = fullPoster
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/movies/" to "Movies",
        "$mainUrl/series/" to "Series",
        "$mainUrl/" to "Home"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return try {
            val url = if (page > 1) "${request.data}page/$page/" else request.data
            val document = app.get(url).document
            
            val home = document.select("article, .item, .movie, .series, .card, .post").mapNotNull { element ->
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
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val document = app.get("$mainUrl/?s=$encodedQuery").document
            
            document.select("article, .item, .movie, .series, .card, .post").mapNotNull { element ->
                element.toSearchResponse()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        return try {
            val document = app.get(url).document
            
            val title = document.selectFirst("h1, h2, .title")?.text()?.trim() ?: "Unknown Title"
            val posterUrl = document.selectFirst("img")?.attr("src") ?: ""
            val fullPoster = if (posterUrl.startsWith("http")) posterUrl else mainUrl + posterUrl
            val description = document.selectFirst("p, .content, .description, .plot")?.text()?.trim() ?: ""
            
            val isTvSeries = url.contains("/series/") || url.contains("/tv/") || document.select(".episodes, .seasons").isNotEmpty()
            
            if (isTvSeries) {
                newTvSeriesLoadResponse(title, url, TvType.TvSeries, emptyList()) {
                    this.posterUrl = fullPoster
                    this.plot = description
                }
            } else {
                newMovieLoadResponse(title, url, TvType.Movie, url) {
                    this.posterUrl = fullPoster
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
            
            document.select("iframe").forEach { iframe ->
                val src = iframe.attr("src")
                if (src.isNotBlank()) {
                    foundLinks = true
                    loadExtractor(src, data, subtitleCallback, callback)
                }
            }
            
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