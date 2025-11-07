package com.egydead

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup

class EgyDead : MainAPI() {
    override var mainUrl = "https://egydead.skin"
    override var name = "EgyDead"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    private fun String.toAbsolute(): String {
        return when {
            this.startsWith("http") -> this
            this.startsWith("//") -> "https:$this"
            else -> "$mainUrl${if (this.startsWith("/")) "" else "/"}$this"
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
            val title = it.selectFirst("h1.BottomTitle, h2.BottomTitle, .BottomTitle")?.text()
                ?: return@mapNotNull null
            val poster = it.selectFirst("img")?.attr("src")?.toAbsolute()
            val type = when {
                href.contains("/series/") || href.contains("مسلسل") -> TvType.TvSeries
                else -> TvType.Movie
            }

            newMovieSearchResponse(title, href, type) {
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
            val title = it.selectFirst("h1.BottomTitle, h2.BottomTitle, .BottomTitle")?.text()
                ?: return@mapNotNull null
            val poster = it.selectFirst("img")?.attr("src")?.toAbsolute()
            val type = when {
                href.contains("/series/") || href.contains("مسلسل") -> TvType.TvSeries
                else -> TvType.Movie
            }

            newMovieSearchResponse(title, href, type) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst(".singleTitle em, .postTitle, h1.entry-title")?.text() ?: "غير معروف"
        val poster = doc.selectFirst(".single-thumbnail img, .poster img, img.wp-post-image")
            ?.attr("src")?.toAbsolute()
        val plot = doc.selectFirst(".extra-content p, .story p, .postContent p")?.text()

        val type = if (url.contains("series")) TvType.TvSeries else TvType.Movie

        return newMovieLoadResponse(title, url, type, url) {
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

        val linkSelectors = listOf(
            ".serversList li[data-link]",
            ".WatchServers li[data-link]",
            ".DownloadLinks a[href]",
            "a.btn[href*='download']"
        )

        val links = linkSelectors.flatMap { sel ->
            doc.select(sel).mapNotNull {
                (it.attr("data-link").ifBlank { it.attr("href") })
                    ?.takeIf { l -> l.isNotBlank() }?.toAbsolute()
            }
        }.distinct()

        var found = false
        for (link in links) {
            when {
                link.endsWith(".mp4") || link.endsWith(".m3u8") -> {
                    callback.invoke(
                        newExtractorLink(name) {
                            this.url = link
                            this.referer = mainUrl
                            this.quality = getQualityFromName("1080p")
                            this.isM3u8 = link.endsWith(".m3u8")
                        }
                    )
                    found = true
                }

                else -> {
                    val result = loadExtractor(link, data, subtitleCallback, callback)
                    if (result) found = true
                }
            }
        }

        return found
    }
}
