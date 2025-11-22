package com.animezid

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class Animezid : MainAPI() {
    override var lang = "ar"
    override var mainUrl = "https://animezid.cam"
    override var name = "Animezid"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Anime, TvType.Cartoon, TvType.TvSeries)

    // ==================== UTILITY FUNCTIONS ====================
    
    private fun String.cleanTitle(): String {
        return this.replace(
            "مشاهدة|تحميل|انمي|مترجم|اون لاين|بجودة عالية|الحلقة|مسلسل".toRegex(),
            ""
        ).trim()
    }

    private fun String.getIntFromText(): Int? {
        return Regex("""\d+""").find(this)?.groupValues?.firstOrNull()?.toIntOrNull()
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        return try {
            val titleElement = selectFirst("h3, h2, .title, .anime-title") ?: return null
            val title = titleElement.text().cleanTitle()
            
            val linkElement = selectFirst("a") ?: return null
            val href = linkElement.attr("href").let {
                if (it.startsWith("http")) it else "$mainUrl${it.removePrefix("/")}"
            }
            
            val posterUrl = selectFirst("img")?.let { img ->
                img.attr("data-src")
                    .ifBlank { img.attr("data-lazy-src") }
                    .ifBlank { img.attr("src") }
            }?.let {
                if (it.startsWith("http")) it else "$mainUrl${it.removePrefix("/")}"
            }

            val type = when {
                href.contains("/anime/") -> TvType.Anime
                href.contains("/cartoon/") -> TvType.Cartoon
                else -> TvType.TvSeries
            }

            newAnimeSearchResponse(title, href, type) {
                this.posterUrl = posterUrl
            }
        } catch (e: Exception) {
            null
        }
    }

    // ==================== MAIN PAGE ====================

    override val mainPage = mainPageOf(
        "$mainUrl/anime/page/" to "أنمي",
        "$mainUrl/cartoon/page/" to "كرتون",
        "$mainUrl/category/anime-movies/page/" to "أفلام أنمي",
        "$mainUrl/category/japanese-anime/page/" to "أنمي ياباني",
        "$mainUrl/category/chinese-anime/page/" to "أنمي صيني",
        "$mainUrl/category/korean-anime/page/" to "أنمي كوري",
        "$mainUrl/ongoing/page/" to "مستمر",
        "$mainUrl/completed/page/" to "مكتمل"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "${request.data}$page/"
        
        return try {
            val document = app.get(url).document
            
            val items = document.select(
                "article.anime-card, " +
                "div.anime-item, " +
                "div.post-item, " +
                ".content-item, " +
                ".anime-box, " +
                "article"
            ).mapNotNull { element ->
                element.toSearchResponse()
            }

            newHomePageResponse(
                list = HomePageList(request.name, items),
                hasNext = items.isNotEmpty()
            )
        } catch (e: Exception) {
            newHomePageResponse(request.name, emptyList())
        }
    }

    // ==================== SEARCH ====================

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.length < 2) return emptyList()
        
        return try {
            val searchUrl = "$mainUrl/?s=$query"
            val document = app.get(searchUrl).document
            
            document.select(
                "article.anime-card, " +
                "div.anime-item, " +
                "div.post-item, " +
                ".search-result, " +
                ".content-item, " +
                "article"
            ).mapNotNull { element ->
                element.toSearchResponse()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ==================== LOAD (SERIES/MOVIE PAGE) ====================

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        
        val title = document.selectFirst(
            "h1.entry-title, " +
            "h1.anime-title, " +
            "h1.post-title, " +
            "h1"
        )?.text()?.cleanTitle() ?: "Unknown"
        
        val posterUrl = document.selectFirst(
            ".anime-poster img, " +
            ".post-thumbnail img, " +
            ".single-poster img, " +
            "img.wp-post-image, " +
            "img"
        )?.let { img ->
            img.attr("data-src")
                .ifBlank { img.attr("src") }
        }?.let {
            if (it.startsWith("http")) it else "$mainUrl${it.removePrefix("/")}"
        }
        
        val description = document.selectFirst(
            ".anime-description, " +
            ".entry-content p, " +
            ".story, " +
            ".synopsis"
        )?.text()?.trim()
        
        val tags = document.select(
            ".anime-genres a, " +
            ".genres a, " +
            "a[rel=tag]"
        ).map { it.text() }
        
        val year = document.selectFirst(
            ".year, " +
            ".release-year, " +
            "span:contains(السنة)"
        )?.text()?.getIntFromText()

        // Extract episodes
        val episodes = mutableListOf<Episode>()
        
        // Pattern 1: Episode list
        document.select(
            ".episode-list a, " +
            ".episodes-list a, " +
            "ul.eps-list li a, " +
            ".episode-item a"
        ).forEach { epElement ->
            val epHref = epElement.attr("href").let {
                if (it.startsWith("http")) it else "$mainUrl${it.removePrefix("/")}"
            }
            val epTitle = epElement.text().trim()
            val epNum = epTitle.getIntFromText()
            
            if (epHref.isNotBlank()) {
                episodes.add(
                    Episode(
                        data = epHref,
                        name = epTitle.ifBlank { "الحلقة $epNum" },
                        episode = epNum
                    )
                )
            }
        }

        // Pattern 2: Season-based episodes
        document.select(".season-list .season, .seasons-list li").forEach { seasonElement ->
            val seasonNumber = seasonElement.text().getIntFromText() ?: 1
            
            seasonElement.select("a, .episode-link").forEach { epElement ->
                val epHref = epElement.attr("href").let {
                    if (it.startsWith("http")) it else "$mainUrl${it.removePrefix("/")}"
                }
                val epTitle = epElement.text().trim()
                val epNum = epTitle.getIntFromText()
                
                if (epHref.isNotBlank()) {
                    episodes.add(
                        Episode(
                            data = epHref,
                            name = epTitle,
                            episode = epNum,
                            season = seasonNumber
                        )
                    )
                }
            }
        }

        val isSeries = episodes.isNotEmpty()

        return if (isSeries) {
            newAnimeLoadResponse(title, url, TvType.Anime) {
                addEpisodes(DubStatus.Subbed, episodes.reversed())
                this.posterUrl = posterUrl
                this.plot = description
                this.tags = tags
                this.year = year
            }
        } else {
            newMovieLoadResponse(title, url, TvType.AnimeMovie, url) {
                this.posterUrl = posterUrl
                this.plot = description
                this.tags = tags
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
        try {
            val document = app.get(data).document
            
            // Pattern 1: Direct iframes
            document.select("iframe").forEach { iframe ->
                val src = iframe.attr("src")
                    .ifBlank { iframe.attr("data-src") }
                    .let {
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
            
            // Pattern 2: Server buttons/tabs
            document.select(
                ".server-list li, " +
                ".watch-servers li, " +
                "button[data-server], " +
                "a[data-embed]"
            ).forEach { 
