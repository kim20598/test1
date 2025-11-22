package com.animezid

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class Animezid : MainAPI() {
    override var lang = "ar"
    override var mainUrl = "https://animezid.cam"
    override var name = "Animezid"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Anime, TvType.Cartoon, TvType.TvSeries)

    // Helper functions
    private fun String.cleanTitle(): String {
        return this.replace("مشاهدة|تحميل|انمي|مترجم|اون لاين|الحلقة|مسلسل".toRegex(), "").trim()
    }

    private fun String.getIntFromText(): Int? {
        return Regex("""\d+""").find(this)?.value?.toIntOrNull()
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val title = this.selectFirst("h3, h2, .title")?.text()?.cleanTitle() ?: return null
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val posterUrl = this.selectFirst("img")?.attr("src")
        
        val fixedHref = when {
            href.startsWith("http") -> href
            href.startsWith("/") -> "$mainUrl$href"
            else -> "$mainUrl/$href"
        }

        return newAnimeSearchResponse(title, fixedHref, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    // Main page
    override val mainPage = mainPageOf(
        "$mainUrl/anime/page/" to "أنمي",
        "$mainUrl/cartoon/page/" to "كرتون",
        "$mainUrl/ongoing/page/" to "مستمر",
        "$mainUrl/completed/page/" to "مكتمل"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("${request.data}$page/").document
        val items = document.select("article, .anime-card, .post-item").mapNotNull {
            it.toSearchResponse()
        }
        return newHomePageResponse(request.name, items)
    }

    // Search
    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("article, .anime-card, .post-item").mapNotNull {
            it.toSearchResponse()
        }
    }

    // Load (series page)
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        
        val title = document.selectFirst("h1")?.text()?.cleanTitle() ?: "Unknown"
        val poster = document.selectFirst(".anime-poster img, img.wp-post-image")?.attr("src")
        val description = document.selectFirst(".anime-description, .entry-content p")?.text()
        val year = document.selectFirst(".year")?.text()?.getIntFromText()
        val tags = document.select(".genres a, a[rel=tag]").map { it.text() }

        // Get episodes
        val episodes = document.select(".episode-list a, .episodes-list a").mapNotNull { ep ->
            val epHref = ep.attr("href")
            val epTitle = ep.text()
            val epNum = epTitle.getIntFromText()

            if (epHref.isBlank()) return@mapNotNull null

            Episode(
                data = epHref,
                name = epTitle,
                episode = epNum
            )
        }

        return if (episodes.isNotEmpty()) {
            newAnimeLoadResponse(title, url, TvType.Anime) {
                addEpisodes(DubStatus.Subbed, episodes.reversed())
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
                this.year = year
            }
        } else {
            newMovieLoadResponse(title, url, TvType.AnimeMovie, url) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
                this.year = year
            }
        }
    }

    // Load links
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        
        // Method 1: Direct iframes
        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotBlank()) {
                loadExtractor(src, data, subtitleCallback, callback)
            }
        }

        // Method 2: Server buttons
        document.select(".server-list li, button[data-server]").forEach { server ->
            val serverUrl = server.attr("data-server")
            if (serverUrl.isNotBlank()) {
                loadExtractor(serverUrl, data, subtitleCallback, callback)
            }
        }

        return true
    }
}
