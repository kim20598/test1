package com.egydead

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class EgyDead : MainAPI() {
    override var mainUrl = "https://egydead.skin"
    override var name = "EgyDead"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private fun String.toAbsolute(): String {
        return when {
            this.startsWith("http") -> this
            this.startsWith("//") -> "https:$this"
            else -> "$mainUrl${if (this.startsWith("/")) "" else "/"}$this"
        }
    }

    // STEP 1: MAIN PAGE ITEMS
    override val mainPage = mainPageOf(
        "$mainUrl/" to "أحدث المحتوى"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) "$mainUrl/page/$page/" else mainUrl
        val document = app.get(url).document

        val items = document.select("div.item").mapNotNull { item ->
            // EXACT SELECTORS FROM YOUR HTML
            val link = item.selectFirst("a") ?: return@mapNotNull null
            val href = link.attr("href").toAbsolute()
            
            // Title is in <h1> inside .sliderBoxInfo
            val title = item.selectFirst(".sliderBoxInfo h1")?.text()?.trim() 
                ?: item.selectFirst("h1")?.text()?.trim()
                ?: "Unknown"
            
            // Poster is direct <img> inside the item
            val poster = item.selectFirst("img")?.attr("src")?.toAbsolute()

            if (title.isBlank() || href.isBlank()) return@mapNotNull null

            // Type detection based on title text
            val type = if (title.contains("مسلسل")) TvType.TvSeries else TvType.Movie

            newMovieSearchResponse(title, href, type) {
                this.posterUrl = poster
            }
        }

        return newHomePageResponse(request.name, items)
    }

    // STEP 2: SEARCH
    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=${query}").document
        
        return document.select("div.item").mapNotNull { item ->
            val link = item.selectFirst("a") ?: return@mapNotNull null
            val href = link.attr("href").toAbsolute()
            val title = item.selectFirst(".sliderBoxInfo h1")?.text()?.trim() ?: "Unknown"
            val poster = item.selectFirst("img")?.attr("src")?.toAbsolute()

            if (title.isBlank() || href.isBlank()) return@mapNotNull null

            val type = if (title.contains("مسلسل")) TvType.TvSeries else TvType.Movie

            newMovieSearchResponse(title, href, type) {
                this.posterUrl = poster
            }
        }
    }

    // STEP 3: LOAD DETAILS
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        
        // Get title from detail page
        val title = document.selectFirst("h1.entry-title")?.text()?.trim() 
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: "Unknown"
        
        // Get poster
        val poster = document.selectFirst("img[src*='wp-content']")?.attr("src")?.toAbsolute()
        
        // Get plot
        val plot = document.selectFirst("div.entry-content")?.text()?.trim()

        // Check if it's a series by looking for episodes
        val episodes = document.select(".episode-list a, a[href*='/episode/']").mapNotNull { episodeLink ->
            val episodeUrl = episodeLink.attr("href").toAbsolute()
            val episodeTitle = episodeLink.text().trim()
            val episodeNumber = extractEpisodeNumber(episodeTitle) ?: 1

            newEpisode(episodeUrl) {
                this.name = episodeTitle
                this.episode = episodeNumber
                this.posterUrl = poster
            }
        }

        val isSeries = episodes.isNotEmpty() || title.contains("مسلسل")

        return if (isSeries) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
            }
        }
    }

    private fun extractEpisodeNumber(text: String): Int? {
        return Regex("""\d+""").find(text)?.value?.toIntOrNull()
    }

    // STEP 4: LOAD LINKS
    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val document = app.get(data).document

        // Extract from download servers
        document.select("ul.donwload-servers-list li").forEach { server ->
            val downloadLink = server.selectFirst("a.ser-link")?.attr("href")?.toAbsolute()
            if (!downloadLink.isNullOrBlank()) {
                loadExtractor(downloadLink, data, subtitleCallback, callback)
            }
        }

        return true
    }
}
