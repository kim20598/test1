package com.movizland

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

class MovizLand : MainAPI() {
    override var mainUrl = "https://en.movizlands.com"
    override var name = "MovizLand"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama
    )

    // Helper functions
    private fun String.toAbsolute(): String {
        return when {
            this.startsWith("http") -> this
            this.startsWith("//") -> "https:$this"
            this.startsWith("/") -> "$mainUrl$this"
            else -> "$mainUrl/$this"
        }
    }

    private fun String.cleanTitle(): String {
        return this
            .replace(Regex("\\(\\d{4}\\)"), "")
            .replace(Regex("Season \\d+"), "")
            .replace("Watch", "")
            .replace("مشاهدة", "")
            .replace("مسلسل", "")
            .replace("فيلم", "")
            .replace("انمي", "")
            .trim()
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        return try {
            val article = this
            val href = article.selectFirst("a")?.attr("href")?.toAbsolute() ?: return null
            val title = article.selectFirst(".h-title, .film-name, h3")?.text()?.cleanTitle() 
                ?: return null
            
            val posterUrl = article.selectFirst("img")?.let { img ->
                img.attr("data-src").ifBlank { img.attr("src") }.toAbsolute()
            }

            val quality = article.selectFirst(".quality")?.text()
            val type = when {
                href.contains("/series/") || href.contains("/season/") -> TvType.TvSeries
                href.contains("/anime/") -> TvType.Anime
                href.contains("/asian/") -> TvType.AsianDrama
                else -> TvType.Movie
            }

            newMovieSearchResponse(title, href, type) {
                this.posterUrl = posterUrl
                quality?.let {
                    this.quality = when {
                        it.contains("1080") -> SearchQuality.HD
                        it.contains("720") -> SearchQuality.HD
                        else -> null
                    }
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/category/movies/page/" to "Movies",
        "$mainUrl/category/series/page/" to "TV Series",
        "$mainUrl/category/asian-series/page/" to "Asian Drama",
        "$mainUrl/category/anime/page/" to "Anime",
        "$mainUrl/category/netflix/page/" to "Netflix",
        "$mainUrl/category/disney/page/" to "Disney+",
        "$mainUrl/category/hbo/page/" to "HBO",
        "$mainUrl/category/prime-video/page/" to "Prime Video",
        "$mainUrl/category/apple-tv/page/" to "Apple TV+",
        "$mainUrl/category/action/page/" to "Action",
        "$mainUrl/category/adventure/page/" to "Adventure",
        "$mainUrl/category/animation/page/" to "Animation",
        "$mainUrl/category/comedy/page/" to "Comedy",
        "$mainUrl/category/crime/page/" to "Crime",
        "$mainUrl/category/drama/page/" to "Drama",
        "$mainUrl/category/fantasy/page/" to "Fantasy",
        "$mainUrl/category/horror/page/" to "Horror",
        "$mainUrl/category/mystery/page/" to "Mystery",
        "$mainUrl/category/romance/page/" to "Romance",
        "$mainUrl/category/sci-fi/page/" to "Sci-Fi",
        "$mainUrl/category/thriller/page/" to "Thriller"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data + page
        val document = app.get(url).document

        val items = document.select(
            "article.post, div.film_list-wrap article, div.flw-item, .content-box"
        ).mapNotNull { element ->
            element.toSearchResponse()
        }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=${URLEncoder.encode(query, "UTF-8")}"
        val document = app.get(searchUrl).document

        return document.select(
            "article.post, div.film_list-wrap article, .search-item, .result-item"
        ).mapNotNull { element ->
            element.toSearchResponse()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1.title, h1.film-title, .movie-title h1")
            ?.text()
            ?.cleanTitle() 
            ?: "Unknown"

        val posterUrl = document.selectFirst(
            "meta[property='og:image'], .film-poster img, .movie-thumbnail img"
        )?.let { element ->
            if (element.tagName() == "meta") {
                element.attr("content")
            } else {
                element.attr("src").ifBlank { element.attr("data-src") }
            }.toAbsolute()
        }

        val plot = document.selectFirst(
            ".description, .film-description, .synopsis, [itemprop='description']"
        )?.text()?.trim()

        val year = document.selectFirst(
            ".year, .film-year, .movie-year, time"
        )?.text()?.filter { it.isDigit() }?.toIntOrNull()

        val tags = document.select(
            ".genre a, .film-genre a, [rel='category tag']"
        ).map { it.text().trim() }

        val duration = document.selectFirst(
            ".duration, .film-duration, .movie-duration"
        )?.text()?.filter { it.isDigit() }?.toIntOrNull()

        val trailer = document.selectFirst(
            "iframe[src*='youtube'], iframe[src*='youtu.be']"
        )?.attr("src")

        val recommendations = document.select(
            ".related-posts article, .film-related article, .recommendations .item"
        ).mapNotNull { it.toSearchResponse() }

        // Check if it's a series or movie
        val isSeries = url.contains("/series/") || 
                      url.contains("/season/") || 
                      document.select(".episodes-list, .season-list").isNotEmpty()

        return if (isSeries) {
            val episodes = mutableListOf<Episode>()
            
            // Handle seasons
            val seasonsElements = document.select(".season-list li a, .seasons-tabs a")
            
            if (seasonsElements.isNotEmpty()) {
                // Multiple seasons
                seasonsElements.forEach { seasonElement ->
                    val seasonUrl = seasonElement.attr("href").toAbsolute()
                    val seasonNum = seasonElement.text()
                        .filter { it.isDigit() }
                        .toIntOrNull() ?: 1
                    
                    val seasonDoc = app.get(seasonUrl).document
                    seasonDoc.select(".episodes-list a, .episode-item").forEach { ep ->
                        val epUrl = ep.attr("href").toAbsolute()
                        val epNum = ep.text().filter { it.isDigit() }.toIntOrNull() ?: 0
                        val epTitle = ep.text()

                        episodes.add(
                            newEpisode(epUrl) {
                                this.name = epTitle
                                this.season = seasonNum
                                this.episode = epNum
                            }
                        )
                    }
                }
            } else {
                // Single season or direct episodes
                document.select(".episodes-list a, .episode-item, ul.episodesList li a")
                    .forEach { ep ->
                        val epUrl = ep.attr("href").toAbsolute()
                        val epNum = ep.text().filter { it.isDigit() }.toIntOrNull() ?: 0
                        val epTitle = ep.text()

                        episodes.add(
                            newEpisode(epUrl) {
                                this.name = epTitle
                                this.season = 1
                                this.episode = epNum
                            }
                        )
                    }
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.year = year
                this.tags = tags
                this.duration = duration
                this.recommendations = recommendations
                addTrailer(trailer)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.year = year
                this.tags = tags
                this.duration = duration
                this.recommendations = recommendations
                addTrailer(trailer)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        var foundLinks = false

        // Extract subtitles
        document.select("track[kind='captions']").forEach { track ->
            val src = track.attr("src").toAbsolute()
            val label = track.attr("label") ?: "Unknown"
            val lang = track.attr("srclang") ?: "en"
            
            subtitleCallback.invoke(
                SubtitleFile(
                    lang = "$label ($lang)",
                    url = src
                )
            )
        }

        // Method 1: Direct video links
        document.select("source[src], video source").forEach { source ->
            val videoUrl = source.attr("src").toAbsolute()
            val quality = source.attr("size")?.toIntOrNull() 
                ?: source.attr("res")?.toIntOrNull()
                ?: Qualities.Unknown.value

            if (videoUrl.isNotBlank()) {
                foundLinks = true
                callback.invoke(
                    newExtractorLink(
                        videoUrl,
                        "$name - Direct",
                        data,
                        quality,
                        videoUrl.contains(".m3u8")
                    )
                )
            }
        }

        // Method 2: iframes
        document.select("iframe").forEach { iframe ->
            val iframeUrl = iframe.attr("src").toAbsolute()
            if (iframeUrl.isNotBlank() && !iframeUrl.contains("youtube")) {
                foundLinks = true
                loadExtractor(iframeUrl, data, subtitleCallback, callback)
            }
        }

        // Method 3: Player containers with data attributes
        document.select("[data-player-src], [data-src], .player-embed").forEach { element ->
            val playerUrl = (element.attr("data-player-src")
                .ifBlank { element.attr("data-src") }
                .ifBlank { element.attr("src") }).toAbsolute()
            
            if (playerUrl.isNotBlank()) {
                foundLinks = true
                loadExtractor(playerUrl, data, subtitleCallback, callback)
            }
        }

        // Method 4: AJAX server switching (if the site uses it)
        document.select(".server-item, [data-server]").forEach { serverElement ->
            val serverId = serverElement.attr("data-server")
                .ifBlank { serverElement.attr("data-id") }
            val serverNum = serverElement.attr("data-num")
            
            if (serverId.isNotBlank()) {
                try {
                    val serverUrl = "$mainUrl/ajax/server/$serverId/$serverNum"
                    val serverResponse = app.get(
                        serverUrl,
                        headers = mapOf(
                            "X-Requested-With" to "XMLHttpRequest",
                            "Referer" to data
                        )
                    ).parsedSafe<ServerResponse>()
                    
                    serverResponse?.embed?.let { embedUrl ->
                        foundLinks = true
                        loadExtractor(embedUrl, data, subtitleCallback, callback)
                    }
                } catch (e: Exception) {
                    // Server request failed, continue
                }
            }
        }

        // Method 5: Check for encrypted/protected content
        val scripts = document.select("script").html()
        Regex("""(?:source|src|file)["']?\s*[:=]\s*["']([^"']+\.(?:mp4|m3u8|mkv))["']""")
            .findAll(scripts).forEach { match ->
                val videoUrl = match.groupValues[1].toAbsolute()
                if (videoUrl.isNotBlank()) {
                    foundLinks = true
                    callback.invoke(
                        newExtractorLink(
                            videoUrl,
                            "$name - Direct",
                            data,
                            Qualities.Unknown.value,
                            videoUrl.contains(".m3u8")
                        )
                    )
                }
            }

        return foundLinks
    }

    // Data class for AJAX responses
    data class ServerResponse(
        val embed: String? = null,
        val url: String? = null,
        val status: Boolean? = null
    )
}