package com.fajrshow

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.network.CloudflareKiller
import org.jsoup.nodes.Element
import java.net.URLEncoder

class Fajrshow : MainAPI() {
    override var lang = "ar"
    override var mainUrl = "https://fajer.show"
    override var name = "Fajrshow"
    override val usesWebView = false // Using CloudflareKiller instead
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // Cloudflare Killer interceptor - CORRECT IMPLEMENTATION
    private val cloudflareKiller by lazy { CloudflareKiller() }

    // Override client with CloudflareKiller
    override val client = super.client.newBuilder()
        .addInterceptor(cloudflareKiller)
        .build()

    private fun String.getIntFromText(): Int? {
        return Regex("""\d+""").find(this)?.groupValues?.firstOrNull()?.toIntOrNull()
    }
    
    private fun String.cleanTitle(): String {
        return this.replace("مشاهدة وتحميل|اونلاين|مترجم".toRegex(), "").trim()
    }

    private fun Element.toMovieSearchResponse(): SearchResponse? {
        return try {
            val title = select("h3").text().cleanTitle()
            val posterUrl = select("img").attr("src")
            val href = select("a").attr("href")
            
            if (title.isBlank() || href.isBlank()) return null
            
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        } catch (e: Exception) {
            null
        }
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
        } catch (e: Exception) {
            null
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/movies/" to "آخر الأفلام",
        "$mainUrl/tvshows/" to "آخر المسلسلات", 
        "$mainUrl/genre/arabic-movies/" to "أفلام عربية"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return try {
            val url = if (page > 1) "${request.data}page/$page/" else request.data
            val document = app.get(url).document
            
            val home = document.select("article.item").mapNotNull { element ->
                try {
                    val typeClass = element.attr("class")
                    when {
                        typeClass.contains("movies") -> element.toMovieSearchResponse()
                        typeClass.contains("tvshows") -> element.toTvSearchResponse()
                        else -> element.toMovieSearchResponse()
                    }
                } catch (e: Exception) {
                    null
                }
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
            val doc = app.get("$mainUrl/?s=$encodedQuery").document
            
            doc.select("article.item").mapNotNull { element ->
                try {
                    val typeClass = element.attr("class")
                    when {
                        typeClass.contains("movies") -> element.toMovieSearchResponse()
                        typeClass.contains("tvshows") -> element.toTvSearchResponse()
                        else -> element.toMovieSearchResponse()
                    }
                } catch (e: Exception) {
                    null
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        return try {
            val doc = app.get(url).document
            
            val title = doc.selectFirst("h1")?.text()?.cleanTitle() ?: "Unknown Title"
            val posterUrl = doc.selectFirst("img[src*='wp-content']")?.attr("src") ?: ""
            val synopsis = doc.selectFirst(".entry-content, .wp-content")?.text() ?: ""
            
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
            val doc = app.get(data).document
            
            doc.select("iframe").forEach { iframe ->
                val src = iframe.attr("src")
                if (src.isNotBlank()) {
                    foundLinks = true
                    loadExtractor(src, data, subtitleCallback, callback)
                }
            }
            
            foundLinks
        } catch (e: Exception) {
            false
        }
    }
}