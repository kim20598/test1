package com.moviztime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class MovizTime : MainAPI() {
    override var mainUrl = "https://moviz-time.live"
    override var name = "Moviz Time"
    override val usesWebView = false
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    override val mainPage = mainPageOf(
        "$mainUrl" to "الأفلام المضافة حديثاً",
        "$mainUrl/category/%d8%a3%d9%81%d9%84%d8%a7%d9%85-2025/" to "أفلام 2025",
        "$mainUrl/category/%d8%a3%d9%81%d9%84%d8%a7%d9%85-2024/" to "أفلام 2024", 
        "$mainUrl/category/%d8%a3%d9%81%d9%84%d8%a7%d9%85-2023/" to "أفلام 2023",
        "$mainUrl/category/%d8%a3%d9%81%d9%84%d8%a7%d9%85-2022/" to "أفلام 2022",
        "$mainUrl/category/%d8%a3%d9%81%d9%84%d8%a7%d9%85-2021/" to "أفلام 2021",
        "$mainUrl/category/%d8%a3%d9%81%d9%84%d8%a7%d9%85-%d8%a3%d8%ac%d9%86%d8%a8%d9%8a%d8%a9/" to "أفلام أجنبية",
        "$mainUrl/category/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%a3%d8%ac%d9%86%d8%a8%d9%8a%d8%a9-%d9%85%d8%aa%d8%b1%d8%ac%d9%85%d8%a9-e/" to "مسلسلات أجنبية",
        "$mainUrl/category/%d9%82%d8%a7%d8%a6%d9%85%d8%a9-%d8%a7%d9%84%d8%a3%d9%86%d9%85%d9%8a-b/%d8%a3%d9%81%d9%84%d8%a7%d9%85-%d8%a3%d9%86%d9%85%d9%8a/" to "أفلام أنمي",
        "$mainUrl/category/%d9%82%d8%a7%d8%a6%d9%85%d8%a9-%d8%a7%d9%84%d8%a3%d9%86%d9%85%d9%8a-b/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%a3%d9%86%d9%85%d9%8a/" to "مسلسلات أنمي",
        "$mainUrl/category/%d8%a3%d9%81%d9%84%d8%a7%d9%85-%d8%a2%d8%b3%d9%8a%d9%88%d9%8a%d8%a9-%d9%85%d8%aa%d8%b1%d8%ac%d9%85%d8%a9/" to "أفلام آسيوية",
        "$mainUrl/category/%d8%a3%d9%81%d9%84%d8%a7%d9%85-%d9%87%d9%86%d8%af%d9%8a%d8%a9/" to "أفلام هندية",
        "$mainUrl/category/%d8%a3%d9%81%d9%84%d8%a7%d9%85-%d8%aa%d8%b1%d9%83%d9%8a%d8%a9/" to "أفلام تركية",
        "$mainUrl/category/imdb-top-250/" to "IMDb Top 250"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page > 1) "${request.data}page/$page/" else request.data
        val document = app.get(url).document
        
        // Use multiple selectors based on the site structure
        val home = document.select("article.pinbox, article, .post, .movie-item").mapNotNull { element ->
            val title = element.select("h2, h3, .title").text()
            val href = element.select("a").attr("href")
            val poster = element.select("img").attr("src")
            
            // Determine type from URL or element
            val isAnime = href.contains("/anime/") || element.select(".ribbon").text().contains("أنمي")
            val isSeries = href.contains("/series/") || element.select("._quality_tag").text().contains("حلقة")
            
            val type = when {
                isAnime -> TvType.Anime
                isSeries -> TvType.TvSeries
                else -> TvType.Movie
            }
            
            when (type) {
                TvType.Anime -> newAnimeSearchResponse(title, href) {
                    this.posterUrl = poster
                }
                TvType.TvSeries -> newTvSeriesSearchResponse(title, href) {
                    this.posterUrl = poster
                }
                else -> newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = poster
                }
            }
        }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=${query}").document
        
        return document.select("article.pinbox, article, .post, .movie-item").mapNotNull { element ->
            val title = element.select("h2, h3, .title").text()
            val href = element.select("a").attr("href")
            val poster = element.select("img").attr("src")
            
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        
        val title = document.selectFirst("h1")?.text() ?: "Unknown"
        val poster = document.selectFirst(".poster img, .wp-post-image")?.attr("src") ?: ""
        val description = document.selectFirst(".content, .entry-content, .plot")?.text() ?: ""
        
        // Check if it's a series by looking for episodes
        val hasEpisodes = document.select("._quality_tag").isNotEmpty() || 
                         document.select("[class*='episode']").isNotEmpty() ||
                         url.contains("/series/")
        
        val isAnime = url.contains("/anime/") || title.contains("أنمي")
        
        return when {
            hasEpisodes && isAnime -> {
                newAnimeLoadResponse(title, url, TvType.Anime) {
                    this.posterUrl = poster
                    this.plot = description
                }
            }
            hasEpisodes -> {
                newTvSeriesLoadResponse(title, url, TvType.TvSeries) {
                    this.posterUrl = poster
                    this.plot = description
                }
            }
            else -> {
                newMovieLoadResponse(title, url, TvType.Movie, url) {
                    this.posterUrl = poster
                    this.plot = description
                }
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
        
        // Extract iframes (most common)
        document.select("iframe").forEach { iframe ->
            val url = iframe.attr("src")
            if (url.isNotBlank()) {
                foundLinks = true
                loadExtractor(url, data, subtitleCallback, callback)
            }
        }
        
        // Extract video links
        document.select("a[href*='.mp4'], a[href*='.m3u8']").forEach { link ->
            val url = link.attr("href")
            if (url.isNotBlank()) {
                foundLinks = true
                loadExtractor(url, data, subtitleCallback, callback)
            }
        }
        
        return foundLinks
    }
}
