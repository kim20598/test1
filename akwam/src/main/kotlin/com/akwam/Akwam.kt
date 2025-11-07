package com.akwam

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup

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
        val document = app.get(data).document
        
        // Method 1: Direct video links - simplified without ExtractorLink
        document.select("source[src]").forEach { source ->
            val videoUrl = source.attr("src").toAbsolute()
            if (videoUrl.isNotBlank() && (videoUrl.contains(".mp4") || videoUrl.contains(".m3u8"))) {
                // Let CloudStream handle the link creation
                loadExtractor(videoUrl, data, subtitleCallback, callback)
            }
        }
        
        // Method 2: Iframe extraction
        document.select("iframe[src]").forEach { iframe ->
            val iframeUrl = iframe.attr("src").toAbsolute()
            if (iframeUrl.isNotBlank()) {
                loadExtractor(iframeUrl, data, subtitleCallback, callback)
            }
        }
        
        // Method 3: Direct download buttons
        document.select("a[href*='.mp4'], a[href*='.m3u8'], a[href*='video']").forEach { link ->
            val videoUrl = link.attr("href").toAbsolute()
            if (videoUrl.isNotBlank() && (videoUrl.contains(".mp4") || videoUrl.contains(".m3u8"))) {
                loadExtractor(videoUrl, data, subtitleCallback, callback)
            }
        }
        
        return true
    }
}
