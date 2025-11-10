package com.fushaar

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import java.net.URLEncoder

class Fushaar : MainAPI() {
    override var mainUrl = "https://fushaar.com"
    override var name = "Fushaar"
    override val usesWebView = false
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "ar"

    // 🎯 UTILITY FUNCTIONS (BUILD-SAFE)
    private fun String.getIntFromText(): Int? {
        return try {
            Regex("""\d+""").find(this)?.groupValues?.firstOrNull()?.toIntOrNull()
        } catch (e: Exception) {
            null
        }
    }
    
    private fun String.cleanTitle(): String {
        return try {
            this.replace("مشاهدة|فيلم|مسلسل|مترجم|كامل|اونلاين".toRegex(), "").trim()
        } catch (e: Exception) {
            this.trim()
        }
    }

    // 🖼️ SEARCH RESPONSE BUILDER (BUILD-SAFE)
    private fun Element.toSearchResponse(): SearchResponse? {
        return try {
            val titleElement = select("h2, h3, .post-title").firstOrNull()
            val title = titleElement?.text()?.cleanTitle() ?: return null
            
            val linkElement = select("a").firstOrNull()
            val href = linkElement?.attr("href") ?: return null
            
            val posterElement = select("img").firstOrNull()
            val posterUrl = posterElement?.attr("src") ?: ""
            
            // Determine content type
            val tvType = if (href.contains("/movie/", true)) {
                TvType.Movie
            } else {
                TvType.TvSeries
            }
            
            if (tvType == TvType.TvSeries) {
                newTvSeriesSearchResponse(title, href, tvType) {
                    this.posterUrl = posterUrl
                }
            } else {
                newMovieSearchResponse(title, href, tvType) {
                    this.posterUrl = posterUrl
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    // 🏠 MAIN PAGE SECTIONS (BUILD-SAFE)
    override val mainPage = mainPageOf(
        "$mainUrl/" to "أحدث المحتوى",
        "$mainUrl/movies/" to "الأفلام",
        "$mainUrl/series/" to "المسلسلات"
    )

    // 📄 MAIN PAGE LOADER (BUILD-SAFE)
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return try {
            val url = if (page > 1) "${request.data}page/$page/" else request.data
            val document = app.get(url).document
            
            val home = document.select("article").mapNotNull { element ->
                element.toSearchResponse()
            }
            
            newHomePageResponse(request.name, home)
        } catch (e: Exception) {
            newHomePageResponse(request.name, emptyList())
        }
    }

    // 🔍 SEARCH FUNCTIONALITY (BUILD-SAFE)
    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            if (query.length < 2) return emptyList()
            
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val searchUrl = "$mainUrl/?s=$encodedQuery"
            val document = app.get(searchUrl).document
            
            document.select("article").mapNotNull { element ->
                element.toSearchResponse()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // 📺 LOAD DETAILED CONTENT (BUILD-SAFE)
    override suspend fun load(url: String): LoadResponse {
        return try {
            val document = app.get(url).document
            
            val titleElement = document.selectFirst("h1, .entry-title")
            val title = titleElement?.text()?.cleanTitle() ?: "Unknown Title"
            
            val posterElement = document.selectFirst(".post-thumbnail img, .wp-post-image")
            val posterUrl = posterElement?.attr("src") ?: ""
            
            val descriptionElement = document.selectFirst(".entry-content, .post-content")
            val description = descriptionElement?.text() ?: ""
            
            val isMovie = url.contains("/movie/", true)
            
            if (isMovie) {
                newMovieLoadResponse(title, url, TvType.Movie, url) {
                    this.posterUrl = posterUrl
                    this.plot = description
                }
            } else {
                val episodes = mutableListOf<Episode>()
                
                // Simple episode extraction
                document.select(".episodes a, .episode-list a").forEach { episodeElement ->
                    val episodeUrl = episodeElement.attr("href")
                    if (episodeUrl.isNotBlank()) {
                        val episodeName = episodeElement.attr("title").ifBlank { episodeElement.text() }
                        val episodeNumber = episodeElement.text().getIntFromText() ?: 1
                        
                        episodes.add(
                            newEpisode(episodeUrl) {
                                this.name = episodeName
                                this.season = 1
                                this.episode = episodeNumber
                            }
                        )
                    }
                }
                
                // If no episodes found, add default
                if (episodes.isEmpty()) {
                    episodes.add(
                        newEpisode(url) {
                            this.name = "الحلقة 1"
                            this.season = 1
                            this.episode = 1
                        }
                    )
                }
                
                newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                    this.posterUrl = posterUrl
                    this.plot = description
                }
            }
        } catch (e: Exception) {
            // Fallback response
            newMovieLoadResponse("Error Loading", url, TvType.Movie, url) {
                this.posterUrl = ""
                this.plot = "Failed to load content"
            }
        }
    }

    // 🔗 LOAD VIDEO LINKS (BUILD-SAFE)
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            var foundLinks = false
            val document = app.get(data).document
            
            // Method 1: Direct MP4 links
            document.select("a[href*='.mp4']").forEach { link ->
                val url = link.attr("href")
                if (url.isNotBlank() && url.startsWith("http")) {
                    foundLinks = true
                    
                    val quality = when {
                        link.text().contains("1080") || link.text().contains("FullHD") -> Qualities.FullHDP.value
                        link.text().contains("480") || link.text().contains("Web") -> Qualities.480P.value
                        link.text().contains("240") || link.text().contains("SD") -> Qualities.240P.value
                        else -> Qualities.Unknown.value
                    }
                    
                    callback.invoke(
                        ExtractorLink(
                            name,
                            "Direct - ${quality}p",
                            url,
                            mainUrl,
                            quality,
                            false
                        )
                    )
                }
            }
            
            // Method 2: Iframe embeds
            document.select("iframe").forEach { iframe ->
                val src = iframe.attr("src")
                if (src.isNotBlank() && src.startsWith("http") && !src.contains("about:blank")) {
                    foundLinks = true
                    loadExtractor(src, data, subtitleCallback, callback)
                }
            }
            
            // Method 3: Download links
            document.select("a[href*='fushaar.link'], a[href*='uptobox.com']").forEach { link ->
                val url = link.attr("href")
                if (url.isNotBlank() && url.startsWith("http")) {
                    foundLinks = true
                    loadExtractor(url, data, subtitleCallback, callback)
                }
            }
            
            foundLinks
        } catch (e: Exception) {
            false
        }
    }
}