package com.fushaar

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class Fushaar : MainAPI() {
    override var mainUrl = "https://fushaar.com"
    override var name = "Fushaar"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private fun String.toAbsolute(): String {
        return when {
            this.startsWith("http") -> this
            this.startsWith("//") -> "https:$this"
            else -> "$mainUrl${if (this.startsWith("/")) "" else "/"}$this"
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/category/افلام-اجنبي" to "أفلام أجنبية",
        "$mainUrl/category/افلام-اسيوية" to "أفلام آسيوية",
        "$mainUrl/category/افلام-انمي" to "أفلام أنمي",
        "$mainUrl/series" to "مسلسلات"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}/page/$page"
        val document = app.get(url).document

        val items = document.select("article").mapNotNull {
            it.toSearchResponse()
        }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=${query.replace(" ", "+")}"
        val doc = app.get(url).document

        return doc.select("article").mapNotNull {
            it.toSearchResponse()
        }
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val title = selectFirst("h3")?.text()?.trim() ?: return null
        val href = selectFirst("a")?.attr("href")?.toAbsolute() ?: return null
        val poster = selectFirst("img")?.attr("src")?.toAbsolute()
        val type = if (href.contains("/movie/")) TvType.Movie else TvType.TvSeries

        return newMovieSearchResponse(title, href, type) {
            this.posterUrl = poster
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1")?.text()?.trim() ?: "غير معروف"
        val poster = doc.selectFirst("img.size-full")?.attr("src")?.toAbsolute()
        val plot = doc.selectFirst("div.entry-content p")?.text()?.trim()

        val isSeries = url.contains("/series/") || doc.select("ul.episodes li").isNotEmpty()

        return if (isSeries) {
            val episodes = arrayListOf<Episode>()
            doc.select("ul.episodes li a").forEach {
                val epHref = it.attr("href").toAbsolute()
                val epTitle = it.text().trim()

                episodes.add(newEpisode(epHref) {
                    this.name = epTitle
                    this.season = 1
                    this.episode = 0
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

        // try embedded iframes
        doc.select("iframe").forEach {
            val src = it.attr("src").toAbsolute()
            loadExtractor(src, data, subtitleCallback, callback)
        }

        // try direct download links
        doc.select("a[href*='.mp4'], a[href*='.m3u8']").forEach {
            val link = it.attr("href").toAbsolute()
            loadExtractor(link, data, subtitleCallback, callback)
        }

        return true
    }
}
