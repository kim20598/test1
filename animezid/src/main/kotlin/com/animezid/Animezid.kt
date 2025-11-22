package com.animezid

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

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
        "$mainUrl/movies/" to "أفلام أنمي"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document
        val items = document.select("a.movie").mapNotNull { it.toSearchResponse() }
        return newHomePageResponse(request.name, items, hasNext = false)
    }

    // ==================== SEARCH ====================

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search.php?keywords=${URLEncoder.encode(query, "UTF-8")}"
        val document = app.get(searchUrl).document
        return document.select("a.movie").mapNotNull { it.toSearchResponse() }
    }

    // ==================== LOAD ====================

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        
        val title = document.selectFirst("h1 span[itemprop=name]")?.text() 
            ?: document.selectFirst("h1")?.text() 
            ?: throw ErrorLoadingException("No title found")

        val poster = document.selectFirst("img.lazy")?.attr("data-src")?.let { fixUrl(it) } ?: ""
        val description = document.selectFirst(".pm-video-description")?.text()?.trim() ?: ""
        val year = document.selectFirst("a[href*='filter=years']")?.text()?.toIntOrNull()

        // Check if it's a movie or series
        val hasEpisodes = document.select(".SeasonsEpisodes a").isNotEmpty()
        
        return if (hasEpisodes) {
            // TV Series
            val episodes = document.select(".SeasonsEpisodes a").mapNotNull { episodeElement ->
                val episodeUrl = fixUrl(episodeElement.attr("href"))
                val episodeNum = episodeElement.select("em").text().toIntOrNull() ?: return@mapNotNull null
                val episodeTitle = episodeElement.select("span").text().takeIf { it.isNotBlank() } ?: "الحلقة $episodeNum"
                
                Episode(episodeUrl, episodeTitle, episodeNum)
            }.distinctBy { it.episode }

            newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
            }
        } else {
            // Movie
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
            }
        }
    }

    // ==================== LOAD LINKS - SIMPLIFIED & DIRECT ====================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        var foundLinks = false

        // METHOD 1: Direct server buttons extraction
        document.select("#xservers button").forEach { serverButton ->
            val embedUrl = serverButton.attr("data-embed").trim()
            if (embedUrl.isNotBlank()) {
                val fixedUrl = fixUrl(embedUrl)
                // Directly pass to extractor
                loadExtractor(fixedUrl, data, subtitleCallback, callback)
                foundLinks = true
            }
        }

        // METHOD 2: Direct iframe extraction  
        if (!foundLinks) {
            document.select("#Playerholder iframe").forEach { iframe ->
                val iframeSrc = iframe.attr("src").trim()
                if (iframeSrc.isNotBlank()) {
                    val fixedUrl = fixUrl(iframeSrc)
                    loadExtractor(fixedUrl, data, subtitleCallback, callback)
                    foundLinks = true
                }
            }
        }

        // METHOD 3: Download links as direct sources
        if (!foundLinks) {
            document.select("a.dl.show_dl.api").forEach { downloadLink ->
                val downloadUrl = downloadLink.attr("href").trim()
                if (downloadUrl.isNotBlank() && downloadUrl.startsWith("http")) {
                    val quality = downloadLink.select("span").firstOrNull()?.text() ?: "1080p"
                    val host = downloadLink.select("span").lastOrNull()?.text() ?: "Download"
                    
                    callback(
                        ExtractorLink(
                            source = name,
                            name = "$host - $quality",
                            url = downloadUrl,
                            referer = data,
                            quality = getQualityFromName(quality),
                            type = ExtractorLinkType.VIDEO
                        )
                    )
                    foundLinks = true
                }
            }
        }

        return foundLinks
    }

    // ==================== HELPER FUNCTIONS ====================

    private fun Element.toSearchResponse(): SearchResponse? {
        val title = this.attr("title").takeIf { it.isNotBlank() } 
            ?: this.select(".title").text().takeIf { it.isNotBlank() }
            ?: return null
            
        val href = this.attr("href").takeIf { it.isNotBlank() } ?: return null
        val poster = this.select("img.lazy").attr("data-src")
        
        val isMovie = title.contains("فيلم") || href.contains("/movie/")

        return if (isMovie) {
            newMovieSearchResponse(title, fixUrl(href), TvType.Movie) {
                this.posterUrl = fixUrl(poster)
            }
        } else {
            newTvSeriesSearchResponse(title, fixUrl(href), TvType.Anime) {
                this.posterUrl = fixUrl(poster)
            }
        }
    }

    private fun fixUrl(url: String): String {
        return when {
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$mainUrl$url"
            else -> "$mainUrl/$url"
        }
    }
}
