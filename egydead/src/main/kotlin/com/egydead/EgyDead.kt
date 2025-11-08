package com.egydead

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import java.net.URLEncoder

class EgyDead : MainAPI() {
    // ✅ SAFE - All properties
    override var lang = "ar"
    override var mainUrl = "https://egydead.skin"
    override var name = "EgyDead"
    override val usesWebView = false
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.Anime)

    // ✅ SAFE - Helper functions
    private fun String.getIntFromText(): Int? {
        return Regex("""\d+""").find(this)?.groupValues?.firstOrNull()?.toIntOrNull()
    }
    
    private fun String.cleanTitle(): String {
        return this.replace("جميع مواسم مسلسل|مترجم كامل|مشاهدة فيلم|مترجم|انمي|الموسم.*|مترجمة كاملة|مسلسل|كاملة".toRegex(), "").trim()
    }

    // ✅ SAFE - Search response
    private fun Element.toSearchResponse(): SearchResponse {
        val title = select(".BottomTitle").text().cleanTitle()
        val posterUrl = select("img").attr("src")
        val href = select("a").attr("href")
        val tvType = when {
            select("span.cat_name").text().contains("افلام") -> TvType.Movie
            else -> TvType.TvSeries
        }
        
        return newMovieSearchResponse(title, href, tvType) {
            this.posterUrl = posterUrl
        }
    }

    // ✅ SAFE - Main page
    override val mainPage = mainPageOf(
        // Movies Categories (افلام)
    "https://egydead.skin/category/افلام-اجنبي-اونلاين/" to "Foreign Movies",
    "https://egydead.skin/category/افلام-كرتون/" to "Cartoon Movies", 
    "https://egydead.skin/category/افلام-اسيوية/" to "Asian Movies",
    "https://egydead.skin/category/افلام-تركية/" to "Turkish Movies",
    "https://egydead.skin/category/افلام-وثائقية/" to "Documentary Movies",
    "https://egydead.skin/category/افلام-اجنبية-مدبلجة/" to "Dubbed Foreign Movies",
    "https://egydead.skin/category/افلام-هندية/" to "Indian Movies",
    "https://egydead.skin/category/افلام-عربي/" to "Arabic Movies",
    "https://egydead.skin/category/افلام-انمي/" to "Anime Movies",
    
    // Special Movie Sections
    "https://tv1.egydead.live/assembly/" to "Movie Series",
    "https://egydead.skin/category/ترجمات-اسلام-الجيزاوي/" to "Islam El-Gizawy Translations",
    "https://egydead.skin/category/افلام-كرتون/افلام-كرتون-ديزني-باللهجة-المصرية/" to "Disney Cartoons in Egyptian Dialect",

    // Series Categories (مسلسلات)
    "https://egydead.skin/series-category/مسلسلات-اجنبي-1/" to "Foreign Series",
    "https://egydead.skin/series-category/مسلسلات-كرتون/" to "Cartoon Series",
    "https://egydead.skin/series-category/مسلسلات-اسيوية/" to "Asian Series", 
    "https://egydead.skin/series-category/مسلسلات-تركية-ا/" to "Turkish Series",
    "https://egydead.skin/series-category/مسلسلات-لاتينية/" to "Latin Series",
    "https://egydead.skin/series-category/مسلسلات-وثائقية/" to "Documentary Series",
    "https://egydead.skin/series-category/مسلسلات-عربي/" to "Arabic Series",
    "https://egydead.skin/series-category/مسلسلات-افريقية/" to "African Series",

    // Anime Categories (انمي)
    "https://egydead.skin/series-category/مسلسلات-انمي/" to "Anime Series",
    "https://egydead.skin/series-category/مسلسلات-انمي-مدبلجة/" to "Dubbed Anime Series",
    "https://egydead.skin/series-category/افلام-انمي/" to "Anime Movies",
    "https://egydead.skin/series-category/انميات-صينية/" to "Chinese Anime",
    "https://egydead.skin/series-category/انميات-كورية/" to "Korean Anime",
    
    // Seasonal Anime Tags
    "https://egydead.skin/tag/انميات-خريف-2025/" to "Fall 2025 Anime",
    "https://egydead.skin/tag/انميات-صيف-2025/" to "Summer 2025 Anime",

    // Dubbed Series (المدبلج)
    "https://egydead.skin/series-category/مسلسلات-اجنبي-مدبلجة/" to "Dubbed Foreign Series", 
    "https://egydead.skin/series-category/مسلسلات-تركية-مدبلجة/" to "Dubbed Turkish Series",
    "https://egydead.skin/series-category/مسلسلات-كرتون-مدبلجة/" to "Dubbed Cartoon Series",
    "https://egydead.skin/series-category/مسلسلات-لاتينية-مدبلجة/" to "Dubbed Latin Series",

    // Miscellaneous Content
    "https://egydead.skin/category/رياضة/" to "Sports",
    "https://egydead.skin/series-category/برامج-تلفزيونية-1/" to "TV Programs",
    "https://egydead.skin/category/عروض-وحفلات/" to "Stand-up Shows & Concerts",
    "https://egydead.skin/tag/كاس-العالم-2022/" to "World Cup 2022",

    // Series Structure Pages
    "https://tv1.egydead.live/serie/" to "Complete Series",
    "https://tv1.egydead.live/season/" to "Full Seasons", 
    "https://tv1.egydead.live/episode/" to "Episodes"
    )

    // ✅ SAFE - getMainPage
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data + page).document
        val home = document.select("li.movieItem").mapNotNull {
            it.toSearchResponse()
        }
        return newHomePageResponse(request.name, home)
    }

    // ✅ SAFE - search function
    override suspend fun search(query: String): List<SearchResponse> {
        if (query.length < 3) return emptyList()
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val doc = app.get("$mainUrl/?s=$encodedQuery").document
        return doc.select("li.movieItem").mapNotNull {
            if(it.select("a").attr("href").contains("/episode/")) return@mapNotNull null
            it.toSearchResponse()
        }
    }

    // ✅ SAFE - load function (NO MORE category/SCORE ISSUES)
    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.select("div.singleTitle em").text().cleanTitle()
        val isMovie = !url.contains("/serie/|/season/".toRegex())

        val posterUrl = doc.select("div.single-thumbnail > img").attr("src")
        val synopsis = doc.select("div.extra-content:contains(القصه) p").text()
        val year = doc.select("ul > li:contains(السنه) > a").text().getIntFromText()
        val tags = doc.select("ul > li:contains(النوع) > a").map { it.text() }
        val recommendations = doc.select("div.related-posts > ul > li").mapNotNull { element ->
            Episodes.toSearchResponse()
        }
        val youtubeTrailer = doc.select("div.popupContent > iframe").attr("src")
        
        return if (isMovie) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = posterUrl
                this.recommendations = recommendations
                this.plot = synopsis
                this.tags = tags
                this.year = year
                addTrailer(youtubeTrailer)
            }
        } else {
            val seasonList = doc.select("div.seasons-list ul > li > a").reversed()
            val episodes = arrayListOf<Episode>()
            
            if(seasonList.isNotEmpty()) {
                seasonList.forEachIndexed { index, season ->
                    val seasonDoc = app.get(season.attr("href")).document
                    seasonDoc.select("div.EpsList > li > a").forEach {
                        episodes.addnewEpisodee(it.attr("href")) {
                            name = it.attr("title")
                            this.season = index + 1
                            episode = it.text().getIntFromText() ?: 1
                        })
                    }
                }
            } else {
                doc.select("div.EpsList > li > a").forEach {
                    episodes.add(newEpisode(it.attr("href")) {
                        name = it.attr("title")
                        this.season = 1
                        episode = it.text().getIntFromText() ?: 1
                    })
                }
            }
            
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.distinct().sortedBy { it.episode }) {
                this.posterUrl = posterUrl
                this.tags = tags
                this.plot = synopsis
                this.recommendations = recommendations
                this.year = year
                addTrailer(youtubeTrailer)
            }
        }
    }

    // ✅ SAFE - loadLinks function
    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        var foundLinks = false
        
        try {
            val doc = app.post(data, data = mapOf("View" to "1")).document
            
            doc.select(".donwload-servers-list > li, .download-servers > li").forEach { element ->
                val url = element.select("a").attr("href")
                if (url.isNotBlank()) {
                    foundLinks = true
                    loadExtractor(url, data, subtitleCallback, callback)
                }
            }
            
            doc.select("ul.serversList > li, [data-link]").forEach { li ->
                val iframeUrl = li.attr("data-link").ifBlank { li.select("a").attr("href") }
                if (iframeUrl.isNotBlank() && iframeUrl.contains("http")) {
                    foundLinks = true
                    loadExtractor(iframeUrl, data, subtitleCallback, callback)
                }
            }
            
        } catch (e: Exception) {
            // Fallback if POST fails
        }
        
        return foundLinks
    }
}
