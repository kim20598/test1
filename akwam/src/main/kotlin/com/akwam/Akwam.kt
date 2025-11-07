package com.akwam

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import kotlinx.serialization.Serializable

class Akwam : MainAPI() {
    override var mainUrl = "https://ak.sv"  // ← NO TRAILING SPACE!
    override var name = "Akwam"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private fun String.toAbsolute(): String {
        if (this.isBlank()) return ""
        return when {
            this.startsWith("http") -> this
            this.startsWith("//") -> "https:$this"
            else -> mainUrl.trimEnd('/') + (if (this.startsWith("/")) this else "/$this")
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?q=${query.trim().replace(" ", "+")}"
        val document = app.get(url).document
        return document.select("div.entry-box").amap {
            val a = it.selectFirst("h3.entry-title a") ?: return@amap null
            val href = a.attr("href").toAbsolute()
            val title = a.text().trim() ?: return@amap null
            val posterUrl = it.selectFirst("img")?.let { img ->
                (img.attr("data-src").ifBlank { img.attr("src") }).toAbsolute()
            }
            val isMovie = href.contains("/movie/")
            val tvType = if (isMovie) TvType.Movie else TvType.TvSeries

            newMovieSearchResponse(title, href, tvType) {
                this.posterUrl = posterUrl
            }
        }.filterNotNull()
    }

    override val mainPage = mainPageOf(
        "$mainUrl/movies" to "أفلام",
        "$mainUrl/series" to "مسلسلات",
        "$mainUrl/anime" to "أنمي",
        "$mainUrl/tv-shows" to "عروض تلفزيونية",
        "$mainUrl/games" to "ألعاب",
        "$mainUrl/apps" to "تطبيقات"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}/page/$page"
        val document = app.get(url).document

        val type = when {
            request.data.contains("/series") || request.data.contains("/anime") -> TvType.TvSeries
            else -> TvType.Movie
        }

        val items = document.select("div.entry-box").amap {
            val title = it.selectFirst("h3.entry-title")?.text()?.trim() ?: return@amap null
            val href = it.selectFirst("h3.entry-title a")?.attr("href")?.toAbsolute() ?: return@amap null
            val posterUrl = it.selectFirst("img")?.let { img ->
                (img.attr("data-src").ifBlank { img.attr("src") }).toAbsolute()
            }
            newMovieSearchResponse(title, href, type) {
                this.posterUrl = posterUrl
            }
        }.filterNotNull()

        return newHomePageResponse(request.name, items)
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1.entry-title")?.text()?.trim() ?: "غير معروف"
        val poster = doc.selectFirst(".poster img")?.let { img ->
            (img.attr("data-src").ifBlank { img.attr("src") }).toAbsolute()
        }
        val plot = doc.selectFirst(".story p")?.text()?.trim()

        // Episodes (for series)
        val episodes = mutableListOf<Episode>()

        // Akwam: seasons are in div.seasnos-box > ul > li > a
        doc.select("div.seasnos-box ul li a").forEach { seasonLink ->
            val seasonHref = seasonLink.attr("href").toAbsolute()
            val seasonDoc = app.get(seasonHref).document

            seasonDoc.select("div.episodes > ul li a").forEach { epEl ->
                val epHref = epEl.attr("href").toAbsolute()
                val epText = epEl.selectFirst(".name")?.text()?.trim()
                    ?: epEl.selectFirst(".episodes-num")?.text()?.trim()
                    ?: epEl.text().trim()
                val epNum = Regex("""\d+""").find(epText)?.value?.toIntOrNull()

                episodes.add(newEpisode(epHref) {
                    name = epText
                    episode = epNum
                    posterUrl = poster
                })
            }
        }

        val isSeries = episodes.isNotEmpty() || doc.select("div.seasnos-box").isNotEmpty()

        return if (isSeries) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
            }
        } else {
            // Movie: return watch page as data
            val watchUrl = doc.select("a.btn.watch-btn, a:contains(شاهد)")
                .firstOrNull { it.attr("href").isNotBlank() }
                ?.attr("href")
                ?.toAbsolute()
                ?: url  // fallback

            newMovieLoadResponse(title, url, TvType.Movie, watchUrl) {
                this.posterUrl = poster
                this.plot = plot
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.i(name, "loadLinks started for: $data")

        val doc = app.get(data).document

        // 🔹 STEP 1: Try direct iframe
        val iframeSrc = doc.selectFirst("iframe[src]")?.attr("src")?.toAbsolute()
        if (!iframeSrc.isNullOrBlank()) {
            Log.d(name, "Direct iframe found: $iframeSrc")
            try {
                loadExtractor(iframeSrc, data, subtitleCallback, callback)
                return true
            } catch (e: Exception) {
                Log.e(name, "Direct iframe extraction failed", e)
            }
        }

        // 🔹 STEP 2: Try script-extracted URLs
        val scriptText = doc.select("script").html()
        val urlRegex = Regex("""https?://[^\s"']+?(?:vadbam|streamwish|akwam\.so|s3\.3rabtv|cloudemb|downet\.net)[^\s"']*""")
        val matches = urlRegex.findAll(scriptText).map { it.value.toAbsolute() }.toList()

        Log.i(name, "Found ${matches.size} candidate URLs from scripts")

        var found = false
        for (src in matches.distinct()) {
            try {
                Log.d(name, "Trying extractor for: $src")
                if (src.endsWith(".mp4")) {
                    callback(
                        ExtractorLink(
                            source = name,
                            name = "MP4",
                            url = src,
                            referer = data,
                            quality = Qualities.P720.value,
                            isM3u8 = false
                        )
                    )
                    found = true
                } else {
                    loadExtractor(src, data, subtitleCallback, callback)
                    found = true
                }
            } catch (e: Exception) {
                Log.e(name, "Failed on $src", e)
            }
        }

        return found
    }
}
