package com.akwam

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.network.CloudflareKiller

class Akwam : MainAPI() {
    override var mainUrl = "https://ak.sv"
    override var name = "Akwam"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    
    // Add Cloudflare bypass
    private val cloudflareKiller = CloudflareKiller()
    
    private fun String.toAbsolute(): String {
        return when {
            this.startsWith("http") -> this
            this.startsWith("//") -> "https:$this"
            else -> "$mainUrl${if (this.startsWith("/")) "" else "/"}$this"
        }
    }

    private fun String.getIntFromText(): Int? {
        return Regex("""\d+""").find(this)?.groupValues?.firstOrNull()?.toIntOrNull()
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?q=${query.replace(" ", "+")}"
        val document = app.get(url, cloudflare = true).document // Add cloudflare bypass
        
        return document.select("div.entry-box").mapNotNull {
            val title = it.selectFirst("h3.entry-title a")?.text() ?: return@mapNotNull null
            val href = it.selectFirst("h3.entry-title a")?.attr("href")?.toAbsolute() ?: return@mapNotNull null
            val poster = it.selectFirst("img")?.attr("data-src")?.toAbsolute()
            val type = if (href.contains("/movie/")) TvType.Movie else TvType.TvSeries
            
            newMovieSearchResponse(title, href, type) {
                this.posterUrl = poster
            }
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/movies" to "أفلام",
        "$mainUrl/series" to "مسلسلات", 
        "$mainUrl/anime" to "أنمي"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}/page/$page"
        val document = app.get(url, cloudflare = true).document // Add cloudflare bypass
        
        val items = document.select("div.entry-box").mapNotNull {
            val title = it.selectFirst("h3.entry-title a")?.text() ?: return@mapNotNull null
            val href = it.selectFirst("h3.entry-title a")?.attr("href")?.toAbsolute() ?: return@mapNotNull null
            val poster = it.selectFirst("img")?.attr("data-src")?.toAbsolute()
            
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
            }
        }
        
        return newHomePageResponse(request.name, items)
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, cloudflare = true).document // Add cloudflare bypass
        val title = document.selectFirst("h1.entry-title")?.text() ?: "غير معروف"
        val poster = document.selectFirst(".poster img")?.attr("src")?.toAbsolute()
        val plot = document.selectFirst(".story p")?.text()

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data, cloudflare = true).document // Add cloudflare bypass

        // Extract all quality links using proven selectors
        val links = doc.select("div.tab-content.quality").map { element ->
            val quality = getQualityFromId(element.attr("id").getIntFromText())
            element.select(".col-lg-6 > a:contains(تحميل)").map { linkElement ->
                if (linkElement.attr("href").contains("/download/")) {
                    Pair(linkElement.attr("href").toAbsolute(), quality)
                } else {
                    // Transform URL for short links
                    val url = "$mainUrl/download${
                        linkElement.attr("href").split("/link")[1]
                    }${data.split("/movie|/episode".toRegex())[1]}".toAbsolute()
                    Pair(url, quality)
                }
            }
        }.flatten()

        // Process each download link using proven pattern
        links.forEach { (url, quality) ->
            val linkDoc = app.get(url, cloudflare = true).document // Add cloudflare bypass
            val button = linkDoc.select("div.btn-loader > a")
            val finalUrl = button.attr("href").toAbsolute()

            // Use loadExtractor for safe link handling
            loadExtractor(finalUrl, data, subtitleCallback, callback)
        }
        
        return true
    }

    private fun getQualityFromId(id: Int?): Qualities {
        return when (id) {
            2 -> Qualities.P360
            3 -> Qualities.P480
            4 -> Qualities.P720
            5 -> Qualities.P1080
            else -> Qualities.Unknown
        }
    }
}
