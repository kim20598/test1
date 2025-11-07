package com.akwam

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class Akwam : MainAPI() {
    override var lang = "ar"
    override var mainUrl = "https://ak.sv"  // Updated domain
    override var name = "Akwam"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    private fun String.getIntFromText(): Int? {
        return Regex("""\d+""").find(this)?.groupValues?.firstOrNull()?.toIntOrNull()
    }

    private fun String.toRatingInt(): Int? {
        return this.trim().toIntOrNull()
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val url = this.select("a").attr("href") ?: return null
        val title = this.select("h3.entry-title a").text() ?: return null
        val posterUrl = this.select("img").attr("data-src")
        val type = if (url.contains("/movie/")) TvType.Movie else TvType.TvSeries

        return newMovieSearchResponse(title, url, type) {
            this.posterUrl = posterUrl
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/movies" to "أفلام",
        "$mainUrl/series" to "مسلسلات"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}/page/$page"
        val doc = app.get(url).document
        val list = doc.select("div.entry-box").mapNotNull { element ->
            element.toSearchResponse()
        }
        return newHomePageResponse(request.name, list)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?q=${query.replace(" ", "+")}"
        val doc = app.get(url).document
        return doc.select("div.entry-box").mapNotNull {
            it.toSearchResponse()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val isMovie = doc.select("#downloads > h2 > span").isNotEmpty()
        val title = doc.select("h1.entry-title").text()
        val posterUrl = doc.select("picture > img").attr("src")

        val year = doc.select("div.font-size-16.text-white.mt-2").firstOrNull {
            it.text().contains("السنة")
        }?.text()?.getIntFromText()

        val duration = doc.select("div.font-size-16.text-white.mt-2").firstOrNull {
            it.text().contains("مدة الفيلم")
        }?.text()?.getIntFromText()

        val synopsis = doc.select("div.widget-body p:first-child").text()

        val rating = doc.select("span.mx-2").text().split("/").lastOrNull()?.toRatingInt()

        val recommendations = doc.select("div > div.widget-body > div.row > div > div.entry-box").mapNotNull {
            val recTitle = it.selectFirst("div.entry-body > .entry-title > .text-white") ?: return@mapNotNull null
            val href = recTitle.attr("href")
            val name = recTitle.text()
            val poster = it.selectFirst(".entry-image > a > picture > img")?.attr("data-src")
            newMovieSearchResponse(name, href, TvType.Movie) {
                this.posterUrl = poster
            }
        }

        return if (isMovie) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = synopsis
                this.rating = rating
                this.duration = duration
                this.recommendations = recommendations
            }
        } else {
            // For series, keep it simple for now
            newMovieLoadResponse(title, url, TvType.TvSeries, url) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = synopsis
                this.rating = rating
                this.duration = duration
                this.recommendations = recommendations
            }
        }
    }

    private fun getQualityFromId(id: Int?): Qualities {
        return when (id) {
            2 -> Qualities.P360
            3 -> Qualities.P480
            4 -> Qualities.P720
            5 -> Qualities.P1080
            else -> Qualities.Unknown
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document

        val links = doc.select("div.tab-content.quality").map { element ->
            val quality = getQualityFromId(element.attr("id").getIntFromText())
            element.select(".col-lg-6 > a:contains(تحميل)").map { linkElement ->
                if (linkElement.attr("href").contains("/download/")) {
                    Pair(linkElement.attr("href"), quality)
                } else {
                    val url = "$mainUrl/download${
                        linkElement.attr("href").split("/link")[1]
                    }${data.split("/movie|/episode".toRegex())[1]}"
                    Pair(url, quality)
                }
            }
        }.flatten()

        links.forEach { linkPair ->
            val linkDoc = app.get(linkPair.first).document
            val button = linkDoc.select("div.btn-loader > a")
            val url = button.attr("href")

            // Use loadExtractor for safe link handling
            loadExtractor(url, data, subtitleCallback, callback)
        }
        
        return true
    }
}
