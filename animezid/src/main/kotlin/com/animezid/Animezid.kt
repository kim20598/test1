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
        val document = app.get(request.data).document
        
        val items = document.select("a.movie").mapNotNull { it.toRealSearchResponse() }

        return newHomePageResponse(request.name, items, hasNext = false)
    }

    // ==================== SEARCH ====================

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search.php?keywords=${java.net.URLEncoder.encode(query, "UTF-8")}"
        val document = app.get(searchUrl).document
        
        return document.select("a.movie").mapNotNull { it.toRealSearchResponse() }
    }

    // ==================== LOAD ====================

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        
        // REAL title extraction from <h1><span itemprop="name">
        val title = document.selectFirst("h1 span[itemprop=name]")?.text() 
            ?: document.selectFirst("h1")?.text() 
            ?: return newMovieLoadResponse("", url, TvType.Movie, url) {
                this.posterUrl = ""
                this.plot = ""
            }
        
        // REAL poster extraction from img.lazy[data-src]
        val poster = document.selectFirst("img.lazy")?.attr("data-src")?.let { 
            if (it.startsWith("http")) it else "$mainUrl$it"
        } ?: ""

        // REAL description extraction from .pm-video-description
        val description = document.selectFirst(".pm-video-description")?.text()?.trim() ?: ""

        // Extract year from the table
        val year = document.selectFirst("a[href*='filter=years']")?.text()?.toIntOrNull()

        // Check if it's a movie or series - ONLY USE REAL DATA
        val hasEpisodes = document.select(".movies_small a.movie").isNotEmpty()
        val isMovie = url.contains("فيلم") || !hasEpisodes

        if (isMovie) {
            // MOVIE - Extract the REAL play URL from the button
            val playUrl = document.selectFirst("a[href*='skip.php']")?.attr("href") ?: url
            
            return newMovieLoadResponse(title, url, TvType.Movie, playUrl) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
            }
        } else {
            // SERIES - ONLY use episodes from similar content
            val episodes = extractRealEpisodes(document, url)
            
            // If no real episodes found, return nothing
            if (episodes.isEmpty()) {
                return newMovieLoadResponse(title, url, TvType.Movie, url) {
                    this.posterUrl = poster
                    this.plot = description
                    this.year = year
                }
            }
            
            return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                this.posterUrl = poster
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
        
        // REAL video extraction - look for skip.php links (the actual play button)
        val playLinks = document.select("a[href*='skip.php']")
        
        playLinks.forEach { playLink ->
            val playUrl = playLink.attr("href")
            if (playUrl.isNotBlank()) {
                loadExtractor(playUrl, data, subtitleCallback, callback)
            }
        }

        // Also check for direct play.php links
        document.select("a[href*='play.php']").forEach { playLink ->
            val playUrl = playLink.attr("href").let {
                if (it.startsWith("http")) it else "$mainUrl/$it"
            }
            loadExtractor(playUrl, data, subtitleCallback, callback)
        }

        return playLinks.isNotEmpty()
    }

    // ==================== REAL EPISODE EXTRACTION ====================

    private fun extractRealEpisodes(document: org.jsoup.nodes.Document, baseUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        // ONLY extract episodes from similar movies section (the carousel)
        document.select(".movies_small a.movie").forEachIndexed { index, episodeElement ->
            val episodeUrl = episodeElement.attr("href").let {
                if (it.startsWith("http")) it else "$mainUrl/$it"
            }
            val episodeText = episodeElement.select(".title").text().trim()
            
            episodes.add(
                newEpisode(episodeUrl) {
                    this.name = episodeText
                    this.episode = index + 1
                }
            )
        }

        return episodes
    }

    // ==================== REAL SEARCH RESPONSE ====================

    private fun Element.toRealSearchResponse(): SearchResponse? {
        val title = this.attr("title").takeIf { it.isNotBlank() } ?: return null
        val href = this.attr("href").takeIf { it.isNotBlank() } ?: return null
        val poster = this.select("img.lazy").attr("data-src")
        
        val fullPoster = if (poster.startsWith("http")) poster else "$mainUrl$poster"
        val fullHref = if (href.startsWith("http")) href else "$mainUrl$href"

        // Determine type based on title
        val isMovie = title.contains("فيلم") || href.contains("/movie/")

        return if (isMovie) {
            newMovieSearchResponse(title, fullHref, TvType.Movie) {
                this.posterUrl = fullPoster
            }
        } else {
            newTvSeriesSearchResponse(title, fullHref, TvType.Anime) {
                this.posterUrl = fullPoster
            }
        }
    }
}