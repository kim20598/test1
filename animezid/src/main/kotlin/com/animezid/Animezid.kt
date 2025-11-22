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
    
    // Add proper headers to bypass Cloudflare
    private val headers = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "Accept-Language" to "ar,en;q=0.9",
        "Cache-Control" to "no-cache",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Referer" to mainUrl,
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "same-origin"
    )

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
        val document = app.get(request.data, headers = headers).document
        
        val items = document.select("a.movie").mapNotNull { it.toRealSearchResponse() }

        return newHomePageResponse(request.name, items, hasNext = false)
    }

    // ==================== SEARCH ====================

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search.php?keywords=${java.net.URLEncoder.encode(query, "UTF-8")}"
        val document = app.get(searchUrl, headers = headers).document
        
        return document.select("a.movie").mapNotNull { it.toRealSearchResponse() }
    }

    // ==================== LOAD ====================

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = headers).document
        
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

        // CORRECT SERIES DETECTION - Check for seasons and episodes tabs
        val hasSeasons = document.select(".tab-seasons li").isNotEmpty()
        val hasEpisodes = document.select(".SeasonsEpisodes a").isNotEmpty()
        val isMovie = !hasSeasons && !hasEpisodes

        if (isMovie) {
            // MOVIE - Use the current URL as play URL
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
            }
        } else {
            // SERIES - USE REAL EPISODES from SeasonsEpisodes
            val episodes = extractRealEpisodesFromSeasons(document, url)
            
            return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
            }
        }
    }

    // ==================== LOAD LINKS - IMPROVED ====================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, headers = headers).document
        
        var foundLinks = false

        // DEBUG: Print the HTML to see what we're getting
        println("=== ANIMEZID DEBUG ===")
        println("URL: $data")
        println("Has #xservers: ${document.select("#xservers").isNotEmpty()}")
        println("Has #Playerholder: ${document.select("#Playerholder").isNotEmpty()}")
        println("Has .dl links: ${document.select("a.dl").size}")
        document.select("#xservers button").forEachIndexed { index, button ->
            println("Server $index: ${button.attr("data-embed")}")
        }

        // METHOD 1: Extract from server buttons with data-embed (PRIORITY)
        document.select("#xservers button").forEach { serverButton ->
            val embedUrl = serverButton.attr("data-embed").trim()
            if (embedUrl.isNotBlank() && !embedUrl.startsWith("<")) {
                println("Found embed URL: $embedUrl")
                // Fix URL if needed
                val fixedUrl = when {
                    embedUrl.startsWith("//") -> "https:$embedUrl"
                    embedUrl.startsWith("/") -> "$mainUrl$embedUrl"
                    !embedUrl.startsWith("http") -> "https://$embedUrl"
                    else -> embedUrl
                }
                loadExtractor(fixedUrl, data, subtitleCallback, callback)
                foundLinks = true
            }
        }

        // METHOD 2: Extract from active iframe in Playerholder
        if (!foundLinks) {
            document.select("#Playerholder iframe").forEach { iframe ->
                val src = iframe.attr("src").trim()
                if (src.isNotBlank()) {
                    println("Found iframe src: $src")
                    val fixedSrc = when {
                        src.startsWith("//") -> "https:$src"
                        src.startsWith("/") -> "$mainUrl$src"
                        !src.startsWith("http") -> "https://$src"
                        else -> src
                    }
                    loadExtractor(fixedSrc, data, subtitleCallback, callback)
                    foundLinks = true
                }
            }
        }

        // METHOD 3: Extract download links (direct file links)
        if (!foundLinks) {
            document.select("a.dl.show_dl.api[target='_blank']").forEach { downloadLink ->
                val downloadUrl = downloadLink.attr("href").trim()
                if (downloadUrl.isNotBlank() && downloadUrl.contains("http")) {
                    println("Found download URL: $downloadUrl")
                    loadExtractor(downloadUrl, data, subtitleCallback, callback)
                    foundLinks = true
                }
            }
        }

        // METHOD 4: Look for any video-related links as fallback
        if (!foundLinks) {
            document.select("a[href*='play.php'], a[href*='skip.php'], a[href*='embed.php']").forEach { videoLink ->
                val videoUrl = videoLink.attr("href").trim().let {
                    if (it.startsWith("http")) it else "$mainUrl/$it"
                }
                if (videoUrl.isNotBlank()) {
                    println("Found video URL: $videoUrl")
                    loadExtractor(videoUrl, data, subtitleCallback, callback)
                    foundLinks = true
                }
            }
        }

        println("=== LINKS FOUND: $foundLinks ===")
        return foundLinks
    }

    // ==================== REAL EPISODE EXTRACTION FROM SEASONS ====================

    private fun extractRealEpisodesFromSeasons(document: org.jsoup.nodes.Document, baseUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        // EXTRACT REAL EPISODES from ALL SeasonsEpisodes divs
        document.select(".SeasonsEpisodes a").forEach { episodeElement ->
            val episodeUrl = episodeElement.attr("href").let {
                if (it.startsWith("http")) it else "$mainUrl/$it"
            }
            
            // Extract episode number from <em> tag
            val episodeNumberText = episodeElement.select("em").text().trim()
            val episodeNumber = episodeNumberText.toIntOrNull()
            
            // Extract episode title from <span> tag
            val episodeTitle = episodeElement.select("span").text().trim()
            
            val episodeName = if (episodeTitle.isNotBlank() && episodeTitle != "الحلقة") {
                episodeTitle
            } else {
                "الحلقة $episodeNumberText"
            }
            
            episodes.add(
                newEpisode(episodeUrl) {
                    this.name = episodeName
                    this.episode = episodeNumber ?: (episodes.size + 1)
                }
            )
        }

        return episodes.distinctBy { it.episode } // Remove duplicates
    }

    // ==================== REAL SEARCH RESPONSE ====================

    private fun Element.toRealSearchResponse(): SearchResponse? {
        val title = this.attr("title").takeIf { it.isNotBlank() } 
            ?: this.select(".title").text().takeIf { it.isNotBlank() }
            ?: return null
            
        val href = this.attr("href").takeIf { it.isNotBlank() } ?: return null
        val poster = this.select("img.lazy").attr("data-src")
        
        val fullPoster = if (poster.startsWith("http")) poster else "$mainUrl$poster"
        val fullHref = if (href.startsWith("http")) href else "$mainUrl$href"

        // Determine type based on content - movies usually have "فيلم" in title
        val isMovie = title.contains("فيلم") || href.contains("/movie/") || title.contains("فيلم")

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