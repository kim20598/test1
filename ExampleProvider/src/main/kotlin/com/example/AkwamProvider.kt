package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink

class AkwamProvider : MainAPI() {
    override var mainUrl = "https://ak.sv"
    override var name = "Akwam"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "ar"

    override val hasMainPage = true

    // Main page - now with REAL Akwam content!
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = mutableListOf<HomePageList>()
        
        try {
            val document = app.get(mainUrl).document
            
            // Featured content from the slider
            val featuredItems = mutableListOf<SearchResponse>()
            document.select(".swiper-slide .entry-box-1").forEach { element ->
                try {
                    val title = element.select(".entry-title").text()
                    val href = element.select("a").attr("href")
                    val poster = element.select("img").attr("src")
                    
                    if (title.isNotEmpty() && href.isNotEmpty()) {
                        val fullUrl = if (href.startsWith("http")) href else "$mainUrl$href"
                        val type = if (href.contains("/series/")) TvType.TvSeries else TvType.Movie
                        
                        featuredItems.add(
                            newMovieSearchResponse(title, fullUrl, type) {
                                this.posterUrl = poster
                            }
                        )
                    }
                } catch (e: Exception) {
                    // Skip invalid items
                }
            }
            
            if (featuredItems.isNotEmpty()) {
                items.add(HomePageList("المميزة", featuredItems))
            }
            
            // Movies section
            val movies = mutableListOf<SearchResponse>()
            document.select(".widget-4 .entry-box-1").take(20).forEach { element ->
                try {
                    val title = element.select(".entry-title").text()
                    val href = element.select("a").attr("href")
                    val poster = element.select("img").attr("data-src") ?: element.select("img").attr("src")
                    
                    if (title.isNotEmpty() && href.isNotEmpty() && href.contains("/movie/")) {
                        val fullUrl = if (href.startsWith("http")) href else "$mainUrl$href"
                        
                        movies.add(
                            newMovieSearchResponse(title, fullUrl, TvType.Movie) {
                                this.posterUrl = poster
                            }
                        )
                    }
                } catch (e: Exception) {
                    // Skip invalid items
                }
            }
            
            if (movies.isNotEmpty()) {
                items.add(HomePageList("أفلام", movies))
            }
            
            // TV Series section
            val series = mutableListOf<SearchResponse>()
            document.select(".widget-4 .entry-box-1").forEach { element ->
                try {
                    val title = element.select(".entry-title").text()
                    val href = element.select("a").attr("href")
                    val poster = element.select("img").attr("data-src") ?: element.select("img").attr("src")
                    
                    if (title.isNotEmpty() && href.isNotEmpty() && href.contains("/series/")) {
                        val fullUrl = if (href.startsWith("http")) href else "$mainUrl$href"
                        
                        series.add(
                            newTvSeriesSearchResponse(title, fullUrl, TvType.TvSeries) {
                                this.posterUrl = poster
                            }
                        )
                    }
                } catch (e: Exception) {
                    // Skip invalid items
                }
            }
            
            if (series.isNotEmpty()) {
                items.add(HomePageList("مسلسلات", series))
            }
            
        } catch (e: Exception) {
            e.printStackTrace()
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
                                newTvSeriesSearchResponse(title, fullUrl, type) {
                                    this.posterUrl = poster
                                }
                            )
                        } else {
                            results.add(
                                newMovieSearchResponse(title, fullUrl, type) {
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

    // Load details - REAL Akwam content details
    override suspend fun load(url: String): LoadResponse {
        try {
            val document = app.get(url).document
            
            val title = document.select("h1, .title").text()
            val poster = document.select(".entry-poster img, .poster img, img[src*='thumb']").attr("src")
            val plot = document.select(".entry-desc, .plot, .description").text()
            
            // Extract year from badges
            var year: Int? = null
            document.select(".badge-secondary").forEach { badge ->
                val text = badge.text()
                if (text.length == 4 && text.toIntOrNull() != null) {
                    year = text.toInt()
                }
            }
            
            // Extract genres/tags
            val genres = document.select(".badge-light").map { it.text() }
            
            // Check if it's a series by looking for episodes
            val isSeries = url.contains("/series/") || document.select(".episode-list, [class*='episode']").isNotEmpty()
            
            return if (isSeries) {
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
