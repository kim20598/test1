package com.animezid

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element
import java.net.URLEncoder

class Animezid : MainAPI() {
    override var lang = "ar"
    override var mainUrl = "https://animezid.cam"
    override var name = "Animezid"
    override val usesWebView = true
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
        val searchUrl = "$mainUrl/search.php?keywords=${URLEncoder.encode(query, "UTF-8")}"
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

    // ==================== LOAD LINKS - REWRITTEN USING AKWAM PATTERN ====================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return kotlin.runCatching {
            val episodeUrl = data
            val step1Doc = app.get(episodeUrl).document

            // METHOD 1: Try to extract from server buttons first
            val serverButtons = step1Doc.select("#xservers button")
            var foundLinks = false

            for (serverButton in serverButtons) {
                val embedUrl = serverButton.attr("data-embed").trim()
                if (embedUrl.isNotBlank() && !embedUrl.startsWith("<")) {
                    // Fix URL if needed
                    val fixedUrl = when {
                        embedUrl.startsWith("//") -> "https:$embedUrl"
                        embedUrl.startsWith("/") -> "$mainUrl$embedUrl"
                        !embedUrl.startsWith("http") -> "https://$embedUrl"
                        else -> embedUrl
                    }
                    
                    // Try to load extractor for this embed URL
                    loadExtractor(fixedUrl, episodeUrl, subtitleCallback, callback)
                    foundLinks = true
                }
            }

            // METHOD 2: If no server buttons found, try direct iframe extraction
            if (!foundLinks) {
                val iframe = step1Doc.selectFirst("#Playerholder iframe")
                val iframeSrc = iframe?.attr("src")?.trim()
                if (iframeSrc?.isNotBlank() == true) {
                    val fixedIframeUrl = when {
                        iframeSrc.startsWith("//") -> "https:$iframeSrc"
                        iframeSrc.startsWith("/") -> "$mainUrl$iframeSrc"
                        !iframeSrc.startsWith("http") -> "https://$iframeSrc"
                        else -> iframeSrc
                    }
                    loadExtractor(fixedIframeUrl, episodeUrl, subtitleCallback, callback)
                    foundLinks = true
                }
            }

            // METHOD 3: Try download links as direct video sources
            if (!foundLinks) {
                val downloadLinks = step1Doc.select("a.dl.show_dl.api[target='_blank']")
                for (downloadLink in downloadLinks) {
                    val downloadUrl = downloadLink.attr("href").trim()
                    if (downloadUrl.isNotBlank() && downloadUrl.contains("http")) {
                        // Create direct video link for download URLs
                        callback(
                            newExtractorLink(
                                source = name,
                                name = "Animezid Download",
                                url = downloadUrl
                            ) {
                                this.referer = episodeUrl
                                this.quality = getQualityFromName("1080p")
                                this.type = ExtractorLinkType.VIDEO
                            }
                        )
                        foundLinks = true
                    }
                }
            }

            // METHOD 4: Fallback - look for any play/skip/embed links
            if (!foundLinks) {
                val videoLinks = step1Doc.select("a[href*='play.php'], a[href*='skip.php'], a[href*='embed.php']")
                for (videoLink in videoLinks) {
                    val videoUrl = videoLink.attr("href").trim().let {
                        if (it.startsWith("http")) it else "$mainUrl/$it"
                    }
                    if (videoUrl.isNotBlank()) {
                        loadExtractor(videoUrl, episodeUrl, subtitleCallback, callback)
                        foundLinks = true
                    }
                }
            }

            foundLinks
        }.getOrElse { false }
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

        return episodes.distinctBy { it.episode }
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
