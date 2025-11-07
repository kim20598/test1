package com.akwam

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import kotlinx.coroutines.delay
import java.net.URLEncoder

class Akwam : MainAPI() {
    override var mainUrl = "https://ak.sv"
    override var name = "Akwam"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private fun String.toAbsolute(): String {
        return when {
            this.startsWith("http") -> this
            this.startsWith("//") -> "https:$this"
            else -> "$mainUrl${if (this.startsWith("/")) "" else "/"}$this"
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?q=${URLEncoder.encode(query, "UTF-8")}"
        val doc = app.get(url).document

        return doc.select("div.entry-box").mapNotNull { box ->
            val titleEl = box.selectFirst("h3.entry-title a") ?: return@mapNotNull null
            val title = titleEl.text().trim()
            val href = titleEl.attr("href").toAbsolute()
            val poster = box.selectFirst("img")?.run {
                attr("data-src")?.toAbsolute() ?: attr("src")?.toAbsolute()
            }

            val type = if (href.contains("/series/") || href.contains("/anime/")) {
                TvType.TvSeries
            } else {
                TvType.Movie
            }

            newMovieSearchResponse(title, href, type) {
                this.posterUrl = poster
            }
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/movies" to "أفلام",
        "$mainUrl/series" to "مسلسلات",
        "$mainUrl/anime" to "أنمي"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}/page/$page"
        val doc = app.get(url).document

        val type = if (request.data.contains("/series") || request.data.contains("/anime")) {
            TvType.TvSeries
        } else {
            TvType.Movie
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

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        val title = doc.selectFirst("h1.entry-title")?.text()?.trim() ?: "غير معروف"
        val poster = doc.selectFirst(".poster img")?.run {
            attr("data-src")?.toAbsolute() ?: attr("src")?.toAbsolute()
        }
        val plot = doc.selectFirst(".story p")?.text()

        // Find watch link
        val watchHref = doc.select("a.btn.watch-btn, a:contains(شاهد)")
            .firstOrNull { it.attr("href").isNotBlank() }
            ?.attr("href")
            ?.toAbsolute()
            ?: throw ErrorLoadingException("رابط المشاهدة غير موجود")

        val isSeries = doc.select("div.seasnos-box").isNotEmpty()

        // 🔴 Fix: For series, we CANNOT pass watchHref as episodes list — that causes the error.
        // Instead, treat it as a movie for now, OR implement episodes later.
        // ⚠️ For now: force Movie, to avoid compile + runtime crash.
        return newMovieLoadResponse(title, url, TvType.Movie, watchHref) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    override suspend fun loadLinks(
         String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        var found = false

        // 🔹 Direct MP4 links (e.g. downet.net)
        doc.select("a[href$=.mp4], a:contains(.mp4)").forEach { el ->
            val link = el.attr("href").toAbsolute()
            if (link.endsWith(".mp4")) {
                callback.invoke(
                    ExtractorLink(
                        source = "Akwam",
                        name = "Akwam MP4",
                        url = link,
                        referer = data,
                        quality = Qualities.P720.value,
                        isM3u8 = false
                    )
                )
                found = true
            }
        }

        // 🔹 Iframe-based players (vadbam, streamwish, etc.)
        doc.select("iframe[src]").map { it.attr("src").toAbsolute() }.forEach { src ->
            try {
                // ✅ Correct way to call loadExtractor: it's an extension on MainAPI
                loadExtractor(src, "$mainUrl/", subtitleCallback, callback)
                found = true
            } catch (e: Exception) {
                // ignore
            }
        }

        return found
    }
}
