package com.akwam.utils

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup

object AkwamParser : MainAPI() {
    override var mainUrl = "https://akwam.to"
    override var name = "Akwam"
    override val supportedTypes = setOf(TvType.Movie)
    override val lang = "ar"

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/search?q=$query").document
        return doc.select(".entry").mapNotNull { el ->
            val title = el.selectFirst(".entry-title a")?.text() ?: return@mapNotNull null
            val href = el.selectFirst(".entry-title a")?.attr("href") ?: return@mapNotNull null
            val poster = el.selectFirst("img")?.attr("src")

            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1")?.text() ?: "Unknown"
        val poster = doc.selectFirst(".poster img")?.attr("src")
        val plot = doc.selectFirst(".story")?.text()

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
        }
    }
}
