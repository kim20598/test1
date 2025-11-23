package com.cineby

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

class Cineby : MainAPI() {
    override var mainUrl = "https://cineby.gd"
    override var name = "Cineby"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    // ==================== MAIN PAGE ====================
    
    override val mainPage = mainPageOf(
        "" to "Home",
        "movies" to "Movies", 
        "tv" to "TV Shows",
        "movies?type=trending" to "Trending Movies",
        "movies?type=popular" to "Popular Movies",
        "tv?type=trending" to "Trending TV",
        "tv?type=popular" to "Popular TV"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (request.data.isEmpty()) {
            mainUrl
        } else {
            "$mainUrl/${request.data}"
        }
        
        val document = app.get(url).document
        val items = mutableListOf<SearchResponse>()
        
        // REAL SELECTORS FROM CINEBY SITE
        document.select("a").forEach { element ->
            val href = element.attr("href")
            if ((href.contains("/movie/") || href.contains("/tv/")) && href != "/movies" && href != "/tv") {
                element.toSearchResponse()?.let { items.add(it) }
            }
        }
        
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    // ==================== SEARCH ====================
    
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search?query=${URLEncoder.encode(query, "UTF-8")}"
        val document = app.get(searchUrl).document
        val items = mutableListOf<SearchResponse>()
        
        document.select("a").forEach { element ->
            val href = element.attr("href")
            if (href.contains("/movie/") || href.contains("/tv/")) {
                element.toSearchResponse()?.let { items.add(it) }
            }
        }
        
        return items
    }

    // ==================== LOAD ====================

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        
        // REAL SELECTORS FROM CINEBY MOVIE/TV PAGES
        val title = document.selectFirst("h1")?.text() ?: "Unknown"
        val poster = document.selectFirst("img")?.attr("src") ?: ""
        val backdrop = document.selectFirst(".backdrop img, .background img")?.attr("src") ?: poster
        val description = document.selectFirst("p, .description, .overview")?.text() ?: ""
        
        // Find year from release date or page content
        val yearText = document.selectFirst(".year, .release-date, .date")?.text()
        val year = yearText?.findYear() ?: document.wholeText().findYear()
        
        val type = if (url.contains("/tv/")) TvType.TvSeries else TvType.Movie
        
        if (type == TvType.TvSeries) {
            val episodes = loadTvEpisodes(document, url)
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

    private suspend fun loadTvEpisodes(document: Element, baseUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        // Look for seasons and episodes structure
        val seasons = document.select(".season, [class*='season']")
        
        if (seasons.isNotEmpty()) {
            seasons.forEachIndexed { seasonIndex, season ->
                val seasonNum = seasonIndex + 1
                val episodeItems = season.select(".episode, [class*='episode']")
                
                episodeItems.forEachIndexed { episodeIndex, episode ->
                    val episodeNum = episodeIndex + 1
                    val episodeTitle = episode.select(".title, .episode-title").text() 
                    val episodeUrl = episode.select("a").attr("href") ?: "$baseUrl/watch?season=$seasonNum&episode=$episodeNum"
                    
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
            // Create default episodes if no structure found
            for (season in 1..1) {
                for (episode in 1..12) {
                    episodes.add(
                        newEpisode("$baseUrl/watch?season=$season&episode=$episode") {
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
        
        // Method 1: Direct video players
        document.select("video source").forEach { source ->
            val videoUrl = source.attr("src")
            if (videoUrl.isNotBlank()) {
                foundLinks = true
                callback(
                    newExtractorLink(
                        source = name,
                        name = "Direct",
                        url = fixUrl(videoUrl),
                        type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = mainUrl
                    }
                )
            }
        }
        
        // Method 2: Iframe embeds
        document.select("iframe").forEach { iframe ->
            val iframeSrc = iframe.attr("src")
            if (iframeSrc.isNotBlank()) {
                foundLinks = true
                loadExtractor(fixUrl(iframeSrc), mainUrl, subtitleCallback, callback)
            }
        }
        
        // Method 3: JavaScript players
        document.select("script").forEach { script ->
            val content = script.html()
            // Look for video URLs in scripts
            val patterns = listOf(
                Regex("""(https?://[^"']*\.(?:mp4|m3u8|mkv|avi)[^"']*)"""),
                Regex("""src\s*:\s*["']([^"']*)["']"""),
                Regex("""file\s*:\s*["']([^"']*)["']"""),
                Regex("""video\s*:\s*["']([^"']*)["']""")
            )
            
            patterns.forEach { pattern ->
                pattern.findAll(content).forEach { match ->
                    val url = match.groupValues[1]
                    if (url.contains("http") && (url.contains(".mp4") || url.contains(".m3u8"))) {
                        foundLinks = true
                        callback(
                            newExtractorLink(
                                source = name,
                                name = "Script",
                                url = fixUrl(url),
                                type = if (url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
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
        val href = attr("href")
        if (href.isBlank() || (!href.contains("/movie/") && !href.contains("/tv/"))) {
            return null
        }
        
        val url = fixUrl(href)
        
        // Find title - try multiple locations
        val titleElement = select(".title, h3, h2, .name, [class*='title']").first()
        val title = titleElement?.text() ?: ownText() ?: return null
        
        // Find poster image
        val poster = select("img").attr("src")
        
        val type = if (url.contains("/tv/")) TvType.TvSeries else TvType.Movie
        
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
        return Regex("""(19|20)\d{2}""").find(this)?.value?.toIntOrNull()
    }
}

