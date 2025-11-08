package com.fushaar

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import java.net.URLEncoder

class Fushaar : MainAPI() {
    // ✅ SAFE - All properties
    override var lang = "ar"
    override var mainUrl = "https://fushaar.com"
    override var name = "Fushaar"
    override val usesWebView = true
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.Anime)

    // ✅ SAFE - Helper functions
    private fun String.getIntFromText(): Int? {
        return Regex("""\d+""").find(this)?.groupValues?.firstOrNull()?.toIntOrNull()
    }
    
    private fun String.cleanTitle(): String {
        return this.replace("مشاهدة وتحميل فلم|مشاهدة وتحميل|اونلاين|مترجم".toRegex(), "").trim()
    }

    // ✅ SAFE - Search response
    private fun Element.toSearchResponse(): SearchResponse {
        val title = select("h3").text().cleanTitle()
        val posterUrl = select("img").attr("src")
        val href = select("a").attr("href")
        val tvType = when {
            href.contains("/movie/") -> TvType.Movie
            else -> TvType.TvSeries
        }
        
        return newMovieSearchResponse(title, href, tvType) {
            this.posterUrl = posterUrl
        }
    }

    // ✅ SAFE - Main page
    override val mainPage = mainPageOf(
        "$mainUrl" to "Latest Movies",
        "$mainUrl/page/2/" to "Page 2", 
        "$mainUrl/page/3/" to "Page 3"
    )

    // ✅ SAFE - getMainPage
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data + if (page > 1) page else "").document
        val home = document.select("article").mapNotNull {
            it.toSearchResponse()
        }
        return newHomePageResponse(request.name, home)
    }

    // ✅ SAFE - search function
    override suspend fun search(query: String): List<SearchResponse> {
        if (query.length < 3) return emptyList()
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val doc = app.get("$mainUrl/?s=$encodedQuery").document
        return doc.select("article").mapNotNull {
            if(it.select("a").attr("href").contains("/episode/")) return@mapNotNull null
            it.toSearchResponse()
        }
    }

    // ✅ SAFE - load function (NO RATING/SCORE ISSUES)
    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1")?.text()?.cleanTitle() ?: "Unknown Title"
        val isMovie = url.contains("/movie/")

        val posterUrl = doc.selectFirst("img")?.attr("src") ?: ""
        val synopsis = doc.selectFirst(".entry-content, .description")?.text() ?: ""
        val year = doc.selectFirst(".year, [class*='date']")?.text()?.getIntFromText()
        val tags = doc.select(".genre a, .category a").map { it.text() }
        val recommendations = doc.select(".related-posts article, .related article").mapNotNull { element ->
            element.toSearchResponse()
        }
        val youtubeTrailer = doc.selectFirst("iframe[src*='youtube']")?.attr("src") ?: ""
        
        return if (isMovie) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = posterUrl
                this.recommendations = recommendations
                this.plot = synopsis
                this.tags = tags
                this.year = year
                addTrailer(youtubeTrailer)
            }
        } else {
            val seasonList = doc.select(".seasons-list a, [class*='season'] a").reversed()
            val episodes = arrayListOf<Episode>()
            
            if(seasonList.isNotEmpty()) {
                seasonList.forEachIndexed { index, season ->
                    val seasonDoc = app.get(season.attr("href")).document
                    seasonDoc.select(".episodes-list a, [class*='episode'] a").forEach {
                        episodes.add(newEpisode(it.attr("href")) {
                            name = it.attr("title").ifBlank { it.text() }
                            this.season = index + 1
                            episode = it.text().getIntFromText() ?: 1
                        })
                    }
                }
            } else {
                doc.select(".episodes-list a, [class*='episode'] a").forEach {
                    episodes.add(newEpisode(it.attr("href")) {
                        name = it.attr("title").ifBlank { it.text() }
                        this.season = 1
                        episode = it.text().getIntFromText() ?: 1
                    })
                }
            }
            
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.distinct().sortedBy { it.episode }) {
                this.posterUrl = posterUrl
                this.tags = tags
                this.plot = synopsis
                this.recommendations = recommendations
                this.year = year
                addTrailer(youtubeTrailer)
            }
        }
    }

    // ✅ SAFE - loadLinks function
    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        var foundLinks = false
        
        try {
            val doc = app.get(data).document
            
            // Try direct video links first
            doc.select("a[href*='.mp4'], a[href*='.m3u8']").forEach { element ->
                val url = element.attr("href")
                if (url.isNotBlank()) {
                    foundLinks = true
                    loadExtractor(url, data, subtitleCallback, callback)
                }
            }
            
            // Try iframe embeds
            doc.select("iframe").forEach { li ->
                val iframeUrl = li.attr("src")
                if (iframeUrl.isNotBlank() && iframeUrl.contains("http")) {
                    foundLinks = true
                    loadExtractor(iframeUrl, data, subtitleCallback, callback)
                }
            }
            
            // If no links found, try POST request
            if (!foundLinks) {
                try {
                    val postDoc = app.post(data, data = mapOf("view" to "1")).document
                    
                    postDoc.select("a[href*='.mp4'], a[href*='.m3u8']").forEach { element ->
                        val url = element.attr("href")
                        if (url.isNotBlank()) {
                            foundLinks = true
                            loadExtractor(url, data, subtitleCallback, callback)
                        }
                    }
                } catch (e: Exception) {
                    // POST failed, continue
                }
            }
            
        } catch (e: Exception) {
            // Fallback if everything fails
        }
        
        return foundLinks
    }
}
