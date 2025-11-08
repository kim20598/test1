package com.egydead

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import java.net.URLEncoder

class EgyDead : MainAPI() {
    override var lang = "ar"
    override var mainUrl = "https://egydead.skin"
    override var name = "EgyDead"
    override val usesWebView = false
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.Anime)

    // Secondary domain for special sections
    private val tvDomain = "https://tv1.egydead.live"

    private fun String.getIntFromText(): Int? {
        return Regex("""\d+""").find(this)?.groupValues?.firstOrNull()?.toIntOrNull()
    }
    
    private fun String.cleanTitle(): String {
        return this.replace("جميع مواسم مسلسل|مترجم كامل|مشاهدة فيلم|مترجم|انمي|الموسم.*|مترجمة كاملة|مسلسل|كاملة".toRegex(), "").trim()
    }

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

    override val mainPage = mainPageOf(
        // Movies Categories (افلام)
        "$mainUrl/category/%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d8%a7%d8%ac%d9%86%d8%a8%d9%8a-%d8%a7%d9%88%d9%86%d9%84%d8%a7%d9%8a%d9%86/?page=" to "Foreign Movies",
        "$mainUrl/category/%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d9%83%d8%b1%d8%aa%d9%88%d9%86/?page=" to "Cartoon Movies",
        "$mainUrl/category/%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d8%a7%d8%b3%d9%8a%d9%88%d9%8a%d8%a9/?page=" to "Asian Movies",
        "$mainUrl/category/%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d8%aa%d8%b1%d9%83%d9%8a%d8%a9/?page=" to "Turkish Movies",
        "$mainUrl/category/%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d9%88%d8%ab%d8%a7%d8%a6%d9%82%d9%8a%d8%a9/?page=" to "Documentary Movies",
        "$mainUrl/category/%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d8%a7%d8%ac%d9%86%d8%a8%d9%8a%d8%a9-%d9%85%d8%af%d8%a8%d9%84%d8%ac%d8%a9/?page=" to "Dubbed Foreign Movies",
        "$mainUrl/category/%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d9%87%d9%86%d8%af%d9%8a%d8%a9/?page=" to "Indian Movies",
        "$mainUrl/category/%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d8%b9%d8%b1%d8%a8%d9%8a/?page=" to "Arabic Movies",
        "$mainUrl/category/%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d8%a7%d9%86%d9%85%d9%8a/?page=" to "Anime Movies",
        
        // Series Categories (مسلسلات)
        "$mainUrl/series-category/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%a7%d8%ac%d9%86%d8%a8%d9%8a-1/?page=" to "Foreign Series",
        "$mainUrl/series-category/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d9%83%d8%b1%d8%aa%d9%88%d9%86/?page=" to "Cartoon Series",
        "$mainUrl/series-category/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%a7%d8%b3%d9%8a%d9%88%d9%8a%d8%a9/?page=" to "Asian Series",
        "$mainUrl/series-category/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%aa%d8%b1%d9%83%d9%8a%d8%a9-%d8%a7/?page=" to "Turkish Series",
        "$mainUrl/series-category/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d9%84%d8%a7%d8%aa%d9%8a%d9%86%d9%8a%d8%a9/?page=" to "Latin Series",
        "$mainUrl/series-category/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d9%88%d8%ab%d8%a7%d8%a6%d9%82%d9%8a%d8%a9/?page=" to "Documentary Series",
        "$mainUrl/series-category/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%b9%d8%b1%d8%a8%d9%8a/?page=" to "Arabic Series",
        "$mainUrl/series-category/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%a7%d9%81%d8%b1%d9%8a%d9%82%d9%8a%d8%a9/?page=" to "African Series",

        // Anime Categories (انمي)
        "$mainUrl/series-category/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%a7%d9%86%d9%85%d9%8a/?page=" to "Anime Series",
        "$mainUrl/series-category/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%a7%d9%86%d9%85%d9%8a-%d9%85%d8%af%d8%a8%d9%84%d8%ac%d8%a9/?page=" to "Dubbed Anime Series",
        "$mainUrl/series-category/%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d8%a7%d9%86%d9%85%d9%8a/?page=" to "Anime Movies",
        "$mainUrl/series-category/%d8%a7%d9%86%d9%85%d9%8a%d8%a7%d8%aa-%d8%b5%d9%8a%d9%86%d9%8a%d8%a9/?page=" to "Chinese Anime",
        "$mainUrl/series-category/%d8%a7%d9%86%d9%85%d9%8a%d8%a7%d8%aa-%d9%83%d9%88%d8%b1%d9%8a%d8%a9/?page=" to "Korean Anime",
        
        // Dubbed Series (المدبلج)
        "$mainUrl/series-category/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%a7%d8%ac%d9%86%d8%a8%d9%8a-%d9%85%d8%af%d8%a8%d9%84%d8%ac%d8%a9/?page=" to "Dubbed Foreign Series",
        "$mainUrl/series-category/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%aa%d8%b1%d9%83%d9%8a%d8%a9-%d9%85%d8%af%d8%a8%d9%84%d8%ac%d8%a9/?page=" to "Dubbed Turkish Series",
        "$mainUrl/series-category/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d9%83%d8%b1%d8%aa%d9%88%d9%86-%d9%85%d8%af%d8%a8%d9%84%d8%ac%d8%a9/?page=" to "Dubbed Cartoon Series",
        "$mainUrl/series-category/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d9%84%d8%a7%d8%aa%d9%8a%d9%86%d9%8a%d8%a9-%d9%85%d8%af%d8%a8%d9%84%d8%ac%d8%a9/?page=" to "Dubbed Latin Series",

        // Miscellaneous Content
        "$mainUrl/category/%d8%b1%d9%8a%d8%a7%d8%b6%d8%a9/?page=" to "Sports",
        "$mainUrl/series-category/%d8%a8%d8%b1%d8%a7%d9%85%d8%ac-%d8%aa%d9%84%d9%81%d8%b2%d9%8a%d9%88%d9%86%d9%8a%d8%a9-1/?page=" to "TV Programs",
        "$mainUrl/category/%d8%b9%d8%b1%d9%88%d8%b6-%d9%88%d8%ad%d9%81%d9%84%d8%a7%d8%aa/?page=" to "Stand-up Shows & Concerts",

        // Special TV Domain Sections
        "$tvDomain/assembly/?page=" to "Movie Series",
        "$tvDomain/serie/?page=" to "Complete Series", 
        "$tvDomain/season/?page=" to "Full Seasons",
        "$tvDomain/episode/?page=" to "Episodes"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data + page).document
        val home = document.select("li.movieItem").mapNotNull {
            it.toSearchResponse()
        }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.length < 3) return emptyList()
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val doc = app.get("$mainUrl/?s=$encodedQuery").document
        return doc.select("li.movieItem").mapNotNull {
            if(it.select("a").attr("href").contains("/episode/")) return@mapNotNull null
            it.toSearchResponse()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.select("div.singleTitle em").text().cleanTitle()
        val isMovie = !url.contains("/serie/|/season/".toRegex())

        val posterUrl = doc.select("div.single-thumbnail > img").attr("src")
        val synopsis = doc.select("div.extra-content:contains(القصه) p").text()
        val year = doc.select("ul > li:contains(السنه) > a").text().getIntFromText()
        val tags = doc.select("ul > li:contains(النوع) > a").map { it.text() }
        val recommendations = doc.select("div.related-posts > ul > li").mapNotNull { element ->
            element.toSearchResponse()
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
                        episodes.add(newEpisode(it.attr("href")) {
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
