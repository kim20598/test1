package com.akwam.utils

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

object AkwamParser {
    private const val BASE_URL = "https://ak.sv"

    suspend fun searchMovies(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        val doc = app.get("$BASE_URL/search?q=$query").document

        doc.select("div.movie").forEach {
            val title = it.selectFirst("h3")?.text() ?: return@forEach
            val href = it.selectFirst("a")?.attr("href") ?: return@forEach
            val poster = it.selectFirst("img")?.attr("data-src") ?: ""
            results.add(
                MovieSearchResponse(
                    name = title,
                    url = href,
                    apiName = "Akwam",
                    type = TvType.Movie,
                    posterUrl = poster
                )
            )
        }

        return results
    }

    suspend fun loadMovie(url: String): LoadResponse? {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1")?.text() ?: return null
        val poster = doc.selectFirst("img.poster")?.attr("src")
        val description = doc.selectFirst("p.story")?.text()
        val links = doc.select("a.download-link").map { it.attr("href") }

        return newMovieLoadResponse(
            name = title,
            url = url,
            apiName = "Akwam",
            posterUrl = poster
        ) {
            plot = description
            this.dataUrl = url
        }
    }
}
