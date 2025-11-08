package com.fushaar

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import org.jsoup.Jsoup

class Fushaar : MainAPI() {
    override var mainUrl = "https://fushaar.com"
    override var name = "Fushaar"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    private fun String.toAbsolute(): String {
        return when {
            this.startsWith("http") -> this
            this.startsWith("//") -> "https:$this"
            else -> "${mainUrl}${if (this.startsWith("/")) "" else "/"}$this"
        }
    }

    // Keep mainPage small and practical
    override val mainPage = mainPageOf(
        "$mainUrl/category/افلام-اجنبي/?page=" to "أفلام أجنبية",
        "$mainUrl/category/افلام-اسيوية/?page=" to "أفلام آسيوية",
        "$mainUrl/season/?page=" to "مسلسلات"
    )

    // Helper to extract lazy image src or normal src
    private fun Element.imageSrc(): String? {
        val img = selectFirst("img") ?: return null
        return (img.attr("data-src").ifBlank { img.attr("src") }).ifBlank { null }?.let {
            if (it.startsWith("data:")) null else it.toAbsolute()
        }
    }

    // Convert an article/item element to a SearchResponse
    private fun Element.toSearchResponse(): SearchResponse? {
        // Title in h3 or h2
        val title = selectFirst("h3, h2")?.text()?.trim() ?: return null
        // anchor
        val a = selectFirst("a") ?: return null
        val href = a.attr("href").trim().toAbsolute()
        // poster
        val poster = imageSrc()
        // determine type by category label if present
        val catText = selectFirst(".cat_name, .meta, .year, .info")?.text()?.trim() ?: ""
        val tvType = when {
            catText.contains("افلام") || href.contains("/movie/") -> TvType.Movie
            catText.contains("مسلسل") || href.contains("/serie/") || href.contains("/season/") -> TvType.TvSeries
            else -> TvType.Movie // default to movie but load() will reassess
        }

        return newMovieSearchResponse(title, href, tvType) {
            this.posterUrl = poster
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // request.data already has the category base URL like ...?page=
        val url = if (request.data.endsWith("=")) "${request.data}${page}" else request.data.replace("=page", "$page")
        val doc = try {
            app.get(url).document
        } catch (e: Throwable) {
            return newHomePageResponse(request.name, emptyList())
        }

        // Fushaar main listing uses article elements
        val items = doc.select("article, .post, .movieItem, .item").mapNotNull { it.toSearchResponse() }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.replace(" ", "+")
        val url = "$mainUrl/?s=$q"
        val doc = try {
            app.get(url).document
        } catch (e: Throwable) {
            return emptyList()
        }

        // results appear as article or .item
        return doc.select("article, li.movieItem, .item").mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = try {
            app.get(url).document
        } catch (e: Throwable) {
            return newMovieLoadResponse("خطأ فى التحميل", url, TvType.Movie, url) {}
        }

        val title = doc.selectFirst("div.singleTitle em, h1.entry-title, h1")?.text()?.trim() ?: "غير معروف"
        val poster = doc.selectFirst("div.single-thumbnail img, .post img")?.let {
            it.attr("data-src").ifBlank { it.attr("src") }?.let { s -> if (s.startsWith("http")) s else s.toAbsolute() }
        }
        val plot = doc.selectFirst("div.extra-content p, .summary, .description")?.text()
        val tags = doc.select("ul > li:contains(النوع) > a, .tags a").map { it.text() }
        val year = doc.selectFirst("ul > li:contains(السنه) > a")?.text()?.toIntOrNull()

        // Determine if page is series by presence of episodes list
        val eps = doc.select("div.EpsList > li > a, .episodes-list li a, .EpsList li a")
        val isSeries = eps.isNotEmpty() || url.contains("/serie/") || url.contains("/season/")

        return if (!isSeries) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
                this.year = year
            }
        } else {
            val episodes = arrayListOf<Episode>()
            eps.forEach { el ->
                val epHref = el.attr("href").toAbsolute()
                val epTitle = el.attr("title").ifBlank { el.text().trim() }
                val epNumber = el.text().trim().toIntOrNull() ?: null
                // Episode constructor may be deprecated in your environment, but most projects still compile with it.
                // If you get deprecation errors convert to newEpisode(...) helper if available.
                episodes.add(Episode(epHref, epTitle, 1, epNumber ?: 0))
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
                this.year = year
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = try {
            app.get(data).document
        } catch (e: Throwable) {
            return false
        }

        var found = false

        // 1) Download servers list (links with class ser-link)
        doc.select("ul.donwload-servers-list li a.ser-link, .download-servers-list a, a.ser-link").forEach { a ->
            val href = a.attr("href").trim().let { if (it.startsWith("http")) it else it.toAbsolute() }
            if (href.isNotBlank()) {
                found = true
                // Let the extractor handle extraction (no deprecated ExtractorLink constructor used here)
                loadExtractor(href, data, subtitleCallback, callback)
            }
        }

        // 2) Servers list (data-link attributes)
        doc.select(".serversList li[data-link]").forEach { li ->
            val link = li.attr("data-link").trim().let { if (it.startsWith("http")) it else it.toAbsolute() }
            if (link.isNotBlank()) {
                found = true
                loadExtractor(link, data, subtitleCallback, callback)
            }
        }

        // 3) direct anchors to mp4 / m3u8
        doc.select("a[href*='.mp4'], a[href*='.m3u8']").forEach { a ->
            val href = a.attr("href").trim().let { if (it.startsWith("http")) it else it.toAbsolute() }
            if (href.isNotBlank()) {
                found = true
                loadExtractor(href, data, subtitleCallback, callback)
            }
        }

        // 4) iframes (embed players)
        doc.select("iframe").forEach { iframe ->
            val src = iframe.attr("src").trim().let { if (it.startsWith("http")) it else it.toAbsolute() }
            if (src.isNotBlank()) {
                found = true
                loadExtractor(src, data, subtitleCallback, callback)
            }
        }

        return found
    }

}
