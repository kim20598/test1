package com.fushaar

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class Fushaar : MainAPI() {
    override var lang = "ar"
    override var mainUrl = "https://fushaar.com"
    override var name = "Fushaar"
    override val usesWebView = false
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

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
        
        val tvType = if (href.contains("/movie/")) TvType.Movie else TvType.TvSeries
        
        return MovieSearchResponse(
            title,
            href,
            this@Fushaar.name,
            tvType,
            posterUrl,
        )
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
        val doc = app.get("$mainUrl/?s=${encodeUrl(query)}").document
        return doc.select("article").mapNotNull {
            it.toSearchResponse()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        
        val title = doc.selectFirst("h1")?.text()?.cleanTitle() ?: "Unknown Title"
        val isMovie = url.contains("/movie/")
        
        val posterUrl = doc.selectFirst("img")?.attr("src") ?: ""
        
        val rating = doc.selectFirst(".rating")?.text()?.getIntFromText()
        val synopsis = doc.selectFirst(".entry-content")?.text() ?: ""
        val year = doc.selectFirst(".year")?.text()?.getIntFromText()
        
        val tags = doc.select(".genre a").map { it.text() }
        
        if (isMovie) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = posterUrl
                this.plot = synopsis
                this.tags = tags
                this.rating = rating
                this.year = year
            }
        } else {
            val episodes = ArrayList<Episode>()
            
            // Simple episode detection
            doc.select("a[href*='episode']").forEach { episodeLink ->
                episodes.add(
                    Episode(
                        episodeLink.attr("href"),
                        episodeLink.text(),
                        1,
                        episodes.size + 1
                    )
                )
            }
            
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl
                this.tags = tags
                this.plot = synopsis
                this.rating = rating
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
            doc.select("a[href*='.mp4'], a[href*='.m3u8']").forEach { link ->
                val url = link.attr("href")
                if (url.isNotBlank()) {
                    foundLinks = true
                    loadExtractor(url, data, subtitleCallback, callback)
                }
            }
            
            // Method 2: Iframe embeds
            doc.select("iframe").forEach { iframe ->
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
