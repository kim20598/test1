package com.akwam.utils

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup

object AkwamParser {
    suspend fun searchMovies(query: String): List<SearchResponse> {
        val url = "https://akwam.to/search?q=$query"
        val doc = app.get(url).document
        val results = mutableListOf<SearchResponse>()

        for (movie in doc.select(".entry")) {
            val name = movie.selectFirst(".entry-title a")?.text() ?: continue
            val href = movie.selectFirst(".entry-title a")?.attr("href") ?: continue
            val poster = movie.selectFirst("img")?.attr("src")

            results.add(
                newMovieSearchResponse(
                    name = name,
                    url = href,
                    type = TvType.Movie
                ) {
                    this.posterUrl = poster
                }
            )
        }
        return results
    }

    suspend fun loadMovie(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1")?.text() ?: "Unknown"

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            posterUrl = doc.selectFirst(".poster img")?.attr("src")
            plot = doc.selectFirst(".story")?.text()
        }
    }
}
