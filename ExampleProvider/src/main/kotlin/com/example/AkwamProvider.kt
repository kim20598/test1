package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink

class AkwamProvider : MainAPI() {
    override var mainUrl = "https://ak.sv"
    override var name = "Akwam"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "ar"

    override val hasMainPage = true

    // Main page - keep it simple for now
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return HomePageResponse(listOf())
    }

    // Search function - let's make this work with real Akwam search
    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        
        try {
            // Try to search on Akwam
            val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
            val searchUrl = "$mainUrl/search?q=$encodedQuery"
            val document = app.get(searchUrl).document
            
            // Try common selectors for search results
            // We'll test multiple common patterns used by streaming sites
            val selectors = listOf(
                ".movie", ".item", ".card", ".box", ".post",
                ".search-result", ".result", "[class*='movie']"
            )
            
            for (selector in selectors) {
                val items = document.select(selector)
                if (items.isNotEmpty()) {
                    items.forEach { element ->
                        try {
                            val title = element.select("h2, h3, .title, [class*='title']").text()
                            val href = element.select("a").attr("href")
                            val poster = element.select("img").attr("src")
                            
                            if (title.isNotEmpty() && href.isNotEmpty()) {
                                val fullUrl = if (href.startsWith("http")) href else "$mainUrl$href"
                                
                                val type = if (title.contains("مسلسل") || href.contains("series")) {
                                    TvType.TvSeries
                                } else {
                                    TvType.Movie
                                }
                                
                                results.add(
                                    newMovieSearchResponse(title, fullUrl, type) {
                                        this.posterUrl = poster
                                    }
                                )
                            }
                        } catch (e: Exception) {
                            // Skip invalid items
                        }
                    }
                    break // Stop after first successful selector
                }
            }
            
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        return results
    }

    // Load details - keep it simple
    override suspend fun load(url: String): LoadResponse {
        return newMovieLoadResponse(
            "Akwam Content",
            url,
            TvType.Movie,
            url
        ) {
            posterUrl = ""
            plot = "Content from Akwam"
            year = 2023
        }
    }

    // Load video links - keep it simple
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return true
    }
}
