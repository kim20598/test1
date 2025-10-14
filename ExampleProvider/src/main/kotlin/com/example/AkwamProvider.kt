package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.Jsoup

class AkwamProvider : MainAPI() {
    override var mainUrl = "https://ak.sv"
    override var name = "Akwam"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "ar"

    override val hasMainPage = true

    // Main page - let's add some real content
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = mutableListOf<HomePageList>()
        
        try {
            val document = app.get(mainUrl).document
            
            // Let's try to find movies on the homepage
            // Common selectors for Arabic sites:
            val movies = document.select("div.movie, .item, .card, .box, .post").takeIf { it.isNotEmpty() }
            
            if (movies != null && movies.isNotEmpty()) {
                val movieList = mutableListOf<SearchResponse>()
                
                movies.forEach { element ->
                    try {
                        val title = element.select("h2, .title, h3").text()
                        val href = element.select("a").attr("href")
                        val poster = element.select("img").attr("src")
                        
                        if (title.isNotEmpty() && href.isNotEmpty()) {
                            val fullUrl = if (href.startsWith("http")) href else "$mainUrl$href"
                            
                            // Determine type based on title or URL
                            val type = if (title.contains("مسلسل") || href.contains("series") || href.contains("مسلسل")) {
                                TvType.TvSeries
                            } else {
                                TvType.Movie
                            }
                            
                            movieList.add(
                                newMovieSearchResponse(title, fullUrl, type) {
                                    this.posterUrl = poster
                                }
                            )
                        }
                    } catch (e: Exception) {
                        // Skip invalid items
                    }
                }
                
                if (movieList.isNotEmpty()) {
                    items.add(HomePageList("أحدث المحتوى", movieList))
                }
            }
            
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        // If no content found, return empty but with a title
        if (items.isEmpty()) {
            items.add(HomePageList("Akwam Content", emptyList()))
        }
        
        return HomePageResponse(items)
    }

    // Search function - let's make it work with real search
    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        
        try {
            // URL encode the query for Arabic support
            val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
            val searchUrl = "$mainUrl/search?q=$encodedQuery"
            val document = app.get(searchUrl).document
            
            // Try common search result selectors
            val searchItems = document.select(".search-result, .result, .item, .movie, .card")
            
            searchItems.forEach { element ->
                try {
                    val title = element.select("h2, .title, h3, h4").text()
                    val href = element.select("a").attr("href")
                    val poster = element.select("img").attr("src")
                    
                    if (title.isNotEmpty() && href.isNotEmpty()) {
                        val fullUrl = if (href.startsWith("http")) href else "$mainUrl$href"
                        
                        val type = if (title.contains("مسلسل") || href.contains("series") || href.contains("مسلسل")) {
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
            
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        return results
    }

    // Load details - let's make it more detailed
    override suspend fun load(url: String): LoadResponse {
        try {
            val document = app.get(url).document
            
            val title = document.select("h1, .title").text()
            val poster = document.select(".poster img, .cover img, img[src*='poster']").attr("src")
            val plot = document.select(".plot, .description, .summary, .content").text()
            
            // Try to extract year
            val yearText = document.select("span.year, .year, [class*='year']").text()
            val year = yearText.filter { it.isDigit() }.takeIf { it.length == 4 }?.toIntOrNull()
            
            // Try to extract genres
            val genres = document.select(".genres a, .tags a, [class*='genre'] a").map { it.text() }
            
            // Check if it's a series by looking for episodes
            val hasEpisodes = document.select(".episodes, .episode-list, [class*='episode']").isNotEmpty()
            
            return if (hasEpisodes) {
                newTvSeriesLoadResponse(title, url, TvType.TvSeries, url) {
                    this.posterUrl = poster
                    this.plot = plot
                    this.year = year
                    this.tags = genres
                }
            } else {
                newMovieLoadResponse(title, url, TvType.Movie, url) {
                    this.posterUrl = poster
                    this.plot = plot
                    this.year = year
                    this.tags = genres
                }
            }
            
        } catch (e: Exception) {
            e.printStackTrace()
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

    // Load video links - basic implementation for now
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            // For now, return true but no actual links
            // We'll implement video extraction later
            return true
            
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }
}
