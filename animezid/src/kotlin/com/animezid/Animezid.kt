package com.animezid

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import java.net.URLEncoder

class Animezid : MainAPI() {
    override var lang = "ar"
    override var mainUrl = "https://animezid.cam"
    override var name = "Animezid1"
    override val usesWebView = false
    override val hasMainPage = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.Cartoon,
        TvType.TvSeries
    )

    // ==================== UTILITY FUNCTIONS ====================
    
    private fun String.cleanTitle(): String {
        return this.replace(
            "مشاهدة|تحميل|انمي|مترجم|اون لاين|بجودة عالية|الحلقة|مسلسل".toRegex(),
            ""
        ).trim()
    }

    private fun String.getIntFromText(): Int? {
        return Regex("""\d+""").find(this)?.groupValues?.firstOrNull()?.toIntOrNull()
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        return try {
            val titleElement = selectFirst("h3, h2, .title, .anime-title") ?: return null
            val title = titleElement.text().cleanTitle()
            
            val linkElement = selectFirst("a") ?: return null
            val href = linkElement.attr("href").let {
                if (it.startsWith("http")) it else "$mainUrl${it.removePrefix("/")}"
            }
            
            val posterUrl = selectFirst("img")?.let { img ->
                img.attr("data-src")
                    .ifBlank { img.attr("data-lazy-src") }
                    .ifBlank { img.attr("src") }
            }?.let {
                if (it.startsWith("http")) it else "$mainUrl${it.removePrefix("/")}"
            }

            // Determine type based on URL or content
            val type = when {
                href.contains("/anime/") -> TvType.Anime
                href.contains("/cartoon/") -> TvType.Cartoon
                else -> TvType.TvSeries
            }

            newAnimeSearchResponse(title, href, type) {
                this.posterUrl = posterUrl
            }
        } catch (e: Exception) {
            Log.e(name, "Error parsing search response", e)
            null
        }
    }

    // ==================== MAIN PAGE ====================

    override val mainPage = mainPageOf(
        "$mainUrl/anime/page/" to "أنمي",
        "$mainUrl/cartoon/page/" to "كرتون",
        "$mainUrl/category/anime-movies/page/" to "أفلام أنمي",
        "$mainUrl/category/japanese-anime/page/" to "أنمي ياباني",
        "$mainUrl/category/chinese-anime/page/" to "أنمي صيني",
        "$mainUrl/category/korean-anime/page/" to "أنمي كوري",
        "$mainUrl/ongoing/page/" to "مستمر",
        "$mainUrl/completed/page/" to "مكتمل",
        "$mainUrl/latest/page/" to "أحدث الحلقات"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "${request.data}$page/"
        
        return try {
            val document = app.get(url).document
            
            val items = document.select(
                "article.anime-card, " +
                "div.anime-item, " +
                "div.post-item, " +
                ".content-item, " +
                ".anime-box"
            ).mapNotNull { element ->
                element.toSearchResponse()
            }

            newHomePageResponse(
                list = HomePageList(request.name, items),
                hasNext = items.isNotEmpty()
            )
        } catch (e: Exception) {
            Log.e(name, "Error loading main page: ${request.name}", e)
            newHomePageResponse(request.name, emptyList())
        }
    }

    // ==================== SEARCH ====================

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.length < 2) return emptyList()
        
        return try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val searchUrl = "$mainUrl/?s=$encodedQuery"
            
            Log.d(name, "Searching: $searchUrl")
            
            val document = app.get(searchUrl).document
            
            document.select(
                "article.anime-card, " +
                "div.anime-item, " +
                "div.post-item, " +
                ".search-result, " +
                ".content-item"
            ).mapNotNull { element ->
                element.toSearchResponse()
            }
        } catch (e: Exception) {
            Log.e(name, "Search error for query: $query", e)
            emptyList()
        }
    }

    // ==================== LOAD (SERIES/MOVIE PAGE) ====================

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        
        val title = document.selectFirst(
            "h1.entry-title, " +
            "h1.anime-title, " +
            "h1.post-title, " +
            "h1"
        )?.text()?.cleanTitle() ?: "Unknown"
        
        val posterUrl = document.selectFirst(
            ".anime-poster img, " +
            ".post-thumbnail img, " +
            ".single-poster img, " +
            "img.wp-post-image"
        )?.let { img ->
            img.attr("data-src")
                .ifBlank { img.attr("src") }
        }?.let {
            if (it.startsWith("http")) it else "$mainUrl${it.removePrefix("/")}"
        }
        
        val description = document.selectFirst(
            ".anime-description, " +
            ".entry-content p, " +
            ".story, " +
            ".synopsis"
        )?.text()?.trim()
        
        val tags = document.select(
            ".anime-genres a, " +
            ".genres a, " +
            "a[rel=tag]"
        ).map { it.text() }
        
        val year = document.selectFirst(
            ".year, " +
            ".release-year, " +
            "span:contains(السنة)"
        )?.text()?.getIntFromText()

        // Extract episodes
        val episodes = mutableListOf<Episode>()
        
        // Pattern 1: Episode list on same page
        document.select(
            ".episode-list a, " +
            ".episodes-list a, " +
            "ul.eps-list li a, " +
            ".episode-item a"
        ).forEach { epElement ->
            val epHref = epElement.attr("href").let {
                if (it.startsWith("http")) it else "$mainUrl${it.removePrefix("/")}"
            }
            val epTitle = epElement.text().trim()
            val epNum = epTitle.getIntFromText()
            
            episodes.add(
                newEpisode(epHref) {
                    name = epTitle.ifBlank { "الحلقة $epNum" }
                    episode = epNum
                    posterUrl = posterUrl
                }
            )
        }

        // Pattern 2: Season-based episodes
        document.select(".season-list .season, .seasons-list li").forEach { seasonElement ->
            val seasonNumber = seasonElement.text().getIntFromText() ?: 1
            
            seasonElement.select("a, .episode-link").forEach { epElement ->
                val epHref = epElement.attr("href").let {
                    if (it.startsWith("http")) it else "$mainUrl${it.removePrefix("/")}"
                }
                val epTitle = epElement.text().trim()
                val epNum = epTitle.getIntFromText()
                
                episodes.add(
                    newEpisode(epHref) {
                        name = epTitle
                        episode = epNum
                        season = seasonNumber
                        posterUrl = posterUrl
                    }
                )
            }
        }

        // Pattern 3: AJAX-loaded episodes (check for load more button)
        val hasLoadMore = document.selectFirst(
            ".load-more-episodes, " +
            "button.load-episodes, " +
            "[data-load-episodes]"
        ) != null

        if (hasLoadMore) {
            Log.d(name, "AJAX episode loading detected but not implemented")
            // Could implement AJAX loading here if needed
        }

        val isSeries = episodes.isNotEmpty()

        return if (isSeries) {
            newAnimeLoadResponse(title, url, TvType.Anime) {
                addEpisodes(DubStatus.Subbed, episodes.reversed())
                this.posterUrl = posterUrl
                this.plot = description
                this.tags = tags
                this.year = year
            }
        } else {
            newMovieLoadResponse(title, url, TvType.AnimeMovie, url) {
                this.posterUrl = posterUrl
                this.plot = description
                this.tags = tags
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
        Log.d(name, "Loading links for: $data")
        
        var foundLinks = false
        
        try {
            val document = app.get(data).document
            
            // Pattern 1: Direct iframes
            document.select("iframe").forEach { iframe ->
                val src = iframe.attr("src")
                    .ifBlank { iframe.attr("data-src") }
                    .let {
                        if (it.startsWith("//")) "https:$it"
                        else if (it.startsWith("/")) "$mainUrl$it"
                        else it
                    }
                
                if (src.isNotBlank() && src.startsWith("http")) {
                    Log.d(name, "Found iframe: $src")
                    foundLinks = true
                    loadExtractor(src, data, subtitleCallback, callback)
                }
            }
            
            // Pattern 2: Server buttons/tabs
            document.select(
                ".server-list li, " +
                ".watch-servers li, " +
                "button[data-server], " +
                "a[data-embed]"
            ).forEach { serverElement ->
                val embedUrl = serverElement.attr("data-server")
                    .ifBlank { serverElement.attr("data-embed") }
                    .ifBlank { serverElement.attr("data-src") }
                    .ifBlank { serverElement.attr("href") }
                    .let {
                        if (it.startsWith("//")) "https:$it"
                        else if (it.startsWith("/")) "$mainUrl$it"
                        else it
                    }
                
                if (embedUrl.isNotBlank() && embedUrl.startsWith("http")) {
                    Log.d(name, "Found server embed: $embedUrl")
                    foundLinks = true
                    loadExtractor(embedUrl, data, subtitleCallback, callback)
                }
            }
            
            // Pattern 3: Direct video links
            document.select("a[href*='.mp4'], a[href*='.m3u8']").forEach { link ->
                val videoUrl = link.attr("href")
                if (videoUrl.isNotBlank()) {
                    Log.d(name, "Found direct video: $videoUrl")
                    foundLinks = true
                    loadExtractor(videoUrl, data, subtitleCallback, callback)
                }
            }
            
            // Pattern 4: AJAX video loading
            val watchButton = document.selectFirst(
                ".watch-button, " +
                "button.watch-now, " +
                "[data-watch-url]"
            )
            
            if (watchButton != null) {
                val watchUrl = watchButton.attr("data-watch-url")
                    .ifBlank { watchButton.attr("href") }
                
                if (watchUrl.isNotBlank()) {
                    try {
                        val watchDoc = app.get(
                            if (watchUrl.startsWith("http")) watchUrl 
                            else "$mainUrl${watchUrl.removePrefix("/")}",
                            referer = data
                        ).document
                        
                        watchDoc.select("iframe, source[src]").forEach { element ->
                            val src = element.attr("src")
                            if (src.isNotBlank() && src.startsWith("http")) {
                                foundLinks = true
                                loadExtractor(src, data, subtitleCallback, callback)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(name, "Error loading watch page", e)
                    }
                }
            }
            
        } catch (e: Exception) {
            Log.e(name, "Error in loadLinks", e)
        }
        
        return foundLinks
    }

}
