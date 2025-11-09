package com.moviztime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class MoviztimeProvider : MainAPI() {
    override var mainUrl = "https://moviz-time.live"
    override var name = "Moviz Time"
    override val usesWebView = false
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie)

    // Simple Cloudflare bypass
    private val safeHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
    )

    override val mainPage = mainPageOf(
        "$mainUrl" to "الأفلام المضافة حديثاً",
        "$mainUrl/category/%d8%a3%d9%81%d9%84%d8%a7%d9%85-2025/" to "أفلام 2025",
        "$mainUrl/category/%d8%a3%d9%81%d9%84%d8%a7%d9%85-2024/" to "أفلام 2024", 
        "$mainUrl/category/%d8%a3%d9%81%d9%84%d8%a7%d9%85-2023/" to "أفلام 2023",
        "$mainUrl/category/%d8%a3%d9%81%d9%84%d8%a7%d9%85-2022/" to "أفلام 2022",
        "$mainUrl/category/%d8%a3%d9%81%d9%84%d8%a7%d9%85-2021/" to "أفلام 2021",
        "$mainUrl/category/%d8%a3%d9%81%d9%84%d8%a7%d9%85-%d8%a3%d8%ac%d9%86%d8%a8%d9%8a%d8%a9/" to "أفلام أجنبية",
        "$mainUrl/category/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%a3%d8%ac%d9%86%d8%a8%d9%8a%d8%a9-%d9%85%d8%aa%d8%b1%d8%ac%d9%85%d8%a9-e/" to "مسلسلات أجنبية"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page > 1) "${request.data}page/$page/" else request.data
        val document = app.get(url, headers = safeHeaders).document
        
        // Use .series selector from analysis (found 24 items)
        val home = document.select(".series, article").mapNotNull { element ->
            val title = element.select("h2, h3").text()
            val href = element.select("a").attr("href")
            val poster = element.select("img").attr("src")
            
            if (title.isNotBlank() && href.isNotBlank()) {
                newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = poster
                }
            } else null
        }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query", headers = safeHeaders).document
        
        return document.select(".series, article").mapNotNull { element ->
            val title = element.select("h2, h3").text()
            val href = element.select("a").attr("href")
            val poster = element.select("img").attr("src")
            
            if (title.isNotBlank() && href.isNotBlank()) {
                newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = poster
                }
            } else null
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = safeHeaders).document
        
        val title = document.selectFirst("h1")?.text() ?: "Unknown"
        val poster = document.selectFirst("img")?.attr("src") ?: ""
        val description = document.selectFirst(".content, .entry-content")?.text() ?: ""
        
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var foundLinks = false
        
        // METHOD 1: Extract iframes with headers
        val mainDoc = app.get(data, headers = safeHeaders).document
        val iframes = mainDoc.select("iframe")
        
        iframes.forEach { iframe ->
            val iframeUrl = iframe.attr("src")
            if (iframeUrl.isNotBlank()) {
                foundLinks = true
                loadExtractor(iframeUrl, data, subtitleCallback, callback)
            }
        }
        
        // METHOD 2: Try with GET parameters that worked
        if (!foundLinks) {
            val workingParams = listOf(
                mapOf("view" to "1"),
                mapOf("load" to "video")
            )
            
            for (params in workingParams) {
                try {
                    val paramDoc = app.get(data, params = params, headers = safeHeaders).document
                    paramDoc.select("iframe").forEach { iframe ->
                        val iframeUrl = iframe.attr("src")
                        if (iframeUrl.isNotBlank()) {
                            foundLinks = true
                            loadExtractor(iframeUrl, data, subtitleCallback, callback)
                        }
                    }
                    if (foundLinks) break
                } catch (e: Exception) {
                    // Continue to next method
                }
            }
        }
        
        return foundLinks
    }
}
