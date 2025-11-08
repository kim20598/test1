package com.fushaar

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import org.jsoup.nodes.Document

class Fushaar : MainAPI() {
    override var mainUrl = "https://fushaar.com"
    override var name = "Fushaar"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // Common headers to look more like a real browser
    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
        "Referer" to mainUrl
    )

    private fun String.toAbsolute(): String {
        return when {
            startsWith("http") -> this
            startsWith("//") -> "https:$this"
            else -> "$mainUrl${if (startsWith("/")) "" else "/"}$this"
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/movies/" to "Latest Movies",
        "$mainUrl/series/" to "Latest Series",
        "$mainUrl/category/افلام-اسيوية/" to "Asian Movies"
    )

    // Helper fetch that uses headers and checks for Cloudflare
    private suspend fun fetchDocument(url: String): Document {
        val res = try {
            app.get(url, headers = defaultHeaders)
        } catch (e: Exception) {
            // network error -> rethrow so caller sees it
            throw e
        }
        val doc = res.document

        // Debug: if you want raw HTML log, uncomment:
        // println("DEBUG: fetched ${url} -> length=${doc.html().length}")

        // Simple detection for Cloudflare challenge / bot protection
        val htmlLower = doc.html().lowercase()
        val challengeIndicators = listOf("checking your browser", "cf-chl-bypass", "cloudflare", "please enable javascript")
        if (challengeIndicators.any { htmlLower.contains(it) }) {
            logger.warning("Cloudflare/bot challenge detected for $url")
        }
        return doc
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = if (request.data.endsWith("/")) request.data else request.data + "/"
        val url = if (page <= 1) base else "$base/page/$page/"
        val doc = fetchDocument(url)

        val items = doc.select("article, div.post, div.item, [class*=\"post\"]").mapNotNull { el ->
            el.toSearchResponse()
        }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=${query.replace(" ", "+")}"
        val doc = fetchDocument(url)
        return doc.select("article, div.post, li.movieItem, .post, .item").mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = fetchDocument(url)

        val title = doc.selectFirst("h1.entry-title, .singleTitle em, h1")?.text()?.trim() ?: "Unknown"
        val poster = doc.selectFirst("div.single-thumbnail img, .post img, article img")?.let {
            (it.attr("src").ifBlank { it.attr("data-src") }).toAbsolute()
        }
        val synopsis = doc.selectFirst("div.entry-content p, .extra-content p, .summary, .post-content p")?.text()
        val tags = doc.select("ul > li:contains(النوع) a, .tags a, .cats a").map { it.text() }
        val year = doc.selectFirst("ul > li:contains(السنه) a, .meta:contains(السنة), .year")?.text()?.filter { it.isDigit() }?.toIntOrNull()
        val isSeries = url.contains("/serie/") || url.contains("/season/") || doc.select(".EpsList, .episodes-list, .seasons").isNotEmpty()

        return if (!isSeries) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = synopsis
                this.tags = tags
                this.year = year
            }
        } else {
            val episodes = arrayListOf<Episode>()
            doc.select("div.EpsList > li > a, .EpsList li a, .episodes-list li a, .episodes a").forEach { el ->
                val eh = el.attr("href").toAbsolute()
                val etitle = el.attr("title").ifBlank { el.text().trim() }
                val episodeNumber = el.text().filter { it.isDigit() }.toIntOrNull() ?: 0
                episodes.add(newEpisode(eh, etitle, episodeNumber, episodeNumber))
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = synopsis
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
        val doc = fetchDocument(data)
        val found = mutableSetOf<String>()

        // 1) Direct links in <a> tags (mp4, m3u8, common download hosts)
        doc.select("a[href]").forEach { a ->
            val h = a.attr("href").ifBlank { return@forEach }
            val abs = h.toAbsolute()
            if (abs.contains(".mp4") || abs.contains(".m3u8") || abs.contains("/download") || abs.contains("1fichier") || abs.contains("fembed") || abs.contains("mixdrop") || abs.contains("mega")) {
                found.add(abs)
                callback(newExtractorLink("fushaar", "Direct", abs, data, 0, abs.endsWith(".m3u8")))
            }
        }

        // 2) iframe embeds
        doc.select("iframe[src]").mapNotNull { it.attr("src").ifBlank { null }?.toAbsolute() }.forEach { iframeUrl ->
            found.add(iframeUrl)
            // Let extractor system attempt to resolve the iframe provider
            loadExtractor(iframeUrl, data, subtitleCallback, callback)
        }

        // 3) Links inside script tags (m3u8, mp4)
        val scriptLinks = mutableSetOf<String>()
        doc.select("script").forEach { s ->
            val text = s.data()
            Regex("(https?:\\\\?/\\\\?/[^'\"\\s]+\\.(?:m3u8|mp4))").findAll(text).forEach { m ->
                scriptLinks.add(m.groupValues[1].replace("\\/", "/"))
            }
            Regex("(https?://[^'\"\\s]+(?:m3u8|mp4))").findAll(text).forEach { m ->
                scriptLinks.add(m.groupValues[1])
            }
        }
        scriptLinks.forEach { url ->
            found.add(url)
            callback(newExtractorLink("fushaar", "Script", url, data, 0, url.endsWith(".m3u8")))
        }

        // Return true only if we found at least one candidate link
        return found.isNotEmpty()
    }
}
