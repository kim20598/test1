package com.moviztime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class MovizTime : MainAPI() {
    override var mainUrl = "https://moviz-time.live"
    override var name = "Moviz Time"
    override val usesWebView = false
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie)

    private val cfHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8"
    )

    override val mainPage = mainPageOf(
        "$mainUrl" to "الأفلام المضافة حديثاً",
        "$mainUrl/category/%d8%a3%d9%81%d9%84%d8%a7%d9%85-2025/" to "أفلام 2025",
        "$mainUrl/category/%d8%a3%d9%81%d9%84%d8%a7%d9%85-2024/" to "أفلام 2024", 
        "$mainUrl/category/%d8%a3%d9%81%d9%84%d8%a7%d9%85-2023/" to "أفلام 2023",
        "$mainUrl/category/%d8%a3%d9%81%d9%84%d8%a7%d9%85-2022/" to "أفلام 2022",
        "$mainUrl/category/%d8%a3%d9%81%d9%84%d8%a7%d9%85-2021/" to "أفلام 2021",
        "$mainUrl/category/%d8%a3%d9%81%d9%84%d8%a7%d9%85-%d8%a3%d8%ac%d9%86%d8%a8%d9%8a%d8%a9/" to "أفلام أجنبية",
        "$mainUrl/category/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%a3%d8%ac%d9%86%d8%a8%d9%8a%d8%a9-%d9%85%d8%aa%d8%b1%d8%ac%d9%85%d8%a9-e/" to "مسلسلات أجنبية",
        "$mainUrl/category/%d9%82%d8%a7%d8%a6%d9%85%d8%a9-%d8%a7%d9%84%d8%a3%d9%86%d9%85%d9%8a-b/%d8%a3%d9%81%d9%84%d8%a7%d9%85-%d8%a3%d9%86%d9%85%d9%8a/" to "أفلام أنمي",
        "$mainUrl/category/imdb-top-250/" to "IMDb Top 250"
    )

    // Enhanced poster extraction function
    private fun extractPosterUrl(element: Element): String {
        // Priority 1: Images with dimensions (likely posters)
        var poster = element.select("img[width][height]").attr("src")
        
        // Priority 2: Images with common poster classes
        if (poster.isBlank()) {
            poster = element.select("img[class*='poster'], img[class*='movie']").attr("src")
        }
        
        // Priority 3: Any image in the element
        if (poster.isBlank()) {
            poster = element.select("img").attr("src")
        }
        
        // Convert to absolute URL if needed
        if (poster.isNotBlank() && !poster.startsWith("http")) {
            poster = if (poster.startsWith("/")) "$mainUrl$poster" else "$mainUrl/$poster"
        }
        
        return poster
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page > 1) "${request.data}page/$page/" else request.data
        val document = app.get(url, headers = cfHeaders).document
        
        val home = document.select("article, .post").mapNotNull { element ->
            val title = element.select(".title-2, h2, h3").text().trim()
            var href = element.select("a").attr("href")
            
            // Use enhanced poster extraction
            val poster = extractPosterUrl(element)
            
            // Fix href URL
            if (href.isNotBlank() && !href.startsWith("http")) {
                href = if (href.startsWith("/")) "$mainUrl$href" else "$mainUrl/$href"
            }
            
            if (title.isNotBlank() && href.isNotBlank() && !href.contains("/category/")) {
                newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = poster
                }
            } else null
        }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query", headers = cfHeaders).document
        
        return document.select("article, .post").mapNotNull { element ->
            val title = element.select(".title-2, h2, h3").text().trim()
            var href = element.select("a").attr("href")
            
            val poster = extractPosterUrl(element)
            
            if (href.isNotBlank() && !href.startsWith("http")) {
                href = if (href.startsWith("/")) "$mainUrl$href" else "$mainUrl/$href"
            }
            
            if (title.isNotBlank() && href.isNotBlank() && !href.contains("/category/")) {
                newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = poster
                }
            } else null
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = cfHeaders).document
        
        val title = document.selectFirst("h1")?.text()?.trim() ?: "Unknown"
        
        // Enhanced poster extraction for movie page
        val poster = extractPosterUrl(document)
        
        val description = document.selectFirst(".content, .entry-content")?.text()?.trim() ?: ""
        
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
        
        val mainDoc = app.get(data, headers = cfHeaders).document
        
        // METHOD 1: Extract iframes from server tabs
        mainDoc.select("#servers_tabs iframe, .single_tab iframe").forEach { iframe ->
            val iframeUrl = iframe.attr("data-src").ifBlank {
                iframe.attr("src")
            }
            if (iframeUrl.isNotBlank()) {
                foundLinks = true
                loadExtractor(iframeUrl, data, subtitleCallback, callback)
            }
        }
        
        // METHOD 2: Try all iframes on the page
        if (!foundLinks) {
            mainDoc.select("iframe").forEach { iframe ->
                val iframeUrl = iframe.attr("data-src").ifBlank {
                    iframe.attr("src")
                }
                if (iframeUrl.isNotBlank()) {
                    foundLinks = true
                    loadExtractor(iframeUrl, data, subtitleCallback, callback)
                }
            }
        }
        
        // METHOD 3: Try download links
        if (!foundLinks) {
            mainDoc.select("a.download_btn, a[href*='download']").forEach { link ->
                val url = link.attr("href")
                if (url.isNotBlank()) {
                    foundLinks = true
                    loadExtractor(url, data, subtitleCallback, callback)
                }
            }
        }
        
        // METHOD 4: GET parameters
        if (!foundLinks) {
            val workingParams = listOf(
                mapOf("view" to "1"),
                mapOf("load" to "video")
            )
            
            for (params in workingParams) {
                try {
                    val paramDoc = app.get(data, params = params, headers = cfHeaders).document
                    paramDoc.select("iframe").forEach { iframe ->
                        val iframeUrl = iframe.attr("data-src").ifBlank {
                            iframe.attr("src")
                        }
                        if (iframeUrl.isNotBlank()) {
                            foundLinks = true
                            loadExtractor(iframeUrl, data, subtitleCallback, callback)
                        }
                    }
                    if (foundLinks) break
                } catch (e: Exception) {
                    // Continue to next parameter
                }
            }
        }
        
        return foundLinks
    }
}
