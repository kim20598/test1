package com.fushaar

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import java.net.URLEncoder

class Fushaar : MainAPI() {
    override var lang = "ar"
    override var mainUrl = "https://www.fushaar.com"
    override var name = "Fushaar"
    override val usesWebView = false
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.Anime)

    // Helper functions
    private fun String.getIntFromText(): Int? {
        return Regex("""\d+""").find(this)?.groupValues?.firstOrNull()?.toIntOrNull()
    }
    
    private fun String.cleanTitle(): String {
        return this.replace("مشاهدة|فيلم|مسلسل|انمي|مترجم|مدبلج|كامل|اونلاين".toRegex(), "").trim()
    }

    // Search response from Fushaar site structure
    private fun Element.toSearchResponse(): SearchResponse {
        val title = select("h2 a").text().cleanTitle()
        val posterUrl = select("img").attr("src")
        val href = select("h2 a").attr("href")
        
        // Determine type based on URL or category
        val tvType = when {
            href.contains("/movie/") || select(".category a").text().contains("فيلم") -> TvType.Movie
            href.contains("/series/") || select(".category a").text().contains("مسلسل") -> TvType.TvSeries
            href.contains("/anime/") || select(".category a").text().contains("انمي") -> TvType.Anime
            else -> TvType.TvSeries
        }
        
        return newMovieSearchResponse(title, href, tvType) {
            this.posterUrl = posterUrl
        }
    }

    // Main page for Fushaar
    override val mainPage = mainPageOf(
        "$mainUrl/movies/" to "الأفلام",
        "$mainUrl/series/" to "المسلسلات", 
        "$mainUrl/anime/" to "الأنمي"
    )

    // getMainPage for Fushaar
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) "${request.data}page/$page/" else request.data
        val document = app.get(url).document
        
        val home = document.select(".movie-list .movie, .series-list .series, .anime-list .anime, .post, article").mapNotNull {
            try {
                it.toSearchResponse()
            } catch (e: Exception) {
                null
            }
        }
        return newHomePageResponse(request.name, home)
    }

    // search function for Fushaar
    override suspend fun search(query: String): List<SearchResponse> {
        if (query.length < 3) return emptyList()
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val doc = app.get("$mainUrl/?s=$encodedQuery").document
        
        return doc.select(".search-result, .post, article, .movie, .series").mapNotNull {
            try {
                // Skip episode pages in search results
                val href = it.select("a").attr("href")
                if (href.contains("/episode/") || href.contains("/watch/")) return@mapNotNull null
                
                it.toSearchResponse()
            } catch (e: Exception) {
                null
            }
        }
    }

    // load function for Fushaar
    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        
        // Extract title
        val title = doc.select("h1.entry-title, h1.title, .post-title").text().cleanTitle()
        
        // Determine if it's a movie or series
        val isMovie = url.contains("/movie/") || doc.select(".movie-details, .film-details").isNotEmpty()
        
        // Extract poster
        val posterUrl = doc.select(".poster img, .thumbnail img, .featured-image img").attr("src")
        
        // Extract synopsis/plot
        val synopsis = doc.select(".entry-content, .description, .plot, .story").text()
        
        // Extract year
        val year = doc.select(".year, .release-date, .date").text().getIntFromText()
        
        // Extract tags/genres
        val tags = doc.select(".genres a, .tags a, .category a").map { it.text() }
        
        // Extract recommendations
        val recommendations = doc.select(".related-posts a, .recommendations a").mapNotNull { element ->
            try {
                newMovieSearchResponse(
                    element.text().cleanTitle(),
                    element.attr("href"),
                    TvType.TvSeries
                ) {
                    this.posterUrl = element.select("img").attr("src")
                }
            } catch (e: Exception) {
                null
            }
        }

        return if (isMovie) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = posterUrl
                this.recommendations = recommendations
                this.plot = synopsis
                this.tags = tags
                this.year = year
            }
        } else {
            // For series, extract episodes
            val episodes = arrayListOf<Episode>()
            
            // Try to find episode lists
            doc.select(".episode-list a, .episodes a, .season-episodes a").forEach { episodeElement ->
                val episodeUrl = episodeElement.attr("href")
                val episodeText = episodeElement.text()
                
                episodes.add(newEpisode(episodeUrl) {
                    name = episodeElement.attr("title").ifBlank { episodeText }
                    episode = episodeText.getIntFromText() ?: 1
                    season = 1
                })
            }
            
            // If no episodes found, try seasons
            if (episodes.isEmpty()) {
                doc.select(".season-list a, .seasons a").forEachIndexed { seasonIndex, seasonElement ->
                    val seasonUrl = seasonElement.attr("href")
                    val seasonDoc = app.get(seasonUrl).document
                    
                    seasonDoc.select(".episode-list a, .episodes a").forEach { episodeElement ->
                        val episodeUrl = episodeElement.attr("href")
                        val episodeText = episodeElement.text()
                        
                        episodes.add(newEpisode(episodeUrl) {
                            name = episodeElement.attr("title").ifBlank { episodeText }
                            episode = episodeText.getIntFromText() ?: 1
                            season = seasonIndex + 1
                        })
                    }
                }
            }
            
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.distinct().sortedBy { it.episode }) {
                this.posterUrl = posterUrl
                this.tags = tags
                this.plot = synopsis
                this.recommendations = recommendations
                this.year = year
            }
        }
    }

    // loadLinks function for Fushaar
    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        var foundLinks = false
        
        try {
            val doc = app.get(data).document
            
            // Try different video server selectors used by Arabic sites
            val serverSelectors = listOf(
                ".servers-list a",
                ".download-servers a", 
                ".server-list a",
                ".video-server a",
                ".player-options a",
                "[data-server]",
                "[data-link]"
            )
            
            serverSelectors.forEach { selector ->
                doc.select(selector).forEach { serverElement ->
                    val videoUrl = serverElement.attr("href").ifBlank { 
                        serverElement.attr("data-link").ifBlank { 
                            serverElement.attr("data-server") 
                        } 
                    }
                    
                    if (videoUrl.isNotBlank() && videoUrl.contains("http")) {
                        foundLinks = true
                        loadExtractor(videoUrl, data, subtitleCallback, callback)
                    }
                }
            }
            
            // Also check for iframes directly
            doc.select("iframe").forEach { iframe ->
                val iframeUrl = iframe.attr("src")
                if (iframeUrl.isNotBlank() && iframeUrl.contains("http")) {
                    foundLinks = true
                    loadExtractor(iframeUrl, data, subtitleCallback, callback)
                }
            }
            
        } catch (e: Exception) {
            // Log error but don't crash
            e.printStackTrace()
        }
        
        return foundLinks
    }
}
