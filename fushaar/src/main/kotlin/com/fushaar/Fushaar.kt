package com.fushaar

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class FushaarProvider : MainAPI() {
    // 🔧 BASIC CONFIGURATION (ALWAYS REQUIRED)
    override var mainUrl = "https://fushaar.com"
    override var name = "Fushaar"
    override val usesWebView = false // Change to true ONLY if site has Cloudflare
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "ar"

    // ✅ SAFE: Custom headers helper (NOT override)
    private fun getCustomHeaders(): Map<String, String> = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
        "Accept-Language" to "ar"
    )

    // ✅ SAFE: Element to SearchResponse converter
    private fun Element.toSearchResponse(): SearchResponse? {
        return try {
            val title = select("h1, h2, h3, .title").firstOrNull()?.text()?.trim() ?: return null
            val href = select("a").attr("href") ?: return null
            val posterUrl = select("img").attr("src")
            
            // Determine content type
            val type = when {
                href.contains("/series/") || href.contains("/tv/") -> TvType.TvSeries
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

    // ✅ SAFE: Main page configuration
    override val mainPage = mainPageOf(
        "https://fushaar.com/" to "Latest Content"
    )

    // ✅ SAFE: Main page implementation
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return try {
            val url = if (page > 1) "${request.data}page/$page/" else request.data
            val document = app.get(url, headers = getCustomHeaders()).document
            
            val home = document.select("article").mapNotNull { element ->
                element.toSearchResponse()
            }
            
            newHomePageResponse(request.name, home)
        } catch (e: Exception) {
            newHomePageResponse(request.name, emptyList())
        }
    }

    // ✅ SAFE: Search implementation
    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            if (query.length < 3) return emptyList()
            val document = app.get("$mainUrl/?s=$query", headers = getCustomHeaders()).document
            
            document.select("article").mapNotNull { element ->
                element.toSearchResponse()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ✅ SAFE: Load implementation
    override suspend fun load(url: String): LoadResponse {
        return try {
            val document = app.get(url, headers = getCustomHeaders()).document
            
            val title = document.selectFirst("h1")?.text()?.trim() ?: "Unknown Title"
            val posterUrl = document.selectFirst("img")?.attr("src") ?: ""
            val description = document.selectFirst("[class*='content'], [class*='description'], .plot")?.text()?.trim() ?: ""
            
            val isTvSeries = url.contains("/series/") || document.select(".episodes, .seasons").isNotEmpty()
            
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

    // ✅ ENHANCED: Load links implementation with MULTIPLE extraction methods
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            var foundLinks = false
            val document = app.get(data, headers = getCustomHeaders()).document
            
            // 🎯 METHOD 1: Direct MP4 links (from analysis)
            document.select("a[href*='.mp4']").forEach { link ->
                val url = link.attr("href")
                val qualityText = link.text().lowercase()
                
                if (url.isNotBlank()) {
                    val quality = when {
                        "1080" in qualityText || "fullhd" in qualityText -> Qualities.FullHDP.value
                        "480" in qualityText || "web" in qualityText -> Qualities.480P.value
                        "240" in qualityText || "sd" in qualityText -> Qualities.240P.value
                        else -> Qualities.Unknown.value
                    }
                    
                    callback.invoke(
                        ExtractorLink(
                            name,
                            "$name - Direct",
                            url,
                            "$mainUrl/",
                            quality,
                            false
                        )
                    )
                    foundLinks = true
                }
            }
            
            // 🎯 METHOD 2: Embedded players (from analysis)
            document.select("iframe").forEach { iframe ->
                val src = iframe.attr("src")
                if (src.isNotBlank()) {
                    foundLinks = true
                    // CloudStream will automatically handle these extractors:
                    // - voe.sx (supported)
                    // - uqload.com (supported) 
                    // - YouTube (supported)
                    // - Custom players via loadExtractor
                    loadExtractor(src, data, subtitleCallback, callback)
                }
            }
            
            // 🎯 METHOD 3: Player.php embeds (site-specific)
            document.select("iframe[src*='player.php']").forEach { iframe ->
                val src = iframe.attr("src")
                if (src.isNotBlank()) {
                    foundLinks = true
                    loadExtractor(src, data, subtitleCallback, callback)
                }
            }
            
            // 🎯 METHOD 4: Download service links
            document.select("a[href*='fushaar.link'], a[href*='uptobox.com']").forEach { link ->
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
