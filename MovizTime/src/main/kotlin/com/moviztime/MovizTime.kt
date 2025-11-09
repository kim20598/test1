package com.moviztime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup

class MovizTime : MainAPI() {
    override var mainUrl = "https://moviz-time.live"
    override var name = "MovizTime"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    private fun String.toAbsoluteUrl(): String {
        return when {
            this.startsWith("http") -> this
            this.startsWith("//") -> "https:$this"
            else -> "$mainUrl${if (this.startsWith("/")) "" else "/"}$this"
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/category/%d8%a3%d9%81%d9%84%d8%a7%d9%85-2025/" to "أفلام 2025",
        "$mainUrl/category/%d8%a3%d9%81%d9%84%d8%a7%d9%85-2024/" to "أفلام 2024",
        "$mainUrl/category/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%a3%d8%ac%d9%86%d8%a8%d9%8a%d8%a9-%d9%85%d8%aa%d8%b1%d8%ac%d9%85%d8%a9-e/" to "مسلسلات أجنبية",
        "$mainUrl/category/%d9%82%d8%a7%d8%a6%d9%85%d8%a9-%d8%a7%d9%84%d8%a3%d9%86%d9%85%d9%8a-b/%d8%a3%d9%81%d9%84%d8%a7%d9%85-%d8%a3%d9%86%d9%85%d9%8a/" to "أفلام أنمي",
        "$mainUrl/category/%d9%82%d8%a7%d8%a6%d9%85%d8%a9-%d8%a7%d9%84%d8%a3%d9%86%d9%85%d9%8a-b/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%a3%d9%86%d9%85%d9%8a/" to "مسلسلات أنمي"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}page/$page/"
        val doc = app.get(url).document

        val items = doc.select("article.pinbox").mapNotNull { article ->
            val link = article.selectFirst("a[href]")?.attr("href")?.toAbsoluteUrl() ?: return@mapNotNull null
            val title = article.selectFirst("a[title]")?.attr("title") ?: return@mapNotNull null
            val poster = article.selectFirst("img")?.attr("src")?.toAbsoluteUrl()
            val type = when {
                link.contains("/anime") -> TvType.Anime
                link.contains("/series") -> TvType.TvSeries
                else -> TvType.Movie
            }

            newMovieSearchResponse(title, link, type) {
                this.posterUrl = poster
            }
        }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=${query.replace(" ", "+")}"
        val doc = app.get(url).document

        return doc.select("article.pinbox").mapNotNull { article ->
            val link = article.selectFirst("a[href]")?.attr("href")?.toAbsoluteUrl() ?: return@mapNotNull null
            val title = article.selectFirst("a[title]")?.attr("title") ?: return@mapNotNull null
            val poster = article.selectFirst("img")?.attr("src")?.toAbsoluteUrl()
            val type = when {
                link.contains("/anime") -> TvType.Anime
                link.contains("/series") -> TvType.TvSeries
                else -> TvType.Movie
            }

            newMovieSearchResponse(title, link, type) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1.entry-title, h1.single-title")?.text() ?: "غير معروف"
        val poster = doc.selectFirst("img.wp-post-image, .single-thumbnail img")?.attr("src")?.toAbsoluteUrl()
        val plot = doc.selectFirst("div.entry-content p, .extra-content p")?.text()

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

        val links = doc.select("a[href*='m3u8'], a[href*='mp4'], iframe[src]").mapNotNull {
            it.attr("href").ifEmpty { it.attr("src") }.toAbsoluteUrl()
        }

        links.forEach { link ->
            loadExtractor(link, data, subtitleCallback, callback)
        }

        return links.isNotEmpty()
    }
}
