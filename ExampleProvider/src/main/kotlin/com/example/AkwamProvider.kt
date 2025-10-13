package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class AkwamProvider : MainAPI() {
    override var mainUrl               = "https://ak.sv"
    override var name                  = "Akwam"
    override val supportedTypes        = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)
    override val hasMainPage           = true
    override var lang                  = "ar"

    /* ------------------------------------------------------------- */
    /*  3 real rows : latest movies / latest series / dubbed movies  */
    /* ------------------------------------------------------------- */
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val dubMovies = parseRow("أفلام مدبلجة", "$mainUrl/movies?section=1&category=17&page=$page")
        val latestMov = parseRow("أحدث الأفلام", "$mainUrl/movies?page=$page")
        val latestSer = parseRow("أحدث المسلسلات", "$mainUrl/series?page=$page")

        return newHomePageResponse(
            listOf(dubMovies, latestMov, latestSer)
        )
    }

    private suspend fun parseRow(name: String, url: String): HomePageList {
        val doc = app.get(url).document
        val list = doc.select("div.widget-body div.entry-box")
                      .mapNotNull { it.toSearchResult() }
        return HomePageList(name, list)
    }

    /* ------------------------------------------------------------- */
    /*  Search                                                       */
    /* ------------------------------------------------------------- */
    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/search?q=$query").document
        return doc.select("div.widget-body div.entry-box")
                  .mapNotNull { it.toSearchResult() }
    }

    /* ------------------------------------------------------------- */
    /*  Details + episode list                                       */
    /* ------------------------------------------------------------- */
    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        val title       = doc.selectFirst("h1.entry-title")?.text()?.trim()
                            ?: throw ErrorLoadingException("No title")
        val poster      = fixUrlNull(doc.selectFirst("div.poster img")?.attr("src"))
        val year        = doc.selectFirst("ul.entry-meta li:contains(سنة الإصدار)")
                             ?.text()?.filter { it.isDigit() }?.toIntOrNull()
        val description = doc.selectFirst("div.story")?.text()?.trim()
        val tags        = doc.select("ul.entry-meta li:contains(التصنيف) a")
                             .map { it.text() }
        val isMovie     = !doc.select("div.watch-seasons").hasText()

        return if (isMovie) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year       = year
                this.plot       = description
                this.tags       = tags
            }
        } else {
            val episodes = doc.select("div.watch-seasons ul li a").mapIndexed { idx, el ->
                newEpisode(el.attr("href")) {
                    name = el.text().trim()
                    season  = 1
                    episode = idx + 1
                }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year       = year
                this.plot       = description
                this.tags       = tags
            }
        }
    }

    /* ------------------------------------------------------------- */
    /*  Links – placeholder until real extractor added               */
    /* ------------------------------------------------------------- */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc      = app.get(data).document
        val watchUrl = doc.selectFirst("a.watch-btn")?.attr("href") ?: return false
        loadExtractor(watchUrl, data, subtitleCallback, callback)
        return true
    }

    /* ------------------------------------------------------------- */
    /*  Helpers                                                      */
    /* ------------------------------------------------------------- */
    private fun Element.toSearchResult(): SearchResponse? {
        val title = selectFirst("h3 a")?.text() ?: return null
        val href  = fixUrl(selectFirst("h3 a")?.attr("href") ?: return null)
        val poster= fixUrlNull(selectFirst("img")?.attr("src"))
        return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
    }

    private fun fixUrl(url: String)  = if (url.startsWith("http")) url else "$mainUrl$url"
    private fun fixUrlNull(url: String?) = url?.let { fixUrl(it) }
}
