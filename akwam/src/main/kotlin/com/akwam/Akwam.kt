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

    private fun String.getIntFromText(): Int? {
        return Regex("""(\d+)""").find(this)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun String.toRatingInt(): Int? {
        return this.trim().toIntOrNull()
    }

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

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val mesEl = doc.select("#downloads > h2 > span").isNotEmpty()
        val mesSt = if(mesEl) true else false
        val isMovie = mesSt
        val title = doc.select("h1.entry-title").text()
        val posterUrl = doc.select("picture > img").attr("src").toAbsolute()

        val year = doc.select("div.font-size-16.text-white.mt-2").firstOrNull {
            it.text().contains("السنة")
        }?.text()?.getIntFromText()

        val duration = doc.select("div.font-size-16.text-white.mt-2").firstOrNull {
            it.text().contains("مدة الفيلم")
        }?.text()?.getIntFromText()

        val synopsis = doc.select("div.widget-body p:first-child").text()

        val rating = doc.select("span.mx-2").text().split("/").lastOrNull()?.toRatingInt()

        val tags = doc.select("div.font-size-16.d-flex.align-items-center.mt-3 > a").map {
            it.text()
        }

        val actors = doc.select("div.widget-body > div > div.entry-box > a").mapNotNull {
            val name = it.selectFirst("div > .entry-title")?.text() ?: return@mapNotNull null
            val image = it.selectFirst("div > img")?.attr("src")?.toAbsolute() ?: return@mapNotNull null
            Actor(name, image)
        }

        val recommendations = doc.select("div > div.widget-body > div.row > div > div.entry-box").mapNotNull {
            val recTitle = it.selectFirst("div.entry-body > .entry-title > .text-white") ?: return@mapNotNull null
            val href = recTitle.attr("href").toAbsolute() ?: return@mapNotNull null
            val name = recTitle.text() ?: return@mapNotNull null
            val poster = it.selectFirst(".entry-image > a > picture > img")?.attr("data-src")?.toAbsolute() ?: return@mapNotNull null
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
                this.tags = tags
                this.duration = duration
                this.recommendations = recommendations
                addActors(actors)
            }
        } else {
            // For TV series, we'll keep it simple for now
            newMovieLoadResponse(title, url, TvType.TvSeries, url) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = synopsis
                this.rating = rating
                this.tags = tags
                this.duration = duration
                this.recommendations = recommendations
                addActors(actors)
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
                    Pair(
                        linkElement.attr("href").toAbsolute(),
                        quality,
                    )
                } else {
                    val url = "$mainUrl/download${
                        linkElement.attr("href").split("/link")[1]
                    }${data.split("/movie|/episode|/shows|/show/episode".toRegex())[1]}".toAbsolute()
                    Pair(
                        url,
                        quality,
                    )
                }
            }
        }.flatten()

        links.map { linkPair ->
            val linkDoc = app.get(linkPair.first).document
            val button = linkDoc.select("div.btn-loader > a")
            val url = button.attr("href").toAbsolute()

            callback.invoke(
                ExtractorLink(
                    this.name,
                    this.name,
                    url,
                    this.mainUrl,
                    linkPair.second.value
                )
            )
        }
        return true
    }
}
