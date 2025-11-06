package com.akwam

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup

class Akwam : MainAPI() {
    override var mainUrl = "https://ak.sv"
    override var name = "Akwam"
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

    // 🔍 Search
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?q=${query.replace(" ", "+")}"
        val document = app.get(url).document

        return document.select("div.entry-box").mapNotNull {
            val title = it.selectFirst("h3.entry-title a")?.text() ?: return@mapNotNull null
            val href = it.selectFirst("h3.entry-title a")?.attr("href")?.toAbsolute() ?: return@mapNotNull null
            val poster = it.selectFirst("img")?.attr("data-src")?.toAbsolute()
            val type = if (href.contains("/movie/")) TvType.Movie else TvType.TvSeries

            newMovieSearchResponse(title, href, type) {
                this.posterUrl = poster
            }
        }
    }

    // 🏠 Main page
    override val mainPage = mainPageOf(
        "$mainUrl/movies" to "أفلام",
        "$mainUrl/series" to "مسلسلات",
        "$mainUrl/anime" to "أنمي"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}/page/$page"
        val document = app.get(url).document

        val items = document.select("div.entry-box").mapNotNull {
            val title = it.selectFirst("h3.entry-title a")?.text() ?: return@mapNotNull null
            val href = it.selectFirst("h3.entry-title a")?.attr("href")?.toAbsolute() ?: return@mapNotNull null
            val poster = it.selectFirst("img")?.attr("data-src")?.toAbsolute()
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
            }
        }

        return newHomePageResponse(request.name, items)
    }

    // 📄 Movie page
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title")?.text() ?: "غير معروف"
        val poster = document.selectFirst(".poster img")?.attr("src")?.toAbsolute()
        val plot = document.selectFirst(".story p")?.text()

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    // 🎥 Extract video links (fixed version)
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val downloadLink = document.selectFirst("a[href*=\"/download/\"]")?.attr("href")?.toAbsolute()

        if (downloadLink != null) {
            val downloadPage = app.get(downloadLink).document
            val videoUrl = Regex("""https:\/\/s\d+\.downet\.net\/download\/[^\"]+\.mp4""")
                .find(downloadPage.html())
                ?.value

            if (videoUrl != null) {
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "Akwam Server",
                        url = videoUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.quality = Qualities.P1080.value
                        this.referer = mainUrl
                    }
                )
                return true
            }
        }

        return false
    }
}
