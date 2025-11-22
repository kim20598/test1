package com.cineby

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import org.jsoup.nodes.Document
import org.jsoup.Jsoup
import java.net.URLEncoder

class Cineby : MainAPI() {
    override var mainUrl = "https://www.cineby.gd"
    override var name = "Cineby"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    // ==================== MAIN PAGE ====================
    
    override val mainPage = mainPageOf(
        "$mainUrl/movies?page=" to "Movies",
        "$mainUrl/tv-series?page=" to "TV Series",
        "$mainUrl/trending?page=" to "Trending",
        "$mainUrl/top-rated?page=" to "Top Rated",
        "$mainUrl/genre/action?page=" to "Action",
        "$mainUrl/genre/comedy?page=" to "Comedy",
        "$mainUrl/genre/horror?page=" to "Horror",
        "$mainUrl/genre/romance?page=" to "Romance",
        "$mainUrl/genre/sci-fi?page=" to "Sci-Fi"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data + page
        val document = app.get(url).document
        
        // Common selectors for movie/series cards
        val items = document.select(
            "div.movie-card, article.movie-item, div.film-poster, .content-item"
        ).mapNotNull { element ->
            element.toSearchResponse()
        }
        
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    // ==================== SEARCH ====================

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search?q=${URLEncoder.encode(query, "UTF-8")}"
        val document = app.get(searchUrl).document
        
        return document.select(
            "div.movie-card, article.movie-item, div.search-result, .content-item"
        ).mapNotNull { element ->
            element.toSearchResponse()
        }
    }

    // ==================== LOAD ====================

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        
        // Extract title
        val title = document.selectFirst(
            "h1.movie-title, h1.title, .movie-info h1, meta[property='og:title']"
        )?.let {
            if (it.tagName() == "meta") it.attr("content") else it.text()
        }?.trim() ?: "Unknown"

        // Extract poster
        val posterUrl = document.selectFirst(
            "img.movie-poster, .poster img, meta[property='og:image']"
        )?.let {
            if (it.tagName() == "meta") it.attr("content") else it.attr("src")
        } ?: ""

        // Extract description
        val plot = document.selectFirst(
            ".movie-description, .synopsis, .overview, p.plot"
        )?.text()?.trim()

        // Extract year
        val year = document.selectFirst(
            ".year, .release-year, span.date"
        )?.text()?.filter { it.isDigit() }?.toIntOrNull()

        // Extract genres/tags
        val tags = document.select(
            ".genres a, .genre-list a, .tags a"
        ).map { it.text().trim() }

        // Extract rating
        val ratingText = document.selectFirst(
            ".rating, .imdb-rating, .score"
        )?.text()

        // Check if it's a series or movie
        val isSeries = url.contains("/tv-series/") || 
                      url.contains("/show/") ||
                      document.select(".episode-list, .seasons-list").isNotEmpty()

