package com.cineby

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
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
        "" to "Home", // Home page with mixed content
        "movies" to "Movies",
        "tv" to "TV Shows",
        "movies?sort=trending" to "Trending Movies",
        "movies?sort=popular" to "Popular Movies",
        "tv?sort=trending" to "Trending TV",
        "tv?sort=popular" to "Popular TV"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (request.data.isEmpty()) {
            mainUrl
        } else {
            "$mainUrl/${request.data}"
        }
        
        val document = app.get(url).document
        val items = mutableListOf<SearchResponse>()
        
        // Try multiple possible selectors for movie/TV cards
        val selectors = listOf(
            ".movie-card", ".tv-card", ".card", 
            ".item", ".poster", ".grid-item",
            "a[href*=/movie/]", "a[href*=/tv/]",
            ".poster-container", ".media-item"
        )
        
        selectors.forEach { selector ->
            document.select(selector).forEach { element ->
                element.toSearchResponse()?.let { items.add(it) }
            }
        }
        
        return newHomePageResponse(request.name, items.distinctBy { it.url }, hasNext = items.isNotEmpty())
    }

    // ==================== SEARCH ====================

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search?q=${URLEncoder.encode(query, "UTF-8")}"
        val document = app.get(searchUrl).document
        val items = mutableListOf<SearchResponse>()
        
        // Try multiple selectors for search results
        val selectors = listOf(
            ".search-result", ".result-item", ".card",
            ".movie-card", ".tv-card", ".item",
            "a[href*=/movie/]", "a[href*=/tv/]"
        )
        
        selectors.forEach { selector ->
            document.select(selector).forEach { element ->
                element.toSearchResponse()?.let { items.add(it) }
            }
        }
        
        return items.distinctBy { it.url }
    }

    // ==================== LOAD ====================

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        
        // Extract title - try multiple selectors
        val title = document.selectFirst("h1, .title, .movie-title, .tv-title, h2")?.text() ?: "Unknown"
        
        // Extract poster - try multiple selectors
        val poster = document.selectFirst("img.poster, .poster img, .movie-poster img, .tv-poster img, [class*='poster'] img")?.attr("src")
        val backdrop = document.selectFirst("img.backdrop, .backdrop img, .background-image, [style*='background']")?.attr("src") ?: poster
        
        // Extract description - try multiple selectors
        val description = document.selectFirst(".overview, .description, .plot, .synopsis, p")?.text()
        
        // Extract year - look in various places
        val yearText = document.selectFirst(".year, .release-date, .date, [class*='date']")?.text()
        val year = yearText?.findYear() ?: document.wholeText().findYear()
        
        // Determine type from URL or content
        val type = when {
            url.contains("/tv/") || document.select(".season, .episode").isNotEmpty() -> TvType.TvSeries
            else -> TvType.Movie
        }
        
        if (type == TvType.TvSeries) {
            val episodes = loadTvSeriesEpisodes(document, url)
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = fixUrl(poster)
                this.backgroundPosterUrl = fixUrl(backdrop)
                this.plot = description
                this.year = year
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = fixUrl(poster)
                this.backgroundPosterUrl = fixUrl(backdrop)
                this.plot = description
                this.year = year
            }
        }
    }

    private suspend fun loadTvSeriesEpisodes(document: Element, baseUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        // Try to find seasons and episodes
        val seasonElements = document.select(".season, [class*='season'], .season-list, .seasons")
        
        if (seasonElements.isNotEmpty()) {
            // Has structured seasons
            seasonElements.forEachIndexed { seasonIndex, seasonElement ->
                val seasonNum = seasonIndex + 1
                val episodeElements = seasonElement.select(".episode, [class*='episode'], .episode-list")
                
                episodeElements.forEachIndexed { episodeIndex, episodeElement ->
                    val episodeNum = episodeIndex + 1
                    val episodeTitle = episodeElement.select(".episode-title, .title, h3, h4").text()
                    val episodeUrl = episodeElement.select("a").attr("href") ?: "$baseUrl/season/$seasonNum/episode/$episodeNum"
                    
                    episodes.add(
                        newEpisode(fixUrl(episodeUrl)) {
                            this.name = if (episodeTitle.isNotBlank()) episodeTitle else "Episode $episodeNum"
                            this.season = seasonNum
                            this.episode = episodeNum
                        }
                    )
                }
            }
        } else {
            // Create placeholder episodes if no structure found
            for (season in 1..1) {
                for (episode in 1..10) {
                    episodes.add(
                        newEpisode("$baseUrl/season/$season/episode/$episode") {
                            this.name = "Episode $episode"
                            this.season = season
                            this.episode = episode
                        }
                    )
                }
            }
        }
        
        return episodes
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
        
        // Method 1: Look for video elements
        document.select("video").forEach { video ->
            video.select("source").forEach { source ->
                val videoUrl = source.attr("src")
                if (videoUrl.isNotBlank()) {
                    foundLinks = true
                    callback(
                        newExtractorLink(
                            source = name,
                            name = "Direct Video",
                            url = fixUrl(videoUrl),
                            type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = mainUrl
                        }
                    )
                }
            }
        }
        
        // Method 2: Look for iframes
        document.select("iframe").forEach { iframe ->
            val iframeSrc = iframe.attr("src")
            if (iframeSrc.isNotBlank() && !iframeSrc.contains("youtube")) {
                foundLinks = true
                loadExtractor(fixUrl(iframeSrc), mainUrl, subtitleCallback, callback)
            }
        }
        
        // Method 3: Look for data attributes with video URLs
        document.select("[data-src], [data-url], [data-video]").forEach { element ->
            val videoUrl = element.attr("data-src") ?: element.attr("data-url") ?: element.attr("data-video")
            if (videoUrl.isNotBlank()) {
                foundLinks = true
                loadExtractor(fixUrl(videoUrl), mainUrl, subtitleCallback, callback)
            }
        }
        
        // Method 4: Look for script tags with video URLs
        document.select("script").forEach { script ->
            val scriptContent = script.html()
            // Look for common video URL patterns in scripts
            val videoPatterns = listOf(
                Regex("""src\s*:\s*["']([^"']*\.(?:mp4|m3u8|mkv|avi)[^"']*)["']"""),
                Regex("""file\s*:\s*["']([^"']*\.(?:mp4|m3u8|mkv|avi)[^"']*)["']"""),
                Regex("""video\s*:\s*["']([^"']*\.(?:mp4|m3u8|mkv|avi)[^"']*)["']"""),
                Regex("""url\s*:\s*["']([^"']*\.(?:mp4|m3u8|mkv|avi)[^"']*)["']""")
            )
            
            videoPatterns.forEach { pattern ->
                pattern.findAll(scriptContent).forEach { match ->
                    val videoUrl = match.groupValues[1]
                    if (videoUrl.isNotBlank()) {
                        foundLinks = true
                        callback(
                            newExtractorLink(
                                source = name,
                                name = "Script Video",
                                url = fixUrl(videoUrl),
                                type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                this.referer = mainUrl
                            }
                        )
                    }
                }
            }
        }
        
        return foundLinks
    }

    // ==================== HELPER FUNCTIONS ====================

    private fun Element.toSearchResponse(): SearchResponse? {
        // Try to find title and URL
        val link = select("a").firstOrNull { 
            it.attr("href")?.contains(Regex("/(movie|tv)/")) == true 
        } ?: return null
        
        val href = link.attr("href") ?: return null
        val url = fixUrl(href)
        
        // Try multiple selectors for title
        val title = link.select(".title, h3, h2, .name, .movie-title, .tv-title").text() 
            ?: link.ownText() 
            ?: select(".title, h3, h2, .name").text()
            ?: return null
        
        // Try multiple selectors for poster
        val poster = select("img").firstOrNull()?.attr("src")
        
        val type = when {
            url.contains("/tv/") -> TvType.TvSeries
            else -> TvType.Movie
        }
        
        return newMovieSearchResponse(title, url, type) {
            this.posterUrl = fixUrl(poster)
        }
    }

    private fun fixUrl(url: String): String {
        if (url.isBlank()) return ""
        return when {
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$mainUrl$url"
            else -> "$mainUrl/$url"
        }
    }

    private fun String.findYear(): Int? {
        val yearPattern = Regex("""(19|20)\d{2}""")
        return yearPattern.find(this)?.value?.toIntOrNull()
    }
}
