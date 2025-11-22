package com.animezid

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class Animezid : MainAPI() {
    override var lang = "ar"
    override var mainUrl = "https://animezid.cam"
    override var name = "Animezid"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Anime, TvType.Cartoon)

    private fun String.cleanTitle(): String {
        return this.replace("مشاهدة|تحميل|انمي|مترجم".toRegex(), "").trim()
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val title = selectFirst("h3, h2, .title")?.text()?.cleanTitle() ?: return null
        val href = selectFirst("a")?.attr("href") ?: return null
        val posterUrl = selectFirst("img")?.attr("src")

        val fixedHref = when {
            href.startsWith("http") -> href
            href.startsWith("/") -> "$mainUrl$href"
            else -> "$mainUrl/$href"
        }

        return newAnimeSearchResponse(title, fixedHref, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/anime/page/" to "أنمي",
        "$mainUrl/cartoon/page/" to "كرتون",
        "$mainUrl/ongoing/page/" to "مستمر",
        "$mainUrl/completed/page/" to "مكتمل"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("${request.data}$page/").document
        val items = document.select("article, .anime-card, .post-item").mapNotNull {
            it.toSearchResponse()
        }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("article, .anime-card, .post-item").mapNotNull {
            it.toSearchResponse()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1")?.text()?.cleanTitle() ?: "Unknown"
        val poster = document.selectFirst("img")?.attr("src")
        val description = document.selectFirst(".entry-content p, .synopsis")?.text()
        val tags = document.select(".genres a, a[rel=tag]").map { it.text() }

        val episodes = document.select(".episode-list a, .episodes-list a").mapNotNull { ep ->
            val epHref = ep.attr("href")
            if (epHref.isBlank()) return@mapNotNull null

            newEpisode(epHref) {
                name = ep.text()
            }
        }

        return if (episodes.isNotEmpty()) {
            newAnimeLoadResponse(title, url, TvType.Anime) {
                addEpisodes(DubStatus.Subbed, episodes.reversed())
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
            }
        } else {
            newMovieLoadResponse(title, url, TvType.AnimeMovie, url) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotBlank()) {
                loadExtractor(src, data, subtitleCallback, callback)
            }
        }

        document.select("button[data-server], .server-list li").forEach { server ->
            val serverUrl = server.attr("data-server")
            if (serverUrl.isNotBlank()) {
                loadExtractor(serverUrl, data, subtitleCallback, callback)
            }
        }

        return true
    }
}
