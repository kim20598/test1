package com.moviztime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class MovizTime : MainAPI() {
    override var mainUrl = "https://moviz-time.live"
    override var name = "MovizTime"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private fun String.toAbsolute(): String {
        return when {
            startsWith("http") -> this
            startsWith("//") -> "https:$this"
            else -> "$mainUrl${if (startsWith("/")) "" else "/"}$this"
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/movies" to "أفلام",
        "$mainUrl/series" to "مسلسلات",
        "$mainUrl/anime" to "أنمي"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}/page/$page"
        val document = app.get(url).document

        val items = document.select("article, div.movie, div.item").mapNotNull {
            it.toSearchResponse()
        }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=${query.replace(" ", "+")}"
        val doc = app.get(url).document

        return doc.select("article, div.movie, div.item").mapNotNull {
            it.toSearchResponse()
        }
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val title = selectFirst("h3, h2, .title a")?.text()?.trim() ?: return null
        val href = selectFirst("a")?.attr("href")?.toAbsolute() ?: return null
        val poster = selectFirst("img")?.attr("src")?.toAbsolute()

        val type = if (href.contains("/movie/")) TvType.Movie else TvType.TvSeries

        return newMovieSearchResponse(title, href, type) {
            this.posterUrl = poster
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1, .post-title")?.text()?.trim() ?: "غير معروف"
        val poster = doc.selectFirst("img.size-full, .poster img")?.attr("src")?.toAbsolute()
        val plot = doc.selectFirst(".story p, .description, .post-content p")?.text()?.trim()
        val isSeries = url.contains("/series/") || doc.select("ul.episodes li").isNotEmpty()

        return if (isSeries) {
            val episodes = arrayListOf<Episode>()
            doc.select("ul.episodes li a").forEach {
                val epHref = it.attr("href").toAbsolute()
                val epTitle = it.text().trim()
                episodes.add(newEpisode(epHref) {
                    this.name = epTitle
                    this.season = 1
                })
            }

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

        // Extract iframes or direct sources
        doc.select("iframe, a[href*='.m3u8'], a[href*='.mp4']").forEach {
            val src = it.attr("src").ifEmpty { it.attr("href") }.toAbsolute()
            loadExtractor(src, data, subtitleCallback, callback)
        }

        return true
    }
}
