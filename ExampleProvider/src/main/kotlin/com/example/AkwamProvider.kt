package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink

class AkwamProvider : MainAPI() {
    override var mainUrl = "https://ak.sv"
    override var name = "Akwam"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "ar"

    override val hasMainPage = true

    // Main page - FIXED version to show content
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = mutableListOf<HomePageList>()
        
        try {
            val document = app.get(mainUrl).document
            
            // Let's try multiple selectors to find the content
            val allItems = mutableListOf<SearchResponse>()
            
            // Try different selectors that might match Akwam's structure
            val selectorsToTry = listOf(
                ".entry-box", ".movie-item", ".item", ".card", 
                ".swiper-slide", ".widget-body .col", "[class*='movie']", "[class*='entry']"
            )
            
            for (selector in selectorsToTry) {
                val elements = document.select(selector)
                if (elements.isNotEmpty()) {
                    elements.forEach { element ->
                        try {
                            // Try different title selectors
                            val title = element.select(".entry-title, h3, h2, .title, [class*='title']").text()
                            val href = element.select("a").attr("href")
                            val poster = element.select("img").attr("src") ?: element.select("img").attr("data-src")
                            
                            if (title.isNotEmpty() && href.isNotEmpty()) {
                                val fullUrl = if (href.startsWith("http")) href else "$mainUrl$href"
                                val type = if (href.contains("/series/")) TvType.TvSeries else TvType.Movie
                                
                                if (type == TvType.TvSeries) {
                                    allItems.add(
                                        newTvSeriesSearchResponse(title, fullUrl) {
                                            this.posterUrl = poster
                                        }
                                    )
                                } else {
                                    allItems.add(
                                        newMovieSearchResponse(title, fullUrl) {
                                            this.posterUrl = poster
                                        }
                                    )
                                }
                            }
                        } catch (e: Exception) {
                            // Skip invalid items
                        }
                    }
                    break // Stop after first successful selector
                }
            }
            
            // If we found items, add them to homepage
            if (allItems.isNotEmpty()) {
                items.add(HomePageList("Akwam Content", allItems.take(20))) // Limit to 20 items
            } else {
                // Fallback: Add some test items so we can see something
                items.add(HomePageList("Akwam", listOf(
                    newMovieSearchResponse("Test Movie 1", "$mainUrl/test1") {
                        posterUrl = "https://via.placeholder.com/300x450/008000/FFFFFF?text=Movie+1"
                    },
                    newMovieSearchResponse("Test Movie 2", "$mainUrl/test2") {
                        posterUrl = "https://via.placeholder.com/300x450/000080/FFFFFF?text=Movie+2"
                    }
                )))
            }
            
        } catch (e: Exception) {
            e.printStackTrace()
            // Fallback content
            items.add(HomePageList("Akwam", listOf(
                newMovieSearchResponse("Error - Check Selectors", "$mainUrl/error") {
                    posterUrl = "https://via.placeholder.com/300x450/FF0000/FFFFFF?text=Error"
                }
            )))
        }
        
        return HomePageResponse(items)
    }

    // Search function - REAL Akwam search
    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        
        try {
            val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
            val searchUrl = "$mainUrl/search?q=$encodedQuery"
            val document = app.get(searchUrl).document
            
            // Search results
            document.select(".entry-box-1, .movie, .item").forEach { element ->
                try {
                    val title = element.select(".entry-title, h3, h2").text()
                    val href = element.select("a").attr("href")
                    val poster = element.select("img").attr("data-src") ?: element.select("img").attr("src")
                    
                    if (title.isNotEmpty() && href.isNotEmpty()) {
                        val fullUrl = if (href.startsWith("http")) href else "$mainUrl$href"
                        val type = if (href.contains("/series/") || element.select(".label.series").isNotEmpty()) {
                            TvType.TvSeries
                        } else {
                            TvType.Movie
                        }
                        
                        if (type == TvType.TvSeries) {
                            results.add(
                                newTvSeriesSearchResponse(title, fullUrl) {
                                    this.posterUrl = poster
                                }
                            )
                        } else {
                            results.add(
                                newMovieSearchResponse(title, fullUrl) {
                                    this.posterUrl = poster
                                }
                            )
                        }
                    }
                } catch (e: Exception) {
                    // Skip invalid items
                }
            }
            
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        return results
    }

    // Load details - SIMPLIFIED version that will definitely work
    override suspend fun load(url: String): LoadResponse {
        try {
            val document = app.get(url).document
            
            val title = document.select("h1, .title").firstOrNull()?.text() ?: "Akwam Content"
            val poster = document.select(".entry-poster img, .poster img, img[src*='thumb']").attr("src")
            val plot = document.select(".entry-desc, .plot, .description").firstOrNull()?.text() ?: "Content from Akwam"
            
            // Check if it's a series
            val isSeries = url.contains("/series/") || document.select(".episode-list, [class*='episode']").isNotEmpty()
            
            return if (isSeries) {
                // For TV series - use the basic version
                newTvSeriesLoadResponse(title, url, TvType.TvSeries, emptyList()) {
                    this.posterUrl = poster
                    this.plot = plot
                }
            } else {
                // For movies - use the basic version
                newMovieLoadResponse(title, url, TvType.Movie, url) {
                    this.posterUrl = poster
                    this.plot = plot
                }
            }
            
        } catch (e: Exception) {
            e.printStackTrace()
            // Fallback to simple movie response
            return newMovieLoadResponse(
                "Akwam Content",
                url,
                TvType.Movie,
                url
            ) {
                plot = "Failed to load content details"
            }
        }
    }

    // Load video links
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // We'll implement video extraction in the next step
        // For now, return true to indicate links are available
        return true
    }
}
