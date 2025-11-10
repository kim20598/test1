package com.fajrshow

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.network.Requests
import org.jsoup.nodes.Element
import java.net.URLEncoder
import kotlinx.coroutines.delay

class Fajrshow : MainAPI() {
    override var lang = "ar"
    override var mainUrl = "https://fajer.show"
    override var name = "Fajrshow"
    override val usesWebView = true // WebView fallback for Cloudflare
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // 🔥 Cloudflare bypass setup
    private val cfKiller = CloudflareKiller()

    // Custom headers helper
    private fun getCustomHeaders(): Map<String, String> = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "ar,en-US;q=0.7,en;q=0.3",
        "Cache-Control" to "no-cache",
        "Pragma" to "no-cache",
        "User-Agent" to Requests.USER_AGENT,
        "Referer" to mainUrl
    )

    // Strong request helper that auto-retries & bypasses CF
    private suspend fun safeGet(url: String): org.jsoup.nodes.Document {
        var attempts = 0
        while (attempts < 3) {
            try {
                // Try CloudflareKiller first
                val cfResult = cfKiller.bypass(url, headers = getCustomHeaders())
                if (cfResult != null) return cfResult.document

                // Try normal request
                return app.get(url, headers = getCustomHeaders()).document
            } catch (e: Exception) {
                attempts++
                delay(1000L * attempts) // exponential backoff
                if (attempts >= 3) throw e
            }
        }
        error("Cloudflare bypass failed")
    }

    private fun String.getIntFromText(): Int? =
        Regex("""\d+""").find(this)?.groupValues?.firstOrNull()?.toIntOrNull()

    private fun String.cleanTitle(): String =
        this.replace("مشاهدة وتحميل|اونلاين|مترجم".toRegex(), "").trim()

    private fun Element.toMovieSearchResponse(): SearchResponse? {
        return try {
            val title = select("h3").text().cleanTitle()
            val posterUrl = select("img").attr("src")
            val href = select("a").attr("href")
            if (title.isBlank() || href.isBlank()) return null
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        } catch (_: Exception) { null }
    }

    private fun Element.toTvSearchResponse(): SearchResponse? {
        return try {
            val title = select("h3").text().cleanTitle()
            val posterUrl = select("img").attr("src")
            val href = select("a").attr("href")
            if (title.isBlank() || href.isBlank()) return null
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        } catch (_: Exception) { null }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/movies/" to "آخر الأفلام",
        "$mainUrl/tvshows/" to "آخر المسلسلات",
        "$mainUrl/genre/arabic-movies/" to "أفلام عربية"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return try {
            val url = if (page > 1) "${request.data}page/$page/" else request.data
            val document = safeGet(url)

            val home = document.select("article.item").mapNotNull { element ->
                val typeClass = element.attr("class")
                when {
                    typeClass.contains("movies") -> element.toMovieSearchResponse()
                    typeClass.contains("tvshows") -> element.toTvSearchResponse()
                    else -> element.toMovieSearchResponse()
                }
            }
            newHomePageResponse(request.name, home)
        } catch (_: Exception) {
            newHomePageResponse(request.name, emptyList())
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            if (query.length < 3) return emptyList()
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val doc = safeGet("$mainUrl/?s=$encodedQuery")

            doc.select("article.item").mapNotNull { element ->
                val typeClass = element.attr("class")
                when {
                    typeClass.contains("movies") -> element.toMovieSearchResponse()
                    typeClass.contains("tvshows") -> element.toTvSearchResponse()
                    else -> element.toMovieSearchResponse()
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        return try {
            val doc = safeGet(url)

            val title = doc.selectFirst("h1")?.text()?.cleanTitle() ?: "Unknown Title"
            val posterUrl = doc.selectFirst("img")?.attr("src") ?: ""
            val synopsis = doc.selectFirst(".entry-content, .wp-content, p")?.text() ?: ""
            val year = doc.selectFirst(".year, .date")?.text()?.getIntFromText()
            val isTvSeries = url.contains("/tvshows/") || doc.select("#seasons").isNotEmpty()

            if (isTvSeries) {
                newTvSeriesLoadResponse(title, url, TvType.TvSeries, emptyList()) {
                    this.posterUrl = posterUrl
                    this.plot = synopsis
                    this.year = year
                }
            } else {
                newMovieLoadResponse(title, url, TvType.Movie, url) {
                    this.posterUrl = posterUrl
                    this.plot = synopsis
                    this.year = year
                }
            }
        } catch (_: Exception) {
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
            val doc = safeGet(data)

            doc.select("iframe").forEach { iframe ->
                val src = iframe.attr("src")
                if (src.isNotBlank()) {
                    foundLinks = true
                    loadExtractor(src, data, subtitleCallback, callback)
                }
            }

            foundLinks
        } catch (_: Exception) {
            false
        }
    }
}