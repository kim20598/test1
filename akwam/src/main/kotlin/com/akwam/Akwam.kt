package com.akwam

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class Akwam : MainAPI() {
    override var mainUrl = "https://ak.sv"
    override var name = "Akwam"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val home = mutableListOf<HomePageList>()
        val document = app.get(mainUrl).document

        val movies = document.select("div.movies div.item a")
        val homeList = movies.mapNotNull { el ->
            val href = el.attr("href")?.toAbsolute() ?: return@mapNotNull null
            val title = el.selectFirst("h3")?.text() ?: el.attr("title")
            val poster = el.selectFirst("img")?.attr("data-src") ?: el.selectFirst("img")?.attr("src")
            MovieSearchResponse(
                title ?: "Akwam Movie",
                href,
                this.name,
                TvType.Movie,
                poster,
                null,
                null,
                null
            )
        }
        home.add(HomePageList("Movies", homeList))
        return HomePageResponse(home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val res = app.get("$mainUrl/search/?q=${query}")
        val document = res.document
        val results = document.select("div.movies div.item a")
        return results.mapNotNull { el ->
            val href = el.attr("href")?.toAbsolute() ?: return@mapNotNull null
            val title = el.selectFirst("h3")?.text() ?: el.attr("title")
            val poster = el.selectFirst("img")?.attr("data-src") ?: el.selectFirst("img")?.attr("src")
            MovieSearchResponse(
                title ?: "Akwam Movie",
                href,
                this.name,
                TvType.Movie,
                poster,
                null,
                null,
                null
            )
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1")?.text() ?: "Akwam Movie"
        val poster = document.selectFirst("div.poster img")?.attr("src")
        val plot = document.selectFirst("div.story p")?.text()

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
        val document = app.get(data).document
        val watchLink = document.selectFirst("a[href*=\"/download/\"]")?.attr("href")?.toAbsolute()
            ?: return false

        val downloadPage = app.get(watchLink).document
        val videoUrl = Regex("""https:\/\/s\d+\.downet\.net\/download\/[^\"]+\.mp4""")
            .find(downloadPage.html())
            ?.value

        if (videoUrl != null) {
            callback.invoke(
                newExtractorLink(
                    name = "Akwam",
                    source = this.name,
                    url = videoUrl,
                    referer = mainUrl,
                    quality = Qualities.P1080.value,
                    type = ExtractorLinkType.VIDEO
                )
            )
            return true
        }

        return false
    }
}
