package com.moviztime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup

class MovizTime : MainAPI() {
    override var mainUrl = "https://moviz-time.live"
    override var name = "MovizTime"
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
        "$mainUrl/page/" to "أحدث الإضافات"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "${request.data}$page"
        val doc = app.get(url).document

        val items = doc.select("article.pinbox").mapNotNull {
            val title = it.selectFirst(".title-2 a")?.text()?.trim() ?: return@mapNotNull null
            val href = it.selectFirst(".title-2 a")?.attr("href")?.toAbsolute() ?: return@mapNotNull null
            val poster = it.selectFirst(".preview img")?.attr("src")?.toAbsolute()
            val quality = it.selectFirst("._quality_tag")?.text()

            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
                this.quality = getQualityFromString(quality)
            }
        }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=${query.replace(" ", "+")}"
        val doc = app.get(url).document

        return doc.select("article.pinbox").mapNotNull {
            val title = it.selectFirst(".title-2 a")?.text()?.trim() ?: return@mapNotNull null
            val href = it.selectFirst(".title-2 a")?.attr("href")?.toAbsolute() ?: return@mapNotNull null
            val poster = it.selectFirst(".preview img")?.attr("src")?.toAbsolute()
            val quality = it.selectFirst("._quality_tag")?.text()
            val type = if (href.contains("/anime/") || href.contains("/series/")) TvType.TvSeries else TvType.Movie

            newMovieSearchResponse(title, href, type) {
                this.posterUrl = poster
                this.quality = getQualityFromString(quality)
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1.entry-title")?.text() ?: "غير معروف"
        val poster = doc.selectFirst(".poster img, .preview img")?.attr("src")?.toAbsolute()
        val plot = doc.selectFirst("div.entry-content p, .story p")?.text()

        val isSeries = url.contains("/series/") || url.contains("/anime/")

        return if (!isSeries) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
            }
        } else {
            val episodes = doc.select(".episode-list a").mapNotNull { ep ->
                val epTitle = ep.text()
                val epUrl = ep.attr("href").toAbsolute()
                newEpisode(epUrl) {
                    this.name = epTitle
                }
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
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
        val iframeLinks = doc.select("iframe, a[href*='.m3u8'], a[href*='.mp4']")
            .mapNotNull { it.attr("src").ifBlank { it.attr("href") }?.toAbsolute() }

        iframeLinks.forEach { link ->
            loadExtractor(link, data, subtitleCallback, callback)
        }

        return iframeLinks.isNotEmpty()
    }
}
