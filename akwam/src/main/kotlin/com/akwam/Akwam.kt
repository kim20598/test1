package com.akwam

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class Akwam : MainAPI() {
    override var mainUrl = "https://ak.sv"
    override var name = "Akwam"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    
    private fun String.toAbsolute(): String {
        return when {
            this.startsWith("http") -> this
            this.startsWith("//") -> "https:$this"
            else -> "$mainUrl${if (this.startsWith("/")) "" else "/"}$this"
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?q=${query.replace(" ", "+")}"
        val document = app.get(url).document
        
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
        val document = app.get(url).document
        
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
        val document = app.get(url).document
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
        val doc = app.get(data).document

        // Extract all quality links using the REAL structure we found
        val links = doc.select("div.tab-content.quality").map { element ->
            val qualityId = element.attr("id").substringAfter("tab-").toIntOrNull()
            val quality = getQualityFromId(qualityId)
            
            element.select("a.link-btn").map { linkElement ->
                val href = linkElement.attr("href").toAbsolute()
                val isWatch = linkElement.hasClass("link-show")
                val isDownload = linkElement.hasClass("link-download")
                
                if (isWatch || isDownload) {
                    Pair(href, quality)
                } else {
                    null
                }
            }.filterNotNull()
        }.flatten()

        // Process each link
        links.forEach { (url, quality) ->
            if (url.contains("/watch/")) {
                // For watch links, extract directly
                loadExtractor(url, data, subtitleCallback, callback)
            } else if (url.contains("/link/")) {
                // For download links, follow to get final URL
                loadExtractor(url, data, subtitleCallback, callback)
            }
        }
        
        return true
    }

    private fun getQualityFromId(id: Int?): Qualities {
        return when (id) {
            5 -> Qualities.P1080  // tab-5 = 1080p
            4 -> Qualities.P720   // tab-4 = 720p  
            3 -> Qualities.P480   // tab-3 = 480p
            2 -> Qualities.P360   // tab-2 = 360p
            else -> Qualities.Unknown
        }
    }
}
