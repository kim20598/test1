package com.animezid

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

class Animezid : MainAPI() {
    override var lang = "ar"
    override var mainUrl = "https://animezid.cam"
    override var name = "Animezid"
    override val usesWebView = false
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Anime, TvType.Movie)

    // ==================== MAIN PAGE ====================
    
    override val mainPage = mainPageOf(
        "$mainUrl/" to "أحدث الإضافات",
        "$mainUrl/anime/" to "أنمي", 
        "$mainUrl/movies/" to "أفلام أنمي"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document
        val items = document.select("a.movie").mapNotNull { it.toSearchResponse() }
        return newHomePageResponse(request.name, items, hasNext = false)
    }

    // ==================== SEARCH ====================

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search.php?keywords=${URLEncoder.encode(query, "UTF-8")}"
        val document = app.get(searchUrl).document
        return document.select("a.movie").mapNotNull { it.toSearchResponse() }
    }

    // ==================== LOAD ====================

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        
        val title = document.selectFirst("h1 span[itemprop=name]")?.text() 
            ?: document.selectFirst("meta[itemprop=name]")?.attr("content")
            ?: document.selectFirst("h1 strong")?.text()
            ?: document.selectFirst("h1")?.text() 
            ?: throw ErrorLoadingException("No title found")

        val poster = document.selectFirst("meta[itemprop=image]")?.attr("content")
            ?: document.selectFirst("meta[itemprop=thumbnailUrl]")?.attr("content")
            ?: document.selectFirst("img.lazy")?.attr("data-src")
            ?: ""
            
        val description = document.selectFirst(".pm-video-description")?.text()?.trim() ?: ""
        val year = document.selectFirst("a[href*='filter=years']")?.text()?.toIntOrNull()

        // Check if it's a movie or series
        val hasEpisodes = document.select(".SeasonsEpisodes a").isNotEmpty()
        
        return if (hasEpisodes) {
            // TV Series
            val episodes = document.select(".SeasonsEpisodes a").mapNotNull { episodeElement ->
                val episodeUrl = fixUrl(episodeElement.attr("href"))
                val episodeNum = episodeElement.select("em").text().toIntOrNull() ?: return@mapNotNull null
                val episodeTitle = episodeElement.select("span").text().takeIf { it.isNotBlank() } ?: "الحلقة $episodeNum"
                
                newEpisode(episodeUrl) {
                    this.name = episodeTitle
                    this.episode = episodeNum
                    this.season = 1
                }
            }.distinctBy { it.episode }

            newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                this.posterUrl = fixUrl(poster)
                this.plot = description
                this.year = year
            }
        } else {
            // Movie
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = fixUrl(poster)
                this.plot = description
                this.year = year
            }
        }
    }

    // ==================== LOAD LINKS - WORKING VERSION ====================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        var foundLinks = false

        // METHOD 1: Extract from server buttons (#xservers button)
        document.select("#xservers button[data-embed]").forEach { serverButton ->
            val embedUrl = serverButton.attr("data-embed").trim()
            val serverName = serverButton.text().ifBlank { "Server" }
            
            if (embedUrl.isNotBlank()) {
                // These are direct embed URLs, send them to extractors
                loadExtractor(embedUrl, data, subtitleCallback, callback)
                foundLinks = true
            }
        }

        // METHOD 2: Get the iframe that's already loaded in Playerholder
        document.selectFirst("#Playerholder iframe")?.let { iframe ->
            val iframeSrc = iframe.attr("src").trim()
            if (iframeSrc.isNotBlank()) {
                loadExtractor(iframeSrc, data, subtitleCallback, callback)
                foundLinks = true
            }
        }

        // METHOD 3: Extract download links
        document.select("a.dl.show_dl.api[href]").forEach { downloadLink ->
            val downloadUrl = downloadLink.attr("href").trim()
            val quality = downloadLink.select("span").firstOrNull()?.text() ?: "1080p"
            val host = downloadLink.select("span").getOrNull(1)?.text() 
                ?: downloadLink.text().split(" ").lastOrNull() 
                ?: "Download"
            
            if (downloadUrl.isNotBlank() && downloadUrl.startsWith("http")) {
                // These are file hosting sites, try to extract from them
                // Most of these need special handling but we can try
                loadExtractor(downloadUrl, data, subtitleCallback, callback)
                foundLinks = true
            }
        }

        // METHOD 4: Try to get video from embed URL if exists
        document.selectFirst("meta[itemprop=embedURL]")?.attr("content")?.let { embedUrl ->
            if (embedUrl.isNotBlank() && !foundLinks) {
                // This is the fallback embed URL
                val embedDoc = app.get(embedUrl).document
                
                // Look for video sources in the embed page
                embedDoc.select("video source, source").forEach { source ->
                    val videoUrl = source.attr("src")
                    if (videoUrl.isNotBlank()) {
                        callback(
                            newExtractorLink(
                                source = name,
                                name = name,
                                url = fixUrl(videoUrl),
                                type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                this.referer = embedUrl
                                this.quality = Qualities.Unknown.value
                            }
                        )
                        foundLinks = true
                    }
                }
                
                // Also check for iframes in embed page
                embedDoc.select("iframe").forEach { iframe ->
                    val iframeSrc = iframe.attr("src")
                    if (iframeSrc.isNotBlank()) {
                        loadExtractor(fixUrl(iframeSrc), embedUrl, subtitleCallback, callback)
                        foundLinks = true
                    }
                }
            }
        }

        return foundLinks
    }

    // ==================== HELPER FUNCTIONS ====================

    private fun Element.toSearchResponse(): SearchResponse? {
        val title = this.attr("title").takeIf { it.isNotBlank() } 
            ?: this.select(".title").text().takeIf { it.isNotBlank() }
            ?: return null
            
        val href = this.attr("href").takeIf { it.isNotBlank() } ?: return null
        val poster = this.select("img.lazy").attr("data-src")
            .ifBlank { this.select("img").attr("src") }
        
        val isMovie = title.contains("فيلم") || href.contains("/movie/")

        return if (isMovie) {
            newMovieSearchResponse(title, fixUrl(href), TvType.Movie) {
                this.posterUrl = fixUrl(poster)
            }
        } else {
            newTvSeriesSearchResponse(title, fixUrl(href), TvType.Anime) {
                this.posterUrl = fixUrl(poster)
            }
        }
    }

    private fun fixUrl(url: String): String {
        return when {
            url.isBlank() -> ""
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$mainUrl$url"
            else -> "$mainUrl/$url"
        }
    }
}
