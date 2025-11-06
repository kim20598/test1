package com.akwam

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class Akwam : MainAPI() {
    override var mainUrl = "https://ak.sv"  // ← NO SPACE!
    override var name = "Akwam"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // Make app mutable to add headers
    private var app = app

    init {
        app = app.newBuilder().apply {
            addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36")
            addHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
            addHeader("Accept-Language", "ar,en-US;q=0.9,en;q=0.8")
            addHeader("Referer", mainUrl)
            addHeader("Origin", mainUrl)
        }.build()
    }

    private fun String.toAbsolute(): String {
        return when {
            this.startsWith("http") -> this
            this.startsWith("//") -> "https:$this"
            else -> "$mainUrl${if (this.startsWith("/")) "" else "/"}$this"
        }
    }

    // ————— SEARCH —————
    override suspend fun search(query: String): List<SearchResponse> {
        delay(300) // Reduce bot detection
        val url = "$mainUrl/search?q=${query.encodeURL()}"
        val doc = app.get(url).document

        return doc.select("div.entry-box").mapNotNull { box ->
            val titleEl = box.selectFirst("h3.entry-title a") ?: return@mapNotNull null
            val title = titleEl.text().trim()
            val href = titleEl.attr("href").toAbsolute()
            val poster = box.selectFirst("img")?.run {
                attr("data-src")?.toAbsolute() ?: attr("src")?.toAbsolute()
            }

            val type = when {
                href.contains("/series/") || href.contains("/anime/") -> TvType.TvSeries
                else -> TvType.Movie
            }

            newMovieSearchResponse(title, href, type) {
                this.posterUrl = poster
            }
        }
    }

    // ————— MAIN PAGE —————
    override val mainPage = mainPageOf(
        "$mainUrl/movies" to "أفلام",
        "$mainUrl/series" to "مسلسلات",
        "$mainUrl/anime" to "أنمي"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        delay(200)
        val url = if (page == 1) request.data else "${request.data}/page/$page"
        val doc = app.get(url).document

        val type = when {
            request.data.contains("/series") || request.data.contains("/anime") -> TvType.TvSeries
            else -> TvType.Movie
        }

        val items = doc.select("div.entry-box").mapNotNull { box ->
            val titleEl = box.selectFirst("h3.entry-title a") ?: return@mapNotNull null
            val title = titleEl.text().trim()
            val href = titleEl.attr("href").toAbsolute()
            val poster = box.selectFirst("img")?.run {
                attr("data-src")?.toAbsolute() ?: attr("src")?.toAbsolute()
            }

            newMovieSearchResponse(title, href, type) {
                this.posterUrl = poster
            }
        }

        return newHomePageResponse(request.name, items)
    }

    // ————— LOAD (info → watch page) —————
    override suspend fun load(url: String): LoadResponse {
        delay(400)
        val doc = app.get(url).document

        val title = doc.selectFirst("h1.entry-title")?.text()?.trim() ?: "غير معروف"
        val poster = doc.selectFirst(".poster img")?.run {
            attr("data-src")?.toAbsolute() ?: attr("src")?.toAbsolute()
        }
        val plot = doc.select(".story p").text()?.takeIf { it.length > 10 }

        // Find watch link — prioritize watch button, fallback to green buttons
        val watchHref = doc.select("""
            a.btn.watch-btn,
            a.btn-success,
            a:contains(شاهد),
            a:contains(التشغيل)
        """).firstOrNull { it.attr("href").isNotBlank() }
            ?.attr("href")
            ?.toAbsolute()
            ?: throw ErrorLoadingException("رابط المشاهدة غير موجود")

        val isSeries = doc.select("div.seasnos-box, a[href*=/season/]").isNotEmpty()

        return if (isSeries) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, watchHref) {
                this.posterUrl = poster
                this.plot = plot
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, watchHref) {
                this.posterUrl = poster
                this.plot = plot
            }
        }
    }

    // ————— LOAD LINKS (extract real players & MP4s) —————
    override suspend fun loadLinks(
         String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        delay(100)
        val doc = app.get(data).document
        val sources = mutableSetOf<String>()

        // 1. Direct iframes
        sources += doc.select("iframe[src]")
            .map { it.attr("src").toAbsolute() }
            .filter { it.isNotBlank() && !it.contains("about:blank") }

        // 2. Script-extracted URLs (vadbam, streamwish, .mp4)
        val scriptText = doc.select("script").text()
        val urlRegex = Regex("""https?://[^\s"']+?(?:vadbam\.com|streamwish\.com|akwam\.so|s3\.3rabtv|cloudemb|downet\.net)[^\s"']*""")
        sources += urlRegex.findAll(scriptText).map { it.value.toAbsolute() }

        // 3. Direct MP4 download links
        sources += doc.select("a[href$=.mp4], a:contains(.mp4)")
            .map { it.attr("href").toAbsolute() }
            .filter { it.endsWith(".mp4") }

        // 4. data-player / data-src
        sources += doc.select("[data-player], [data-src], [data-embed]")
            .map { el ->
                el.attr("data-player").ifBlank {
                    el.attr("data-src").ifBlank {
                        el.attr("data-embed")
                    }
                }.toAbsolute()
            }
            .filter { it.isNotBlank() && !it.startsWith("javascript") }

        // 5. Fallback: try any href with player-like words
        sources += doc.select("a[href*=vadbam], a[href*=streamwish], a[href*=embed]")
            .map { it.attr("href").toAbsolute() }
            .filter { it.isNotBlank() }

        logD("Found candidate sources: $sources")

        var success = false
        for (src in sources.distinct()) {
            try {
                when {
                    src.endsWith(".mp4") -> {
                        callback.invoke(
                            ExtractorLink(
                                name = "Akwam MP4",
                                source = name,
                                url = src,
                                referer = data,
                                quality = Qualities.P720.value,
                                isM3u8 = false
                            )
                        )
                        success = true
                        logD("Added MP4 link: $src")
                    }
                    src.contains("vadbam") || src.contains("streamwish") || src.contains("cloudemb") -> {
                        loadExtractor(src, data, subtitleCallback, callback)
                        success = true
                        logD("Used extractor for: $src")
                    }
                    else -> {
                        // Generic fallback
                        loadExtractor(src, data, subtitleCallback, callback)
                        success = true
                        logD("Generic extractor for: $src")
                    }
                }
            } catch (e: Exception) {
                logE("Failed on $src", e)
            }
        }

        if (!success) {
            logE("No playable links found on: $data")
            throw ErrorLoadingException("فشل استخراج الروابط — تحقق من السجلات")
        }

        return success
    }
}
