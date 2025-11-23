package com.cineby

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
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
        "$mainUrl/movies/trending" to "Trending Movies",
        "$mainUrl/movies/popular" to "Popular Movies", 
        "$mainUrl/movies/top-rated" to "Top Rated Movies",
        "$mainUrl/tv/trending" to "Trending TV",
        "$mainUrl/tv/popular" to "Popular TV",
        "$mainUrl/tv/top-rated" to "Top Rated TV"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document
        val items = document.select(".grid .card").mapNotNull { it.toSearchResponse() }
        
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    // ==================== SEARCH ====================

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search?query=${URLEncoder.encode(query, "UTF-8")}"
        val document = app.get(searchUrl).document
        return document.select(".grid .card").mapNotNull { it.toSearchResponse() }
    }

    // ==================== LOAD ====================

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        
        val title = document.selectFirst("h1")?.text() ?: "Unknown"
        val poster = document.selectFirst(".poster img")?.attr("src")
        val backdrop = document.selectFirst(".backdrop img")?.attr("src")
        val description = document.selectFirst(".overview")?.text()
        val year = document.selectFirst(".release-date")?.text()?.take(4)?.toIntOrNull()
        
        val type = if (url.contains("/tv/")) TvType.TvSeries else TvType.Movie
        
        // Check if it's a TV series by looking for seasons
        val seasons = document.select(".season-item")
        
        return if (type == TvType.TvSeries && seasons.isNotEmpty()) {
            val episodes = mutableListOf<Episode>()
            
            seasons.forEach { seasonElement ->
                val seasonNum = seasonElement.select(".season-number").text().toIntOrNull() ?: 1
                val seasonEpisodes = seasonElement.select(".episode-item")
                
                seasonEpisodes.forEach { episodeElement ->
                    val episodeNum = episodeElement.select(".episode-number").text().toIntOrNull() ?: 1
                    val episodeTitle = episodeElement.select(".episode-title").text()
                    val episodeUrl = episodeElement.attr("data-url") ?: "$url/season/$seasonNum/episode/$episodeNum"
                    
                    episodes.add(
                        newEpisode(episodeUrl) {
                            this.name = episodeTitle
                            this.season = seasonNum
                            this.episode = episodeNum
                        }
                    )
                }
            }
            
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.plot = description
                this.year = year
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.plot = description
                this.year = year
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
        
        // Method 1: Look for direct video players
        document.select("video source").forEach { source ->
            val videoUrl = source.attr("src")
            if (videoUrl.isNotBlank()) {
                foundLinks = true
                callback(
                    newExtractorLink(
                        source = name,
                        name = "Direct Video",
                        url = videoUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = mainUrl
                    }
                )
            }
        }
        
        // Method 2: Look for iframe embeds
        document.select("iframe").forEach { iframe ->
            val iframeSrc = iframe.attr("src")
            if (iframeSrc.isNotBlank()) {
                foundLinks = true
                loadExtractor(iframeSrc, mainUrl, subtitleCallback, callback)
            }
        }
        
        // Method 3: Look for embedded players
        document.select("[data-player], .video-player, .player").forEach { player ->
            val playerUrl = player.attr("data-src") ?: player.attr("data-url")
            if (playerUrl?.isNotBlank() == true) {
                foundLinks = true
                loadExtractor(playerUrl, mainUrl, subtitleCallback, callback)
            }
        }
        
        return foundLinks
    }

    // ==================== HELPER FUNCTIONS ====================

    private fun Element.toSearchResponse(): SearchResponse? {
        val titleElement = select(".title a, h3 a, .card-title a").first()
        val title = titleElement?.text() ?: return null
        val href = titleElement.attr("href") ?: return null
        val url = fixUrl(href)
        val poster = select("img").attr("src")
        
        val type = when {
            url.contains("/tv/") -> TvType.TvSeries
            else -> TvType.Movie
        }
        
        return newMovieSearchResponse(title, url, type) {
            this.posterUrl = fixUrl(poster)
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
