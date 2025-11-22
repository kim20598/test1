package com.animezid

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import org.jsoup.Jsoup
import java.net.URLEncoder
import android.util.Log

class Animezid : MainAPI() {
    override var lang = "ar"
    override var mainUrl = "https://animezid.cam"
    override var name = "Animezid"
    override val usesWebView = false
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Anime, TvType.Movie)
    
    companion object {
        const val TAG = "Animezid"
    }

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
            ?: document.selectFirst("h1")?.text() 
            ?: throw ErrorLoadingException("No title found")

        val poster = document.selectFirst("img.lazy")?.attr("data-src")?.let { fixUrl(it) } ?: ""
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
                this.posterUrl = poster
                this.plot = description
                this.year = year
            }
        } else {
            // Movie
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
            }
        }
    }

    // ==================== LOAD LINKS - COMPLETELY REWRITTEN ====================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d(TAG, "Loading links from: $data")
        
        val document = app.get(data).document
        var foundLinks = false

        // Debug: Log the page structure
        Log.d(TAG, "Page title: ${document.title()}")
        
        // METHOD 1: Look for ALL server buttons (different selectors)
        val serverSelectors = listOf(
            "#xservers button[data-embed]",
            ".server-btn[data-embed]",
            "button[data-embed]",
            "[data-server]",
            ".servers-list button",
            ".watch-servers button"
        )
        
        for (selector in serverSelectors) {
            val servers = document.select(selector)
            if (servers.isNotEmpty()) {
                Log.d(TAG, "Found ${servers.size} servers with selector: $selector")
                
                servers.forEach { serverButton ->
                    val embedData = serverButton.attr("data-embed")
                        .ifBlank { serverButton.attr("data-server") }
                        .ifBlank { serverButton.attr("data-url") }
                        .ifBlank { serverButton.attr("data-src") }
                    
                    val serverName = serverButton.text().ifBlank { "Server" }
                    
                    Log.d(TAG, "Server '$serverName' embed data: ${embedData.take(100)}")
                    
                    if (embedData.isNotBlank()) {
                        // Check if it's HTML embed code
                        if (embedData.trim().startsWith("<")) {
                            Log.d(TAG, "Parsing HTML embed for server: $serverName")
                            val embedDoc = Jsoup.parse(embedData)
                            
                            // Extract iframes from HTML
                            embedDoc.select("iframe").forEach { iframe ->
                                val iframeSrc = iframe.attr("src")
                                    .ifBlank { iframe.attr("data-src") }
                                
                                if (iframeSrc.isNotBlank()) {
                                    Log.d(TAG, "Found iframe URL: $iframeSrc")
                                    val fixedUrl = fixUrl(iframeSrc)
                                    loadExtractor(fixedUrl, data, subtitleCallback, callback)
                                    foundLinks = true
                                }
                            }
                            
                            // Also check for direct video tags in HTML
                            embedDoc.select("video source, source").forEach { source ->
                                val videoUrl = source.attr("src")
                                if (videoUrl.isNotBlank()) {
                                    Log.d(TAG, "Found direct video in embed: $videoUrl")
                                    callback(
                                        newExtractorLink(
                                            source = name,
                                            name = serverName,
                                            url = fixUrl(videoUrl),
                                            type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                        ) {
                                            this.referer = data
                                            this.quality = Qualities.Unknown.value
                                        }
                                    )
                                    foundLinks = true
                                }
                            }
                        } else if (embedData.startsWith("http") || embedData.startsWith("//")) {
                            // It's a direct URL
                            Log.d(TAG, "Direct URL from server: $embedData")
                            val fixedUrl = fixUrl(embedData)
                            loadExtractor(fixedUrl, data, subtitleCallback, callback)
                            foundLinks = true
                        }
                    }
                }
            }
        }

        // METHOD 2: Check the current player holder
        val playerSelectors = listOf(
            "#Playerholder iframe",
            "#player iframe",
            ".player-container iframe",
            ".video-container iframe",
            "iframe[allowfullscreen]"
        )
        
        for (selector in playerSelectors) {
            document.select(selector).forEach { iframe ->
                val iframeSrc = iframe.attr("src").ifBlank { iframe.attr("data-src") }
                if (iframeSrc.isNotBlank()) {
                    Log.d(TAG, "Found player iframe: $iframeSrc")
                    loadExtractor(fixUrl(iframeSrc), data, subtitleCallback, callback)
                    foundLinks = true
                }
            }
        }

        // METHOD 3: Look for download buttons/links
        val downloadSelectors = listOf(
            "a.dl.show_dl",
            "a[href*='download']",
            ".download-btn",
            "a.btn-download",
            ".downloads-list a",
            "a[download]"
        )
        
        for (selector in downloadSelectors) {
            document.select(selector).forEach { downloadLink ->
                val downloadUrl = downloadLink.attr("href")
                if (downloadUrl.isNotBlank() && !downloadUrl.startsWith("javascript")) {
                    val quality = downloadLink.select("span").firstOrNull()?.text() 
                        ?: downloadLink.text()
                        ?: "Unknown"
                    
                    Log.d(TAG, "Found download link: $downloadUrl - Quality: $quality")
                    
                    if (downloadUrl.endsWith(".mp4") || downloadUrl.endsWith(".mkv") || 
                        downloadUrl.endsWith(".m3u8") || downloadUrl.contains("video")) {
                        callback(
                            newExtractorLink(
                                source = name,
                                name = "Download - $quality",
                                url = fixUrl(downloadUrl),
                                type = if (downloadUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                this.referer = data
                                this.quality = extractQuality(quality)
                            }
                        )
                        foundLinks = true
                    } else {
                        // It might be a redirect link, try to load it
                        loadExtractor(fixUrl(downloadUrl), data, subtitleCallback, callback)
                        foundLinks = true
                    }
                }
            }
        }

        // METHOD 4: Check JavaScript for video URLs
        document.select("script").forEach { script ->
            val scriptContent = script.html()
            
            // Common patterns for video URLs in JavaScript
            val patterns = listOf(
                Regex("""(?:source|src|file|url)["']?\s*[:=]\s*["']([^"']+\.(?:mp4|m3u8|mkv))["']"""),
                Regex("""iframe\.src\s*=\s*["']([^"']+)["']"""),
                Regex("""player\.load\(['"]([^'"]+)['"]"""),
                Regex("""videoUrl["']?\s*[:=]\s*["']([^"']+)["']""")
            )
            
            patterns.forEach { pattern ->
                pattern.findAll(scriptContent).forEach { match ->
                    val url = match.groupValues[1]
                    if (url.isNotBlank() && !url.contains("example.com")) {
                        Log.d(TAG, "Found URL in script: $url")
                        if (url.endsWith(".mp4") || url.endsWith(".m3u8") || url.endsWith(".mkv")) {
                            callback(
                                newExtractorLink(
                                    source = name,
                                    name = name,
                                    url = fixUrl(url),
                                    type = if (url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = data
                                    this.quality = Qualities.Unknown.value
                                }
                            )
                            foundLinks = true
                        } else {
                            loadExtractor(fixUrl(url), data, subtitleCallback, callback)
                            foundLinks = true
                        }
                    }
                }
            }
        }

        // METHOD 5: Try AJAX endpoints if nothing found
        if (!foundLinks) {
            Log.d(TAG, "No direct links found, trying AJAX methods...")
            
            // Common AJAX patterns
            val videoId = Regex("""(?:video|id)[/_-](\d+)""").find(data)?.groupValues?.get(1)
            if (videoId != null) {
                val ajaxUrls = listOf(
                    "$mainUrl/ajax/video/$videoId",
                    "$mainUrl/player.php?id=$videoId",
                    "$mainUrl/embed.php?id=$videoId",
                    "$mainUrl/watch.php?v=$videoId"
                )
                
                for (ajaxUrl in ajaxUrls) {
                    try {
                        Log.d(TAG, "Trying AJAX URL: $ajaxUrl")
                        val response = app.get(
                            ajaxUrl,
                            headers = mapOf(
                                "X-Requested-With" to "XMLHttpRequest",
                                "Referer" to data
                            )
                        ).text
                        
                        // Try to extract URLs from response
                        if (response.contains("http")) {
                            Regex("""(https?://[^\s"'<>]+)""").findAll(response).forEach { match ->
                                val url = match.value
                                if (url.contains("video") || url.contains("stream") || 
                                    url.endsWith(".mp4") || url.endsWith(".m3u8")) {
                                    Log.d(TAG, "Found URL in AJAX response: $url")
                                    loadExtractor(url, data, subtitleCallback, callback)
                                    foundLinks = true
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "AJAX request failed: ${e.message}")
                    }
                }
            }
        }

        Log.d(TAG, "Link extraction complete. Found links: $foundLinks")
        return foundLinks
    }

    // ==================== HELPER FUNCTIONS ====================

    private fun Element.toSearchResponse(): SearchResponse? {
        val title = this.attr("title").takeIf { it.isNotBlank() } 
            ?: this.select(".title").text().takeIf { it.isNotBlank() }
            ?: return null
            
        val href = this.attr("href").takeIf { it.isNotBlank() } ?: return null
        val poster = this.select("img.lazy").attr("data-src")
        
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
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$mainUrl$url"
            else -> "$mainUrl/$url"
        }
    }

    private fun extractQuality(quality: String): Int {
        return when {
            quality.contains("1080") -> Qualities.P1080.value
            quality.contains("720") -> Qualities.P720.value
            quality.contains("480") -> Qualities.P480.value
            quality.contains("360") -> Qualities.P360.value
            quality.contains("240") -> Qualities.P240.value
            else -> Qualities.Unknown.value
        }
    }
}
