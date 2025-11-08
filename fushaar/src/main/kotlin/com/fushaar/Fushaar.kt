package com.fushaar

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class Fushaar : MainAPI() {
    override var lang = "ar"
    override var mainUrl = "https://fushaar.com"
    override var name = "Fushaar"
    override val usesWebView = false
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie)

    private fun String.getIntFromText(): Int? {
        return Regex("""\d+""").find(this)?.groupValues?.firstOrNull()?.toIntOrNull()
    }
    
    private fun String.cleanTitle(): String {
        return this.replace("مشاهدة وتحميل فلم|مشاهدة وتحميل|اونلاين|مترجم".toRegex(), "").trim()
    }

    private fun Element.toSearchResponse(): SearchResponse {
        val title = select("h3").text().cleanTitle()
        val posterUrl = select("img").attr("src")
        val href = select("a").attr("href")
        
        // Use newMovieSearchResponse instead of deprecated constructor
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl" to "Latest Movies"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(request.data + if (page > 1) page else "").document
        val home = document.select("article").mapNotNull {
            it.toSearchResponse()
        }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.length < 3) return emptyList()
        // Remove encodeUrl - use simple string concatenation
        val doc = app.get("$mainUrl/?s=$query").document
        return doc.select("article").mapNotNull {
            it.toSearchResponse()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        
        val title = doc.selectFirst("h1")?.text()?.cleanTitle() ?: "Unknown Title"
        val isMovie = url.contains("/movie/")
        
        val posterUrl = doc.selectFirst("img")?.attr("src") ?: ""
        
        // Use score instead of deprecated rating
        val score = doc.selectFirst(".rating")?.text()?.getIntFromText()?.let {
            Rating(it.toDouble())
        }
        
        val synopsis = doc.selectFirst(".entry-content")?.text() ?: ""
        val year = doc.selectFirst(".year")?.text()?.getIntFromText()
        
        val tags = doc.select(".genre a").map { it.text() }
        
        if (isMovie) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = posterUrl
                this.plot = synopsis
                this.tags = tags
                this.rating = score
                this.year = year
            }
        } else {
            val episodes = ArrayList<Episode>()
            
            // Simple episode detection using newEpisode
            doc.select("a[href*='episode']").forEach { episodeLink ->
                episodes.add(
                    newEpisode(episodeLink.attr("href")) {
                        name = episodeLink.text()
                        season = 1
                        episode = episodes.size + 1
                    }
                )
            }
            
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl
                this.tags = tags
                this.plot = synopsis
                this.rating = score
                this.year = year
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
            doc.select("a[href*='.mp4'], a[href*='.m3u8']").apmap { link ->
                val url = link.attr("href")
                if (url.isNotBlank()) {
                    foundLinks = true
                    loadExtractor(url, data, subtitleCallback, callback)
                }
            }
            
            // Method 2: Iframe embeds
            doc.select("iframe").apmap { iframe ->
                val src = iframe.attr("src")
                if (src.isNotBlank()) {
                    foundLinks = true
                    loadExtractor(src, data, subtitleCallback, callback)
                }
            }
            
        } catch (e: Exception) {
            // Log error but don't crash
        }
        
        return foundLinks
    }
}
