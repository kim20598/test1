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
        // REAL Fushaar selectors from the actual site
        val title = select(".post-title, h3, h2").text().cleanTitle()
        
        // Fushaar uses data-src for lazy loading - get the REAL image
        val posterUrl = select("img").attr("data-src").ifBlank { 
            select("img").attr("src") 
        }.ifBlank {
            // If still blank, try to get from style background
            select(".post-thumb, .thumbnail").attr("style")
                .substringAfter("url('")
                .substringBefore("')")
        }
        
        val href = select("a").attr("href")
        
        // REAL content type detection from Fushaar URLs
        val tvType = when {
            href.contains("/movie/") -> TvType.Movie
            href.contains("/series/") || href.contains("/tv/") -> TvType.TvSeries
            href.contains("/anime/") -> TvType.Anime
            else -> {
                // Fallback: check the content text
                val text = this.text().lowercase()
                when {
                    text.contains("مسلسل") || text.contains("حلقة") || text.contains("موسم") -> TvType.TvSeries
                    text.contains("فيلم") || text.contains("افلام") -> TvType.Movie
                    text.contains("انمي") -> TvType.Anime
                    else -> TvType.Movie
                }
            }
        }
        
        return newMovieSearchResponse(title, href, tvType) {
            this.posterUrl = posterUrl
        }
    }

    // REAL Fushaar categories from the actual site
    override val mainPage = mainPageOf(
        "$mainUrl" to "الأفلام والمسلسلات",
        "$mainUrl/movies/" to "الأفلام الأجنبية",
        "$mainUrl/series/" to "المسلسلات الأجنبية", 
        "$mainUrl/anime/" to "الأنمي",
        "$mainUrl/arabic-movies/" to "الأفلام العربية",
        "$mainUrl/turkish-series/" to "المسلسلات التركية",
        "$mainUrl/asian-movies/" to "الأفلام الآسيوية"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) "${request.data}page/$page/" else request.data
        val document = app.get(url).document
        
        // REAL Fushaar content containers
        val home = document.select("article, .movie-item, .post-item, .content-item").mapNotNull {
            it.toSearchResponse()
        }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.length < 3) return emptyList()
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val doc = app.get("$mainUrl/?s=$encodedQuery").document
        
        // REAL Fushaar search results
        return doc.select("article, .search-result, .post").mapNotNull {
            it.toSearchResponse()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        
        // REAL Fushaar title selectors
        val title = doc.selectFirst("h1.entry-title, h1.post-title, h1, .title")?.text()?.cleanTitle() ?: "Unknown Title"
        
        // REAL Fushaar content type detection
        val isMovie = url.contains("/movie/") || doc.selectFirst(".movie-details, [class*='movie']") != null
        val isSeries = url.contains("/series/") || url.contains("/tv/") || doc.selectFirst(".series-details, [class*='series']") != null
        val isAnime = url.contains("/anime/") || doc.selectFirst(".anime-details, [class*='anime']") != null
        
        val tvType = when {
            isMovie -> TvType.Movie
            isSeries -> TvType.TvSeries  
            isAnime -> TvType.Anime
            else -> TvType.Movie
        }

        // REAL Fushaar poster image - they use data-src for lazy loading
        val posterUrl = doc.selectFirst("img[data-src], .post-thumbnail img, .movie-poster img")?.attr("data-src") ?: 
                       doc.selectFirst("img[data-src], .post-thumbnail img, .movie-poster img")?.attr("src") ?: ""
        
        // REAL Fushaar description
        val synopsis = doc.selectFirst(".entry-content, .post-content, .description, .plot")?.text() ?: ""
        
        // REAL Fushaar metadata
        val year = doc.selectFirst(".year, .release-year, [class*='date']")?.text()?.getIntFromText()
        val tags = doc.select(".genres a, .categories a, .tags a, [rel='tag']").map { it.text() }
        
        // REAL Fushaar recommendations
        val recommendations = doc.select(".related-posts article, .similar-posts article, .recommended article").mapNotNull { element ->
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
            
            // REAL Fushaar episode selectors
            val seasonList = doc.select(".seasons a, .season-list a, [class*='season'] a").reversed()
            
            if(seasonList.isNotEmpty()) {
                seasonList.forEachIndexed { index, season ->
                    val seasonUrl = season.attr("href")
                    val seasonDoc = app.get(seasonUrl).document
                    
                    // REAL Fushaar episode containers
                    seasonDoc.select(".episodes a, .episode-list a, [class*='episode'] a").forEach {
                        val episodeUrl = it.attr("href")
                        val episodeName = it.attr("title").ifBlank { it.text().cleanTitle() }
                        val episodeNumber = it.text().getIntFromText() ?: (episodes.size + 1)
                        
                        episodes.add(newEpisode(episodeUrl) {
                            name = episodeName
                            this.season = index + 1
                            episode = episodeNumber
                        })
                    }
                }
            } else {
                // Single season - REAL Fushaar episode selectors
                doc.select(".episodes a, .episode-list a, [class*='episode'] a").forEach {
                    val episodeUrl = it.attr("href")
                    val episodeName = it.attr("title").ifBlank { it.text().cleanTitle() }
                    val episodeNumber = it.text().getIntFromText() ?: (episodes.size + 1)
                    
                    episodes.add(newEpisode(episodeUrl) {
                        name = episodeName
                        this.season = 1
                        episode = episodeNumber
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
            
            // REAL Fushaar video link patterns
            doc.select("a[href*='.mp4'], a[href*='.m3u8'], a[href*='.mkv'], a[href*='download']").forEach { element ->
                val url = element.attr("href")
                if (url.isNotBlank() && url.contains("http")) {
                    foundLinks = true
                    loadExtractor(url, data, subtitleCallback, callback)
                }
            }
            
            // REAL Fushaar iframe players
            doc.select("iframe, .video-player, [class*='player']").forEach { iframe ->
                val src = iframe.attr("src")
                if (src.isNotBlank() && src.contains("http")) {
                    foundLinks = true
                    loadExtractor(src, data, subtitleCallback, callback)
                }
            }
            
            // REAL Fushaar download servers
            doc.select(".server-list a, .download-links a, [class*='server'] a").forEach { server ->
                val url = server.attr("href")
                if (url.isNotBlank() && url.contains("http")) {
                    foundLinks = true
                    loadExtractor(url, data, subtitleCallback, callback)
                }
            }
            
            // Try POST request as fallback (common in Arabic sites)
            if (!foundLinks) {
                try {
                    val postDoc = app.post(data, data = mapOf("view" to "1", "load" to "video")).document
                    
                    postDoc.select("a[href*='.mp4'], a[href*='.m3u8'], iframe").forEach { element ->
                        val url = if (element.tagName() == "iframe") element.attr("src") else element.attr("href")
                        if (url.isNotBlank() && url.contains("http")) {
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
