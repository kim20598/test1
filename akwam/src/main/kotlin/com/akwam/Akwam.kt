package com.akwam

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class Akwam : MainAPI() {
    override var mainUrl = "https://ak.sv"
    override var name = "Akwam"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/movies" to "الأفلام",
        "$mainUrl/series" to "المسلسلات"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data).document
        val movies = doc.select("div.movieBox, div.seriesBox").mapNotNull {
            val link = it.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val title = it.selectFirst("h3")?.text() ?: "No title"
            val poster = it.selectFirst("img")?.attr("data-src") ?: ""
            MovieSearchResponse(title, link, this.name, TvType.Movie, poster)
        }
        return newHomePageResponse(request.name, movies)
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1")?.text() ?: "Akwam Video"
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
        val description = doc.selectFirst("meta[property=og:description]")?.attr("content")

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = description
        }
    }
}
