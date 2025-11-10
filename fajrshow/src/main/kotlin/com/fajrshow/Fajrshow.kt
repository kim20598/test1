package com.fajrshow

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor

class Fajrshow : MainAPI() {
    override var mainUrl = "https://fajer.show"
    override var name = "Fajrshow"
    override val usesWebView = true
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "ar"

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return try {
            // Try to load the main page normally first
            val document = app.get(mainUrl).document
            
            // If we get here, Cloudflare might be bypassed
            // Try to extract some basic content
            val home = document.select("a[href*='/movie/'], a[href*='/tv/']").mapNotNull { element ->
                val title = element.text().trim()
                val href = element.attr("href")
                if (title.isNotBlank() && href.isNotBlank()) {
                    newMovieSearchResponse(title, href, TvType.Movie) {
                        this.posterUrl = ""
                    }
                } else {
                    null
                }
            }
            
            newHomePageResponse("Fajrshow", home)
        } catch (e: Exception) {
            // If normal request fails, return empty and let WebView handle it
            newHomePageResponse("Protected Site", emptyList())
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            if (query.length < 3) return emptyList()
            val document = app.get("$mainUrl/?s=$query").document
            
            document.select("a[href*='/movie/'], a[href*='/tv/']").mapNotNull { element ->
                val title = element.text().trim()
                val href = element.attr("href")
                if (title.isNotBlank() && href.isNotBlank()) {
                    newMovieSearchResponse(title, href, TvType.Movie) {
                        this.posterUrl = ""
                    }
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        return try {
            val document = app.get(url).document
            
            val title = document.selectFirst("h1")?.text()?.trim() ?: "Unknown Title"
            
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = ""
                this.plot = "Content loaded from Fajrshow"
            }
        } catch (e: Exception) {
            newMovieLoadResponse("Protected Content", url, TvType.Movie, url) {
                this.posterUrl = ""
                this.plot = "Site is protected. Content will load in WebView."
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
            // Let Cloud Stream's extractor system handle the links
            // With usesWebView = true, it will use WebView for protected pages
            loadExtractor(data, data, subtitleCallback, callback)
        } catch (e: Exception) {
            false
        }
    }
}