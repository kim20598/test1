package com.fushaar

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import java.net.URLEncoder

class Fushaar : MainAPI() {
    override var lang = "ar"
    override var mainUrl = "https://fushaar.com"
    override var name = "Fushaar"
    override val usesWebView = false
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.Anime)

    private fun String.getIntFromText(): Int? {
        return Regex("""\d+""").find(this)?.groupValues?.firstOrNull()?.toIntOrNull()
    }
    
    private fun String.cleanTitle(): String {
        return this.replace("مشاهدة وتحميل فلم|مشاهدة وتحميل|اونلاين|مترجم".toRegex(), "").trim()
    }

    private fun Element.toSearchResponse(): SearchResponse {
        val title = select("h3").text().cleanTitle()
        // FIX: Fushaar uses data-src for lazy loading images
        val posterUrl = select("img").attr("data-src").ifBlank { 
            select("img").attr("src") 
        }
        val href = select("a").attr("href")
        
        // FIX: Better content type detection
        val tvType = when {
            href.contains("/movie/") -> TvType.Movie
            href.contains("/series/") || href.contains("/show/") -> TvType.TvSeries
            href.contains("/anime/") -> TvType.Anime
            else -> TvType.Movie // default
        }
        
        return newMovieSearchResponse(title, href, tvType) {
            this.posterUrl = posterUrl
        }
    }

    // FIX: Proper main page with categories
    override val mainPage = mainPageOf(
        "$mainUrl/movies/" to "أفلام أجنبية",
        "$mainUrl/series/" to "مسلسلات أجنبية", 
        "$mainUrl/anime/" to "انمي",
        "$mainUrl/arabic-movies/" to "أفلام عربية",
        "$mainUrl/turkish-series/" to "مسلسلات تركية"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) "${request.data}page/$page/" else request.data
        val document = app.get(url).document
        val home = document.select("article").mapNotNull {
            it.toSearchResponse()
        }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.length < 3) return emptyList()
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val doc = app.get("$mainUrl/?s=$encodedQuery").document
        return doc.select("article").mapNotNull {
            it.toSearchResponse()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1")?.text()?.cleanTitle() ?: "Unknown Title"
        
        // FIX: Better content type detection from URL and page content
        val isMovie = url.contains("/movie/") || doc.selectFirst("[class*='movie']") != null
        val isSeries = url.contains("/series/") || url.contains("/show/") || doc.selectFirst("[class*='series']") != null
        val isAnime = url.contains("/anime/") || doc.selectFirst("[class*='anime']") != null
        
        val tvType = when {
            isMovie -> TvType.Movie
            isSeries -> TvType.TvSeries  
            isAnime -> TvType.Anime
            else -> TvType.Movie
        }

        // FIX: Get proper poster with lazy loading support
        val posterUrl = doc.selectFirst("img[data-src], .poster img, .thumbnail img")?.attr("data-src") ?: 
                       doc.selectFirst("img[data-src], .poster img, .thumbnail img")?.attr("src") ?: ""
        
        val synopsis = doc.selectFirst(".entry-content, .description, .plot")?.text() ?: ""
        val year = doc.selectFirst(".year, [class*='date']")?.text()?.getIntFromText()
        val tags = doc.select(".genre a, .category a, [rel='tag']").map { it.text() }
        
        val recommendations = doc.select(".related-posts article, .related article, .similar-posts article").mapNotNull { element ->
            element.toSearchResponse()
        }
        
        val youtubeTrailer = doc.selectFirst("iframe[src*='youtube'], iframe[src*='youtu.be']")?.attr("src") ?: ""
        
        return if (tvType == TvType.Movie) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = posterUrl
                this.recommendations = recommendations
                this.plot = synopsis
                this.tags = tags
                this.year = year
                addTrailer(youtubeTrailer)
            }
        } else {
            val episodes = arrayListOf<Episode>()
            
            // FIX: Better episode detection for series/anime
            val seasonList = doc.select(".seasons-list a, [class*='season'] a, .season-list a").reversed()
            
            if(seasonList.isNotEmpty()) {
                seasonList.forEachIndexed { index, season ->
                    val seasonDoc = app.get(season.attr("href")).document
                    seasonDoc.select(".episodes-list a, [class*='episode'] a, .episode-list a").forEach {
                        episodes.add(newEpisode(it.attr("href")) {
                            name = it.attr("title").ifBlank { it.text().cleanTitle() }
                            this.season = index + 1
                            episode = it.text().getIntFromText() ?: (episodes.size + 1)
                        })
                    }
                }
            } else {
                // Single season series
                doc.select(".episodes-list a, [class*='episode'] a, .episode-list a").forEach {
                    episodes.add(newEpisode(it.attr("href")) {
                        name = it.attr("title").ifBlank { it.text().cleanTitle() }
                        this.season = 1
                        episode = it.text().getIntFromText() ?: (episodes.size + 1)
                    })
                }
            }
            
            newTvSeriesLoadResponse(title, url, tvType, episodes.distinctBy { it.episode }.sortedBy { it.episode }) {
                this.posterUrl = posterUrl
                this.tags = tags
                this.plot = synopsis
                this.recommendations = recommendations
                this.year = year
                addTrailer(youtubeTrailer)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var foundLinks = false
        
        try {
            val doc = app.get(data).document
            
            // Method 1: Direct video links
            doc.select("a[href*='.mp4'], a[href*='.m3u8'], a[href*='.mkv']").forEach { element ->
                val url = element.attr("href")
                if (url.isNotBlank()) {
                    foundLinks = true
                    loadExtractor(url, data, subtitleCallback, callback)
                }
            }
            
            // Method 2: Iframe embeds
            doc.select("iframe").forEach { iframe ->
                val src = iframe.attr("src")
                if (src.isNotBlank() && src.contains("http")) {
                    foundLinks = true
                    loadExtractor(src, data, subtitleCallback, callback)
                }
            }
            
            // Method 3: Download servers
            doc.select(".server-list a, .download-server a, [class*='server'] a").forEach { server ->
                val url = server.attr("href")
                if (url.isNotBlank()) {
                    foundLinks = true
                    loadExtractor(url, data, subtitleCallback, callback)
                }
            }
            
            // Method 4: Try POST request if no links found
            if (!foundLinks) {
                try {
                    val postDoc = app.post(data, data = mapOf("view" to "1")).document
                    
                    postDoc.select("a[href*='.mp4'], a[href*='.m3u8'], iframe").forEach { element ->
                        val url = if (element.tagName() == "iframe") element.attr("src") else element.attr("href")
                        if (url.isNotBlank()) {
                            foundLinks = true
                            loadExtractor(url, data, subtitleCallback, callback)
                        }
                    }
                } catch (e: Exception) {
                    // POST failed, continue
                }
            }
            
        } catch (e: Exception) {
            // Fallback if everything fails
        }
        
        return foundLinks
    }
}
