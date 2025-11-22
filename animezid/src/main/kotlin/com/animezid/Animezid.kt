package com.animezid

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class Animezid : MainAPI() {
    override var lang = "ar"
    override var mainUrl = "https://animezid.cam"
    override var name = "Animezid"
    override val usesWebView = false
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Anime, TvType.Movie)

    // ==================== MAIN PAGE ====================
    
    override val mainPage = mainPageOf(
        "$mainUrl/" to "أحدث الإضافات",
        "$mainUrl/anime/" to "أنمي",
        "$mainUrl/movies/" to "أفلام أنمي", 
        "$mainUrl/ongoing/" to "مسلسلات مستمرة",
        "$mainUrl/completed/" to "مسلسلات مكتملة",
        "$mainUrl/category.php?cat=disney-masr" to "ديزني بالمصري",
        "$mainUrl/category.php?cat=spacetoon" to "سبيستون",
        "$mainUrl/category.php?cat=new-movies" to "أحدث الافلام",
        "$mainUrl/category.php?cat=newvideos" to "أفلام جديدة",
        "$mainUrl/category.php?cat=subbed-animation" to "أفلام انيميشن مترجمة",
        "$mainUrl/category.php?cat=dubbed-animation" to "افلام كرتون جديدة",
        "$mainUrl/category.php?cat=kimetsu-no-yaiba-3" to "قاتل الشياطين الموسم الثالث",
        "$mainUrl/category.php?cat=one-piece" to "ون بيس",
        "$mainUrl/category.php?cat=conan-ar" to "المحقق كونان",
        "$mainUrl/category.php?cat=dragon-ball-super-ar" to "دراغون بول سوبر",
        "$mainUrl/category.php?cat=attack-on-titan" to "هجوم العمالقة",
        "$mainUrl/category.php?cat=jujutsu-kaisen-s-2" to "جوجوتسو كايسن الموسم الثاني",
        "$mainUrl/category.php?cat=miraculous-ar" to "الدعسوقه والقط الاسود"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) "${request.data}page/$page/" else request.data
        val document = app.get(url).document
        
        val items = document.select("a.movie").mapNotNull { element ->
            element.toSearchResponse()
        }

        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    // ==================== SEARCH ====================

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.length < 2) return emptyList()
        
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val searchUrl = "$mainUrl/search.php?keywords=$encodedQuery"
        val document = app.get(searchUrl).document
        
        return document.select("a.movie").mapNotNull { element ->
            element.toSearchResponse()
        }
    }

    // ==================== LOAD ====================

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        
        // Extract title
        val title = document.selectFirst("h1 span[itemprop=name]")?.text() 
            ?: document.selectFirst("h1")?.text() 
            ?: "Unknown"
        
        // Extract poster
        val posterUrl = document.selectFirst("img.lazy")?.attr("data-src")?.let {
            if (it.startsWith("http")) it else "$mainUrl$it"
        } ?: ""

        // Extract description
        val description = document.selectFirst(".pm-video-description")?.text()?.trim() ?: ""
        
        // Extract year
        val year = document.selectFirst("a[href*='filter=years']")?.text()?.toIntOrNull()

        // Check if it's a series by looking for episode patterns
        val isSeries = url.contains("/anime/") || 
                      document.select("a[href*='watch.php']").isNotEmpty() ||
                      title.contains("الحلقة")

        if (isSeries) {
            // For series, create a single episode that links to the main page
            val episodes = listOf(
                newEpisode(url) {
                    this.name = "مشاهدة الحلقات"
                    this.episode = 1
                }
            )

            return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                this.posterUrl = posterUrl
                this.plot = description
                this.year = year
            }
        } else {
            // For movies
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = posterUrl
                this.plot = description
                this.year = year
            }
        }
    }

    // ==================== LOAD LINKS ====================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        
        // Extract video URL from play button
        val playButtonUrl = document.select("a[href*='play.php?vid=']").firstOrNull()?.attr("href")
        if (!playButtonUrl.isNullOrBlank()) {
            val fullPlayUrl = if (playButtonUrl.startsWith("http")) playButtonUrl else "$mainUrl/$playButtonUrl"
            return loadExtractor(fullPlayUrl, data, subtitleCallback, callback)
        }
        
        // Fallback: try iframe sources
        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("src").let {
                when {
                    it.startsWith("//") -> "https:$it"
                    it.startsWith("/") -> "$mainUrl$it"
                    else -> it
                }
            }
            
            if (src.isNotBlank() && src.startsWith("http")) {
                loadExtractor(src, data, subtitleCallback, callback)
            }
        }
        
        return true
    }

    // ==================== UTILITY ====================

    private fun Element.toSearchResponse(): SearchResponse? {
        val title = this.attr("title")
        val href = this.attr("href")
        val poster = this.select("img.lazy").attr("data-src")
        
        if (title.isBlank() || href.isBlank()) return null

        // Determine type
        val type = when {
            title.contains("فيلم") || href.contains("/movie/") -> TvType.Movie
            else -> TvType.Anime
        }

        return if (type == TvType.Anime) {
            newTvSeriesSearchResponse(title, href, type) {
                this.posterUrl = if (poster.startsWith("http")) poster else "$mainUrl$poster"
            }
        } else {
            newMovieSearchResponse(title, href, type) {
                this.posterUrl = if (poster.startsWith("http")) poster else "$mainUrl$poster"
            }
        }
    }
}