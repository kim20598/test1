package com.egydead

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class EgyDead : MainAPI() {
    override var mainUrl = "https://egydead.skin"
    override var name = "EgyDead"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    private fun String.toAbsolute(): String {
        return when {
            startsWith("http") -> this
            startsWith("//") -> "https:$this"
            else -> "$mainUrl${if (startsWith("/")) "" else "/"}$this"
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/category/افلام-اجنبي-اونلاين" to "أفلام أجنبية",
        "$mainUrl/series-category/مسلسلات-اجنبي-1" to "مسلسلات أجنبية",
        "$mainUrl/category/افلام-انمي" to "أفلام أنمي",
        "$mainUrl/series-category/مسلسلات-انمي" to "مسلسلات أنمي"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}/page/$page"
        val doc = app.get(url).document

        val items = doc.select("li.movieItem a").mapNotNull {
            val href = it.attr("href").toAbsolute()
            val title = it.selectFirst("h1.BottomTitle")?.text() ?: return@mapNotNull null
            val poster = it.selectFirst("img")?.attr("src")?.toAbsolute()
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
            }
        }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=${query.replace(" ", "+")}"
        val doc = app.get(url).document

        return doc.select("li.movieItem a").mapNotNull {
            val href = it.attr("href").toAbsolute()
            val title = it.selectFirst("h1.BottomTitle")?.text() ?: return@mapNotNull null
            val poster = it.selectFirst("img")?.attr("src")?.toAbsolute()
            val type = if (href.contains("/movie/")) TvType.Movie else TvType.TvSeries
            newMovieSearchResponse(title, href, type) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst(".singleTitle em")?.text() ?: "غير معروف"
        val poster = doc.selectFirst(".single-thumbnail img")?.attr("src")?.toAbsolute()
        val plot = doc.selectFirst(".extra-content p")?.text()

        // Check if it's a TV series with episodes
        val episodes = doc.select(".episodes-list .EpsList li a").map {
            val epUrl = it.attr("href").toAbsolute()
            val name = it.text()
            newEpisode(epUrl) {
                this.name = name
            }
        }

        return if (episodes.isNotEmpty()) {
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document

        // Watch servers
        val streamLinks = doc.select(".serversList li")
            .mapNotNull { it.attr("data-link") }
            .filter { it.isNotBlank() }

        // Download servers
        val downloadLinks = doc.select(".donwload-servers-list a.ser-link")
            .mapNotNull { it.attr("href") }
            .filter { it.isNotBlank() }

        (streamLinks + downloadLinks).forEach { link ->
            loadExtractor(link, data, subtitleCallback, callback)
        }

        return (streamLinks + downloadLinks).isNotEmpty()
    }
}
