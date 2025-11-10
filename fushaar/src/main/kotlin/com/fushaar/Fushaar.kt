package com.fushaar

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import java.net.URLEncoder

class Fushaar : MainAPI() {
    override var mainUrl = "https://fushaar.com"
    override var name = "Fushaar"
    override val usesWebView = false
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "ar"

    // Custom selectors for fushaar.com
    private val containerSelector = ".moviesList, .movie, .item, .post, .film"
    private val titleSelector = ".title, h2, h3, a"
    private val posterSelector = "img"
    private val linkSelector = "a"

    private fun Element.toSearchResponse(): SearchResponse? {
        return try {
            val linkElement = select(linkSelector).firstOrNull() ?: return null
            val title = linkElement.attr("title").takeIf { it.isNotBlank() } 
                ?: linkElement.select(titleSelector).firstOrNull()?.text()?.trim() 
                ?: return null
            
            val href = linkElement.attr("href").takeIf { it.isNotBlank() } ?: return null
            val fullUrl = if (href.startsWith("http")) href else mainUrl + href
            
            val posterElement = select(posterSelector).firstOrNull()
            val posterUrl = posterElement?.attr("src") ?: posterElement?.attr("data-src") ?: ""
            val fullPoster = when {
                posterUrl.startsWith("http") -> posterUrl
                posterUrl.startsWith("//") -> "https:$posterUrl"
                posterUrl.isNotBlank() -> mainUrl + posterUrl
                else -> ""
            }
            
            // Determine type based on URL or content
            val type = when {
                fullUrl.contains("/series/", true) || 
                fullUrl.contains("/مسلسل/", true) || 
                fullUrl.contains("/مسلسلات/", true) -> TvType.TvSeries
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
        "$mainUrl/movies/" to "أفلام",
        "$mainUrl/series/" to "مسلسلات", 
        "$mainUrl/" to "الصفحة الرئيسية"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return try {
            val url = if (page > 1) "${request.data}page/$page/" else request.data
            val document = app.get(url, timeout = 30).document
            
            val items = document.select(containerSelector).mapNotNull { element ->
                element.toSearchResponse()
            }
            
            newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
        } catch (e: Exception) {
            newHomePageResponse(request.name, emptyList())
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            if (query.length < 2) return emptyList()
            
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val searchUrl = "$mainUrl/?s=$encodedQuery"
            val document = app.get(searchUrl, timeout = 30).document
            
            document.select(containerSelector).mapNotNull { element ->
                element.toSearchResponse()
            }.distinctBy { it.url }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> {
        return search(query)
    }

    override suspend fun load(url: String): LoadResponse {
        return try {
            val document = app.get(url, timeout = 30).document
            
            val title = document.selectFirst("h1, .title, h2")?.text()?.trim() ?: "Unknown Title"
            val poster = document.selectFirst(".poster img, .cover img, img")?.let { img ->
                val src = img.attr("src").ifBlank { img.attr("data-src") }
                when {
                    src.startsWith("http") -> src
                    src.startsWith("//") -> "https:$src"
                    src.isNotBlank() -> mainUrl + src
                    else -> ""
                }
            } ?: ""
            
            val description = document.selectFirst(".plot, .description, .content, p")?.text()?.trim() ?: ""
            
            // Determine if it's TV series or Movie
            val isTvSeries = url.contains("/series/", true) || 
                            url.contains("/مسلسل/", true) ||
                            document.select(".episodes, .seasons").isNotEmpty()

            if (isTvSeries) {
                // For TV series - create a simple episode
                val episodes = listOf(
                    newEpisode(url) {
                        this.name = "الحلقة 1"
                        this.episode = 1
                        this.season = 1
                    }
                )

                newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                    this.posterUrl = poster
                    this.plot = description
                }
            } else {
                // For movies
                newMovieLoadResponse(title, url, TvType.Movie, url) {
                    this.posterUrl = poster
                    this.plot = description
                }
            }
        } catch (e: Exception) {
            newMovieLoadResponse("Error Loading", url, TvType.Movie, url) {
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
            val document = app.get(data, timeout = 30).document
            
            // Extract from iframes (most common)
            document.select("iframe").forEach { iframe ->
                val src = iframe.attr("src").takeIf { it.isNotBlank() } ?: return@forEach
                if (loadExtractor(src, data, subtitleCallback, callback)) {
                    foundLinks = true
                }
            }
            
            // Extract from video players - FIXED: Using proper ExtractorLink
            document.select("video source").forEach { source ->
                val src = source.attr("src").takeIf { it.isNotBlank() } ?: return@forEach
                callback.invoke(
                    ExtractorLink(
                        name,
                        src,
                        mainUrl,
                        "Direct",
                        Qualities.Unknown.value,
                        false
                    )
                )
                foundLinks = true
            }
            
            // Extract from download links
            document.select("a[href*='.mp4'], a[href*='.m3u8']").forEach { link ->
                val url = link.attr("href").takeIf { it.isNotBlank() } ?: return@forEach
                if (loadExtractor(url, data, subtitleCallback, callback)) {
                    foundLinks = true
                }
            }
            
            foundLinks
        } catch (e: Exception) {
            false
        }
    }
}