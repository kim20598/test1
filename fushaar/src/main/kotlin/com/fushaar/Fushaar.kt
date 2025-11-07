package com.fushaar

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup

class Fushaar : MainAPI() {
    override var mainUrl = "https://www.fushaar.live"
    override var name = "Fushaar"
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
        "$mainUrl/movies/" to "أحدث الأفلام",
        "$mainUrl/series/" to "أحدث المسلسلات"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}page/$page/"
        val doc = app.get(url).document

        val items = doc.select("div.item a").mapNotNull {
            val href = it.attr("href").toAbsolute()
            val title = it.selectFirst("h3")?.text() ?: return@mapNotNull null
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

        return doc.select("div.item a").mapNotNull {
            val href = it.attr("href").toAbsolute()
            val title = it.selectFirst("h3")?.text() ?: return@mapNotNull null
            val poster = it.selectFirst("img")?.attr("src")?.toAbsolute()
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1.entry-title")?.text() ?: "غير معروف"
        val poster = doc.selectFirst("img.aligncenter")?.attr("src")?.toAbsolute()
        val plot = doc.selectFirst("div.desc p")?.text()

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        val links = doc.select("a.watch, a.download").mapNotNull { it.attr("href").toAbsolute() }

        links.forEach { link ->
            loadExtractor(link, data, subtitleCallback, callback)
        }

        return links.isNotEmpty()
    }
}
