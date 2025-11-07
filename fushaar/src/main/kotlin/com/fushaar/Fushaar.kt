package com.fushaar

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class Fushaar : MainAPI() {
    override var lang = "ar"
    override var mainUrl = "https://www.fushaar.com"
    override var name = "Fushaar"
    override val usesWebView = false
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    override val mainPage = mainPageOf(
        "$mainUrl/movies/" to "Movies",
        "$mainUrl/series/" to "Series"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document
        val items = document.select("article").mapNotNull { it.toSearchResponse() }
        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val titleElement = select("h2 a").firstOrNull() ?: return null
        val title = titleElement.text()
        val href = titleElement.attr("href")
        val poster = select("img").attr("src")
        val type = if (href.contains("/movie/")) TvType.Movie else TvType.TvSeries
        
        return newMovieSearchResponse(title, href, type) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("article").mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        
        val title = document.select("h1").firstOrNull()?.text() ?: "Unknown Title"
        val poster = document.select(".post-thumbnail img, img.wp-post-image").firstOrNull()?.attr("src") ?: ""
        val plot = document.select(".entry-content").firstOrNull()?.text() ?: ""
        
        val isMovie = url.contains("/movie/")
        
        if (isMovie) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
            }
        } else {
            val episodes = document.select(".episode-list a").mapNotNull { episode ->
                val episodeUrl = episode.attr("href")
                val episodeText = episode.text()
                val episodeNumber = episodeText.filter { it.isDigit() }.toIntOrNull() ?: 1
                
                newEpisode(episodeUrl) {
                    name = episodeText
                    episode = episodeNumber
                    season = 1
                }
            }
            
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val iframe = document.select("iframe").firstOrNull()?.attr("src") ?: ""
        
        if (iframe.isNotEmpty()) {
            loadExtractor(iframe, data, subtitleCallback, callback)
            return true
        }
        return false
    }
}
