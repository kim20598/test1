package com.akwam

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class AkwamProvider : MainAPI() {
    override var mainUrl = "https://ak.sv"
    override var name = "Akwam"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "ar"
    override val hasMainPage = true

    // =============================
    // 🔎 البحث
    // =============================
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?q=$query"
        val document = app.get(url).document
        return document.select("div.entry-box-1").mapNotNull { element ->
            toSearchResponse(element)
        }
    }

    private fun toSearchResponse(element: Element): SearchResponse? {
        val title = element.selectFirst("h3.entry-title a")?.text() ?: return null
        val href = fixUrl(element.selectFirst("a.box")?.attr("href") ?: return null)
        val poster = fixUrlNull(element.selectFirst("div.entry-image img")?.attr("src"))

        return MovieSearchResponse(
            title = title,
            url = href,
            apiName = this.name,
            type = TvType.Movie,
            posterUrl = poster
        )
    }

    // =============================
    // 📄 تحميل صفحة الفيلم
    // =============================
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1.entry-title")?.text() ?: return null
        val poster = document.selectFirst("div.movie-cover img")?.attr("src")
        val description = document.selectFirst(".widget-body .text-white")?.text()

        // استخراج السنة
        val year = document.selectFirst("div.font-size-16:matchesOwn(السنة)")?.text()
            ?.replace(Regex("[^0-9]"), "")?.toIntOrNull()

        // التصنيفات (Genres)
        val genres = document.select("a.badge-light").map { it.text() }

        // التقييم
        val rating = document.selectFirst("span.mx-2")?.text()?.replace("10 /", "")?.trim()

        return MovieLoadResponse(
            name = title,
            url = url,
            apiName = this.name,
            type = TvType.Movie,
            dataUrl = url,
            posterUrl = fixUrlNull(poster),
            plot = description,
            year = year,
        ).apply {
            this.rating = rating
            this.tags = genres
        }
    }
}
