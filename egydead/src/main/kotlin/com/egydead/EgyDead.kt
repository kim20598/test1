package com.egydead

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup

class EgyDead : MainAPI() {
    override var mainUrl = "https://egydead.skin"
    override var name = "EgyDead"
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
        "$mainUrl/category/movies/" to "أفلام",
        "$mainUrl/category/series/" to "مسلسلات",
        "$mainUrl/category/anime/" to "أنمي"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}/page/$page"
        val document = app.get(url).document

        val items = document.select("div.item").mapNotNull {
            val title = it.selectFirst("h1, h2, h3")?.text()
                ?.takeUnless { t -> t.contains("القائمه", true) || t.contains("الرئيسيه", true) }
                ?: return@mapNotNull null

            val href = it.selectFirst("a")?.attr("href")?.toAbsolute()
                ?: return@mapNotNull null

            val poster = it.selectFirst("img")?.attr("src")?.toAbsolute()

            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
            }
        }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=${query.replace(" ", "+")}"
        val document = app.get(url).document
        return document.select("div.item").mapNotNull {
            val title = it.selectFirst("h1, h2, h3")?.text() ?: return@mapNotNull null
            val href = it.selectFirst("a")?.attr("href")?.toAbsolute() ?: return@mapNotNull null
            val poster = it.selectFirst("img")?.attr("src")?.toAbsolute()
            val type = if (href.contains("/series/")) TvType.TvSeries else TvType.Movie

            newMovieSearchResponse(title, href, type) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title, h1.title")?.text() ?: "غير معروف"
        val poster = document.selectFirst("img.wp-post-image, .poster img")?.attr("src")?.toAbsolute()
        val plot = document.selectFirst("div.story p, div.post-content p")?.text()

        val iframe = document.selectFirst("iframe")?.attr("src")?.toAbsolute()
        val recommendations = document.select("div.item").mapNotNull {
            val recTitle = it.selectFirst("h1, h2, h3")?.text() ?: return@mapNotNull null
            val recHref = it.selectFirst("a")?.attr("href")?.toAbsolute() ?: return@mapNotNull null
            val recPoster = it.selectFirst("img")?.attr("src")?.toAbsolute()
            newMovieSearchResponse(recTitle, recHref, TvType.Movie) {
                this.posterUrl = recPoster
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val iframe = document.selectFirst("iframe")?.attr("src")?.toAbsolute() ?: return false
        loadExtractor(iframe, data, subtitleCallback, callback)
        return true
    }
}
