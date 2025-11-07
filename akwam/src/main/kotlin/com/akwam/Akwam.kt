package com.akwam

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import android.util.Log

class Akwam : MainAPI() {
    override var mainUrl = "https://ak.sv"
    override var name = "Akwam"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    
    private fun String.toAbsolute(): String {
        if (this.isBlank()) return ""
        return when {
            this.startsWith("http") -> this
            this.startsWith("//") -> "https:$this"
            else -> mainUrl.trimEnd('/') + this
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

        // Check if it's a series by looking for episodes
        val episodes = document.select("div.episode-list a, .episodes-list a").mapNotNull { episode ->
            val episodeUrl = episode.attr("href").toAbsolute()
            val episodeTitle = episode.text().trim()
            val episodeNum = episodeTitle.getIntFromText()
            
            newEpisode(episodeUrl) {
                name = episodeTitle
                episode = episodeNum
                posterUrl = poster
            }
        }

        val isTvSeries = episodes.isNotEmpty() || url.contains("/series/")

        return if (isTvSeries) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
            }
        }
    }

    private fun String.getIntFromText(): Int? {
        return Regex("""(\d+)""").find(this)?.groupValues?.get(1)?.toIntOrNull()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.i(name, "loadLinks initiated with data: $data")

        val document = app.get(data).document
        
        // Method 1: Look for download buttons with quality tabs (Akwam specific)
        document.select("div.tab-content.quality").forEach { qualityElement ->
            val qualityId = qualityElement.attr("id").getIntFromText()
            val quality = when (qualityId) {
                2 -> Qualities.P360
                3 -> Qualities.P480  
                4 -> Qualities.P720
                5 -> Qualities.P1080
                else -> Qualities.Unknown
            }
            
            qualityElement.select("a:contains(تحميل)").forEach { downloadLink ->
                val downloadUrl = downloadLink.attr("href").toAbsolute()
                if (downloadUrl.contains("/download/")) {
                    extractDownloadLink(downloadUrl, quality, data, subtitleCallback, callback)
                }
            }
        }
        
        // Method 2: Direct iframe extraction
        document.select("iframe[src]").forEach { iframe ->
            val iframeUrl = iframe.attr("src").toAbsolute()
            if (iframeUrl.isNotBlank()) {
                loadExtractor(iframeUrl, data, subtitleCallback, callback)
            }
        }
        
        // Method 3: Watch buttons
        document.select("a.watch-btn, a.btn-watch").forEach { watchBtn ->
            val watchUrl = watchBtn.attr("href").toAbsolute()
            if (watchUrl.isNotBlank()) {
                loadExtractor(watchUrl, data, subtitleCallback, callback)
            }
        }

        return true
    }

    private suspend fun extractDownloadLink(
        downloadUrl: String,
        quality: Qualities,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val downloadDoc = app.get(downloadUrl, referer = referer).document
            val videoLink = downloadDoc.select("div.btn-loader a, a.download-link").attr("href").toAbsolute()
            
            if (videoLink.isNotBlank()) {
                loadExtractor(videoLink, downloadUrl, subtitleCallback, callback)
            }
        } catch (e: Exception) {
            Log.e(name, "Error extracting download link: $downloadUrl", e)
        }
    }
}
