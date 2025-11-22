package com.animezid

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class Animezid : MainAPI() {
    override var mainUrl = "https://animezid.cam"
    override var name = "Animezid"
    override val usesWebView = false
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Anime, TvType.Movie)
    override var lang = "ar"

    private fun Element.toSearchResponse(): SearchResponse? {
        return try {
            val title = this.attr("title")
                .replace("مشاهدة|تحميل|انمي|مترجم".toRegex(), "")
                .trim()
            if (title.isBlank()) return null
            
            val href = this.attr("href")
            val fullUrl = if (href.startsWith("http")) href else "$mainUrl$href"
            
            val poster = this.select("img").firstOrNull()?.attr("data-src") ?: ""
            val fullPoster = if (poster.startsWith("http")) poster else "$mainUrl$poster"

            val type = if (title.contains("فيلم") || fullUrl.contains("/movie/")) {
                TvType.Movie
            } else {
                TvType.Anime
            }

            if (type == TvType.Anime) {
                newTvSeriesSearchResponse(title, fullUrl, type) {
                    this.posterUrl = fullPoster
                }
            } else {
                newMovieSearchResponse(title, fullUrl, type) {
                    this.posterUrl = fullPoster
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/" to "أحدث الإضافات",
        "$mainUrl/anime/" to "أنمي",
        "$mainUrl/movies/" to "أفلام أنمي"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return try {
            val url = if (page > 1) "${request.data}page/$page/" else request.data
            val document = app.get(url).document
            val items = document.select("a.movie").mapNotNull { it.toSearchResponse() }
            newHomePageResponse(request.name, items)
        } catch (e: Exception) {
            newHomePageResponse(request.name, emptyList())
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val document = app.get("$mainUrl/search.php?keywords=$query").document
            document.select("a.movie").mapNotNull { it.toSearchResponse() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        return try {
            val document = app.get(url).document
            val title = document.selectFirst("h1")?.text() ?: "Unknown"
            val poster = document.selectFirst("img")?.attr("src") ?: ""
            val description = document.selectFirst(".entry-content")?.text() ?: ""

            val isMovie = url.contains("/movie/") || title.contains("فيلم")

            if (isMovie) {
                newMovieLoadResponse(title, url, TvType.Movie, url) {
                    this.posterUrl = poster
                    this.plot = description
                }
            } else {
                val episodes = listOf(
                    newEpisode(url) {
                        this.name = "الحلقة 1"
                        this.episode = 1
                        this.season = 1
                    }
                )
                newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                    this.posterUrl = poster
                    this.plot = description
                }
            }
        } catch (e: Exception) {
            newMovieLoadResponse("Unknown", url, TvType.Movie, url) {
                this.posterUrl = ""
                this.plot = "Failed to load"
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            var found = false
            val document = app.get(data).document
            document.select("iframe").forEach { iframe ->
                val src = iframe.attr("src")
                if (src.isNotBlank()) {
                    found = true
                    loadExtractor(src, data, subtitleCallback, callback)
                }
            }
            found
        } catch (e: Exception) {
            false
        }
    }
}
