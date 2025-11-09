package com.moviztime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class MovizTime : MainAPI() {
    override var mainUrl = "https://moviz-time.live"
    override var name = "MovizTime"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)
    override val hasDownloadSupport = true

    // ----------------------------- MAIN PAGE -----------------------------
    override val mainPage = mainPageOf(
        "$mainUrl/category/movies/page/" to "أفلام",
        "$mainUrl/category/series/page/" to "مسلسلات",
        "$mainUrl/category/anime/page/" to "أنمي"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "${request.data}$page"
        val doc = app.get(url).document

        val items = doc.select("article.pinbox, div.pinbox").mapNotNull {
            it.toSearchResult()
        }

        return newHomePageResponse(request.name, items)
    }

    // ----------------------------- SEARCH -----------------------------
    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=$query").document
        return doc.select("article.pinbox, div.pinbox").mapNotNull {
            it.toSearchResult()
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val link = selectFirst("a[href]") ?: return null
        val href = link.attr("href").toAbsolute(mainUrl)
        val title = link.attr("title").ifBlank { link.text() }

        val img = selectFirst("img")?.let { imgEl ->
            imgEl.attr("src")
                .ifBlank { imgEl.attr("data-src") }
                .ifBlank { imgEl.attr("data-lazy-src") }
                .toAbsolute(mainUrl)
        }

        val type = when {
            href.contains("/anime/") -> TvType.Anime
            href.contains("/series/") -> TvType.TvSeries
            else -> TvType.Movie
        }

        return newMovieSearchResponse(title, href, type) {
            this.posterUrl = img
        }
    }

    // ----------------------------- LOAD PAGE -----------------------------
    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1.entry-title")?.text() ?: "غير معروف"
        val poster = doc.selectFirst("div.thumb img")?.attr("src")?.toAbsolute(mainUrl)
        val plot = doc.selectFirst("div.entry-content p")?.text()

        val isSeries = url.contains("/series/") || url.contains("/anime/")
        if (isSeries) {
            val episodes = doc.select("ul.episodios li a").mapNotNull {
                val epLink = it.attr("href").toAbsolute(mainUrl)
                val epTitle = it.text()
                newEpisode(epLink) {
                    this.name = epTitle
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
            }
        }
    }

    // ----------------------------- LOAD LINKS -----------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        var foundLinks = false

        // Extract download or streaming links
        doc.select("a[href*='.mp4'], a[href*='.m3u8'], a[href*='vid'], iframe[src]").forEach { el ->
            val link = (el.attr("href").ifBlank { el.attr("src") }).toAbsolute(mainUrl)
            if (link.isNotBlank()) {
                foundLinks = true
                loadExtractor(link, data, subtitleCallback, callback)
            }
        }

        return foundLinks
    }

    // ----------------------------- UTILITIES -----------------------------
    private fun String.toAbsolute(baseUrl: String): String {
        return if (this.startsWith("http")) this
        else if (this.startsWith("/")) "$baseUrl$this"
        else "$baseUrl/$this"
    }
}
