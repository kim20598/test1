package com.movizland

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.amap // <<< THIS LINE FIXES THE BUILD ERROR
import org.jsoup.nodes.Element
import kotlinx.serialization.Serializable

class Movizland : MainAPI() {
    override var mainUrl = "https://en.movizlands.com"
    override var name = "Movizland"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // Helper to parse a single content block from main/search pages
    private fun Element.toSearchResult(): SearchResponse? {
        val link = this.selectFirst("a") ?: return null
        val href = link.attr("href")
        if (href.isBlank()) return null

        val title = this.selectFirst(".Block--Name")?.text()?.trim() ?: return null
        val posterUrl = this.selectFirst("img.lazyload")?.attr("data-src")

        val tvType = when {
            href.contains("/movie/") -> TvType.Movie
            href.contains("/series/") -> TvType.TvSeries
            else -> null // Ignore items that are not movies or series
        } ?: return null

        return newMovieSearchResponse(title, href, tvType) {
            this.posterUrl = posterUrl
        }
    }

    // Define the main page sections
    override val mainPage = mainPageOf(
        "$mainUrl/movies/page/" to "Latest Movies",
        "$mainUrl/series/page/" to "Latest TV-Series",
        "$mainUrl/category/action/page/" to "Action",
        "$mainUrl/category/comedy/page/" to "Comedy",
        "$mainUrl/category/horror/page/" to "Horror",
        "$mainUrl/category/romance/page/" to "Romance",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "${request.data}$page/"
        val document = app.get(url).document

        val items = document.select("div.Block").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/search/$query/").document
        return document.select("div.Block").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("div.Title--Content--Single-left h1")?.text()?.trim() ?: "Unknown"
        val poster = document.selectFirst("div.Poster--Single-left img")?.attr("src")
        val plot = document.selectFirst("div.Story p")?.text()?.trim()
        val year = document.selectFirst("span.Date")?.text()?.toIntOrNull()
        val tags = document.select("span.Genre a").map { it.text() }
        val recommendations = document.select("div.Block").mapNotNull { it.toSearchResult() }

        val isTvSeries = url.contains("/series/")
        return if (isTvSeries) {
            val episodes = document.select("div.Episodes--Seasons--Episodes ul li a").mapNotNull { epEl ->
                val epHref = epEl.attr("href")
                if (epHref.isBlank()) return@mapNotNull null

                val epName = epEl.attr("title")
                val (season, episode) = epEl.selectFirst(".Mv--Info-IMDB")?.text().let { info ->
                    val season = info?.let { Regex("""S (\d+)""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
                    val episode = info?.let { Regex("""E (\d+)""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
                    Pair(season, episode)
                }
                newEpisode(epHref) {
                    name = epName
                    this.season = season
                    this.episode = episode
                }
            }.reversed() // Episodes are listed newest first, so reverse the list

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.recommendations = recommendations
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.recommendations = recommendations
            }
        }
    }

    // Data class for parsing the JSON response from the site's AJAX endpoint
    @Serializable
    data class AjaxResponse(
        val embed_url: String
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // The player is on a dedicated watch page, which is the content URL + "/watch"
        val watchUrl = "$data/watch/"
        val document = app.get(watchUrl).document

        val postId = document.selectFirst("#player-embed")?.attr("data-post") ?: return false

        // Concurrently fetch the iframe URL for each available server
        document.select("ul.server_list--menu li").amap { serverEl ->
            val serverId = serverEl.attr("data-server")

            // Make a POST request to the site's backend to get the server's iframe
            val response = app.post(
                url = "$mainUrl/wp-admin/admin-ajax.php",
                data = mapOf(
                    "action" to "get_player_ajax",
                    "post" to postId,
                    "nume" to serverId
                ),
                referer = watchUrl,
                headers = mapOf("X-Requested-With" to "XMLHttpRequest")
            ).parsedSafe<AjaxResponse>()

            // Extract the iframe URL from the JSON response and load it
            response?.embed_url?.let { iframeHtml ->
                val iframeUrl = Regex("""src=["'](.*?)["']""").find(iframeHtml)?.groupValues?.get(1)
                if (iframeUrl != null) {
                    loadExtractor(iframeUrl, watchUrl, subtitleCallback, callback)
                }
            }
        }

        return true
    }
}