        return if (isSeries) {
            // Extract episodes
            val episodes = mutableListOf<Episode>()
            
            // Check for season structure
            val seasons = document.select(".season-item, .season-tab, [data-season]")
            
            if (seasons.isNotEmpty()) {
                // Multiple seasons
                seasons.forEach { season ->
                    val seasonNum = season.attr("data-season")
                        .ifBlank { season.text() }
                        .filter { it.isDigit() }
                        .toIntOrNull() ?: 1
                    
                    // Get episodes for this season
                    val episodeContainer = when {
                        season.attr("data-season").isNotBlank() -> 
                            document.selectFirst("[data-season='${season.attr("data-season")}'] .episodes")
                        else -> season.selectFirst(".episodes")
                    } ?: season
                    
                    episodeContainer.select("a[href*='episode'], .episode-item").forEach { ep ->
                        val epUrl = fixUrl(ep.attr("href"))
                        val epNum = ep.text().filter { it.isDigit() }.toIntOrNull() ?: 0
                        val epTitle = ep.selectFirst(".episode-title")?.text() 
                            ?: "Episode $epNum"
                        
                        episodes.add(
                            newEpisode(epUrl) {
                                this.name = epTitle
                                this.season = seasonNum
                                this.episode = epNum
                            }
                        )
                    }
                }
            } else {
                // Single season or direct episode list
                document.select(
                    "a[href*='episode'], .episode-item, .episode-list a"
                ).forEach { ep ->
                    val epUrl = fixUrl(ep.attr("href"))
                    val epNum = ep.text().filter { it.isDigit() }.toIntOrNull() ?: 0
                    val epTitle = ep.selectFirst(".episode-title")?.text()
                        ?: ep.text().trim()
                        ?: "Episode $epNum"
                    
                    episodes.add(
                        newEpisode(epUrl) {
                            this.name = epTitle
                            this.season = 1
                            this.episode = epNum
                        }
                    )
                }
            }
            
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = fixUrl(posterUrl)
                this.plot = plot
                this.year = year
                this.tags = tags
            }
        } else {
            // Movie
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = fixUrl(posterUrl)
                this.plot = plot
                this.year = year
                this.tags = tags
            }
        }
    }

    // ==================== LOAD LINKS ====================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        var foundLinks = false

        // Method 1: Direct video elements
        document.select("video source, source[src]").forEach { source ->
            val videoUrl = source.attr("src")
            val quality = source.attr("res")
                ?.ifBlank { source.attr("label") }
                ?: "Default"
            
            if (videoUrl.isNotBlank()) {
                foundLinks = true
                callback(
                    newExtractorLink(
                        source = name,
                        name = "$name - $quality",
                        url = fixUrl(videoUrl),
                        type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = data
                        this.quality = getQualityFromString(quality)
                    }
                )
            }
        }

        // Method 2: iframes (embedded players)
        document.select("iframe").forEach { iframe ->
            val iframeSrc = iframe.attr("src")
            if (iframeSrc.isNotBlank() && !iframeSrc.contains("youtube")) {
                foundLinks = true
                loadExtractor(fixUrl(iframeSrc), data, subtitleCallback, callback)
            }
        }

        // Method 3: Server/source buttons
        document.select(
            "button[data-src], button[data-url], .server-item, a.server-link"
        ).forEach { serverElement ->
            val serverUrl = serverElement.attr("data-src")
                .ifBlank { serverElement.attr("data-url") }
                .ifBlank { serverElement.attr("data-embed") }
                .ifBlank { serverElement.attr("href") }
            
            val serverName = serverElement.text().trim()
            
            if (serverUrl.isNotBlank() && !serverUrl.startsWith("#")) {
                foundLinks = true
                
                // Check if it's a direct video or needs extraction
                when {
                    serverUrl.endsWith(".mp4") || serverUrl.endsWith(".m3u8") -> {
                        callback(
                            newExtractorLink(
                                source = name,
                                name = serverName.ifBlank { name },
                                url = fixUrl(serverUrl),
                                type = if (serverUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                this.referer = data
                                this.quality = Qualities.Unknown.value
                            }
                        )
                    }
                    else -> {
                        loadExtractor(fixUrl(serverUrl), data, subtitleCallback, callback)
                    }
                }
            }
        }

        // Method 4: AJAX server loading
        document.select("[data-server-id], [data-episode-id]").forEach { element ->
            val serverId = element.attr("data-server-id")
            val episodeId = element.attr("data-episode-id")
                .ifBlank { data.substringAfterLast("/").substringBefore("?") }
            
            if (serverId.isNotBlank()) {
                try {
                    val ajaxUrl = "$mainUrl/ajax/server/$serverId/$episodeId"
                    val response = app.get(
                        ajaxUrl,
                        headers = mapOf(
                            "X-Requested-With" to "XMLHttpRequest",
                            "Referer" to data
                        )
                    ).text
                    
                    // Parse JSON or HTML response
                    if (response.contains("url") || response.contains("src")) {
                        // Extract URL from response
                        val urlPattern = Regex("""(?:url|src)["']?\s*[:=]\s*["']([^"']+)["']""")
                        urlPattern.find(response)?.groupValues?.get(1)?.let { extractedUrl ->
                            foundLinks = true
                            loadExtractor(fixUrl(extractedUrl), data, subtitleCallback, callback)
                        }
                    }
                } catch (e: Exception) {
                    // Continue to next server
                }
            }
        }

        // Method 5: Extract subtitles
        document.select("track[kind='captions'], track[kind='subtitles']").forEach { track ->
            val src = track.attr("src")
            val label = track.attr("label") ?: "Unknown"
            val lang = track.attr("srclang") ?: "en"
            
            if (src.isNotBlank()) {
                subtitleCallback(
                    SubtitleFile(
                        lang = "$label ($lang)",
                        url = fixUrl(src)
                    )
                )
            }
        }

        // Method 6: Check scripts for video URLs
        document.select("script").forEach { script ->
            val scriptContent = script.html()
            
            // Common patterns for video URLs in JavaScript
            val patterns = listOf(
                Regex("""(?:source|src|file|videoUrl)["']?\s*[:=]\s*["']([^"']+\.(?:mp4|m3u8|mkv))["']"""),
                Regex("""(?:embed|iframe)\.src\s*=\s*["']([^"']+)["']"""),
                Regex("""sources\s*:\s*\[\s*\{[^}]*url["']?\s*:\s*["']([^"']+)["']""")
            )
            
            patterns.forEach { pattern ->
                pattern.findAll(scriptContent).forEach { match ->
                    val url = match.groupValues[1]
                    if (url.isNotBlank() && url.startsWith("http")) {
                        foundLinks = true
                        when {
                            url.endsWith(".mp4") || url.endsWith(".m3u8") -> {
                                callback(
                                    newExtractorLink(
                                        source = name,
                                        name = name,
                                        url = url,
                                        type = if (url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                    ) {
                                        this.referer = data
                                        this.quality = Qualities.Unknown.value
                                    }
                                )
                            }
                            else -> {
                                loadExtractor(url, data, subtitleCallback, callback)
                            }
                        }
                    }
                }
            }
        }

        return foundLinks
    }

    // ==================== HELPER FUNCTIONS ====================

    private fun Element.toSearchResponse(): SearchResponse? {
        // Extract title
        val title = this.selectFirst(
            "h3, h2, .movie-title, .title, a[title]"
        )?.let { element ->
            element.attr("title").ifBlank { element.text() }
        }?.trim() ?: return null
        
        // Extract URL
        val href = this.selectFirst("a")?.attr("href") ?: return null
        
        // Extract poster
        val posterUrl = this.selectFirst("img")?.let { img ->
            img.attr("data-src")
                .ifBlank { img.attr("data-lazy-src") }
                .ifBlank { img.attr("src") }
        } ?: ""
        
        // Extract quality if available
        val quality = this.selectFirst(".quality, .resolution")?.text()
        
        // Determine type
        val type = when {
            href.contains("/tv-series/") || href.contains("/show/") -> TvType.TvSeries
            else -> TvType.Movie
        }
        
        return newMovieSearchResponse(title, fixUrl(href), type) {
            this.posterUrl = fixUrl(posterUrl)
            quality?.let {
                this.quality = when {
                    it.contains("1080") -> SearchQuality.HD
                    it.contains("720") -> SearchQuality.HD
                    it.contains("480") -> SearchQuality.SD
                    else -> null
                }
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

    private fun getQualityFromString(quality: String): Int {
        return when {
            quality.contains("4K") || quality.contains("2160") -> Qualities.P2160.value
            quality.contains("1440") -> Qualities.P1440.value
            quality.contains("1080") -> Qualities.P1080.value
            quality.contains("720") -> Qualities.P720.value
            quality.contains("480") -> Qualities.P480.value
            quality.contains("360") -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    // Data class for AJAX responses
    data class ServerResponse(
        val url: String? = null,
        val embed: String? = null,
        val sources: List<Source>? = null
    )

    data class Source(
        val file: String? = null,
        val label: String? = null,
        val type: String? = null
    )
}