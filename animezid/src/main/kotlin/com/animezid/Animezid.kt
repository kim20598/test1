package com.animezid

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

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
        "$mainUrl/movies/" to "أفلام أنمي",
        "$mainUrl/ongoing/" to "مسلسلات مستمرة", 
        "$mainUrl/completed/" to "مسلسلات مكتملة",
        "$mainUrl/category.php?cat=disney-masr" to "ديزني بالمصري",
        "$mainUrl/category.php?cat=spacetoon" to "سبيستون"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document
        
        val items = document.select("a.movie").mapNotNull { it.toRealSearchResponse() }

        return newHomePageResponse(request.name, items, hasNext = false)
    }

    // ==================== SEARCH ====================

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search.php?keywords=${java.net.URLEncoder.encode(query, "UTF-8")}"
        val document = app.get(searchUrl).document
        
        return document.select("a.movie").mapNotNull { it.toRealSearchResponse() }
    }

    // ==================== LOAD ====================

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        
        // REAL title extraction
        val title = document.selectFirst("h1")?.text() ?: "Unknown"
        
        // REAL poster extraction  
        val poster = document.selectFirst("img.lazy")?.attr("data-src")?.let { 
            if (it.startsWith("http")) it else "$mainUrl$it"
        } ?: ""

        // REAL description extraction
        val description = document.selectFirst(".pm-video-description")?.text() ?: ""

        // Check if it's a movie or series by URL pattern
        val isMovie = url.contains("/movie/") || url.contains("فيلم") || 
                     document.select("a[href*='play.php']").isNotEmpty()

        if (isMovie) {
            // MOVIE - use the URL directly for playback
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
            }
        } else {
            // SERIES - extract REAL episodes
            val episodes = extractRealEpisodes(document, url)
            
            return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                this.posterUrl = poster
                this.plot = description
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
        
        // REAL video extraction - look for play.php links
        val playLinks = document.select("a[href*='play.php']")
        
        playLinks.forEach { playLink ->
            val playUrl = playLink.attr("href").let {
                if (it.startsWith("http")) it else "$mainUrl/$it"
            }
            loadExtractor(playUrl, data, subtitleCallback, callback)
        }

        // Also check for iframes
        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("src").let {
                when {
                    it.startsWith("//") -> "https:$it"
                    it.startsWith("/") -> "$mainUrl$it"
                    else -> it
                }
            }
            if (src.isNotBlank()) {
                loadExtractor(src, data, subtitleCallback, callback)
            }
        }

        return playLinks.isNotEmpty()
    }

    // ==================== REAL EPISODE EXTRACTION ====================

    private fun extractRealEpisodes(document: org.jsoup.nodes.Document, baseUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        // Method 1: Look for episode links in the page
        document.select("a[href*='watch.php']").forEach { episodeLink ->
            val episodeUrl = episodeLink.attr("href").let {
                if (it.startsWith("http")) it else "$mainUrl/$it"
            }
            val episodeText = episodeLink.text().trim()
            
            // Extract episode number from text
            val episodeNum = extractEpisodeNumber(episodeText)
            
            episodes.add(
                newEpisode(episodeUrl) {
                    this.name = episodeText.ifBlank { "الحلقة $episodeNum" }
                    this.episode = episodeNum
                }
            )
        }

        // Method 2: If no episodes found, check similar content section
        if (episodes.isEmpty()) {
            document.select(".movies_small a.movie[href*='watch.php']").forEach { similar ->
                val episodeUrl = similar.attr("href").let {
                    if (it.startsWith("http")) it else "$mainUrl/$it"
                }
                val episodeText = similar.select(".title").text().trim()
                
                val episodeNum = extractEpisodeNumber(episodeText)
                
                episodes.add(
                    newEpisode(episodeUrl) {
                        this.name = episodeText
                        this.episode = episodeNum
                    }
                )
            }
        }

        // Method 3: Fallback - create a single episode linking to the main page
        if (episodes.isEmpty()) {
            episodes.add(
                newEpisode(baseUrl) {
                    this.name = "مشاهدة الحلقات"
                    this.episode = 1
                }
            )
        }

        return episodes.distinctBy { it.episode }.sortedBy { it.episode }
    }

    private fun extractEpisodeNumber(text: String): Int {
        return Regex("""الحلقة\s*(\d+)""").find(text)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""\b(\d+)\b""").find(text)?.groupValues?.get(1)?.toIntOrNull() 
            ?: 1
    }

    // ==================== REAL SEARCH RESPONSE ====================

    private fun Element.toRealSearchResponse(): SearchResponse? {
        val title = this.attr("title").takeIf { it.isNotBlank() } ?: return null
        val href = this.attr("href").takeIf { it.isNotBlank() } ?: return null
        val poster = this.select("img.lazy").attr("data-src")
        
        val fullPoster = if (poster.startsWith("http")) poster else "$mainUrl$poster"
        val fullHref = if (href.startsWith("http")) href else "$mainUrl$href"

        // Determine type based on title and URL
        val isMovie = title.contains("فيلم") || href.contains("/movie/") || href.contains("فيلم")

        return if (isMovie) {
            newMovieSearchResponse(title, fullHref, TvType.Movie) {
                this.posterUrl = fullPoster
            }
        } else {
            newTvSeriesSearchResponse(title, fullHref, TvType.Anime) {
                this.posterUrl = fullPoster
            }
        }
    }
}