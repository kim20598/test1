package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class AkwamProvider : MainAPI() {
    override var mainUrl               = "https://ak.sv"
    override var name                  = "Akwam"
    override val supportedTypes        = setOf(TvType.Movie, TvType.TvSeries)
    override val hasMainPage           = true
    override var lang                  = "ar"

    /*  CORRECT signature – only name (String) required  */
    override val mainPage = listOf(
        MainPageData("مميزه"),
        MainPageData("افلام"),
        MainPageData("مسلسلات"),
        MainPageData("تلفزيون"),
        MainPageData("مدبلج")
    )

    private val rowMap = mapOf(
        "مميزه"  to "${mainUrl}/page/{p}?section=2",
        "افلام"  to "${mainUrl}/movies?page={p}",
        "مسلسلات" to "${mainUrl}/series?page={p}",
        "تلفزيون" to "${mainUrl}/tvshows?category=28&page={p}",
        "مدبلج"  to "${mainUrl}/movies?section=1&category=17&page={p}"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val urlTemp = rowMap[request.name] ?: "${mainUrl}/movies?page=$page"
        val url     = urlTemp.replace("{p}", page.toString())
        val items   = parseRow(url)
        return newHomePageResponse(request.name, items)
    }

    private suspend fun parseRow(url: String): List<SearchResponse> =
        app.get(url).document
           .select("div.widget-body div.entry-box")
           .mapNotNull { it.toSearchResult() }

    override suspend fun search(query: String) =
        app.get("${mainUrl}/search?q=$query").document
           .select("div.widget-body div.entry-box")
           .mapNotNull { it.toSearchResult() }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1.entry-title")?.text()?.trim()
                      ?: throw ErrorLoadingException("No title")
        val poster = fixUrlNull(
            doc.selectFirst("div.poster img")?.attr("data-src")
                ?: doc.selectFirst("div.poster img")?.attr("src")
        )
        val year = doc.selectFirst("ul.entry-meta li:contains(سنة الإصدار)")
                      ?.text()?.filter { it.isDigit() }?.toIntOrNull()
        val description = doc.selectFirst("div.story")?.text()?.trim().orEmpty()
        val tags = doc.select("ul.entry-meta li:contains(التصنيف) a").map { it.text() }
        val isMovie = !doc.select("div.watch-seasons").hasText()

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

    override suspend fun loadLinks(
        data: String, isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        val watchUrl = doc.selectFirst("a.watch-btn")?.attr("href") ?: return false
        loadExtractor(watchUrl, data, subtitleCallback, callback)
        return true
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = selectFirst("h3 a")?.text() ?: return null
        val href  = fixUrl(selectFirst("h3 a")?.attr("href") ?: return null)
        val poster = fixUrlNull(
            selectFirst("img")?.attr("data-src")
                ?: selectFirst("img")?.attr("src")
        )
        val type = when {
            href.contains("/series")  -> TvType.TvSeries
            href.contains("/tvshows") -> TvType.TvSeries
            else                      -> TvType.Movie
        }
        return newMovieSearchResponse(title, href, type) { this.posterUrl = poster }
    }

    private fun fixUrl(url: String)  = if (url.startsWith("http")) url else "$mainUrl$url"
    private fun fixUrlNull(url: String?) = url?.let { fixUrl(it) }
}
