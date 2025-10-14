package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup

class AkwamProvider : MainAPI() {
    override var mainUrl = "https://ak.sv"
    override var name = "Akwam"
    override val supportedTypes = setOf(TvType.Movie)
    override var lang = "ar"
    override val hasMainPage = false

    private suspend fun getDocument(url: String) = app.get(url).document

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search?q=$query"
        val doc = getDocument(searchUrl)
        val results = mutableListOf<SearchResponse>()

        doc.select("div.item a").forEach { element ->
            val href = fixUrl(element.attr("href"))
            val title = element.selectFirst("h3")?.text() ?: return@forEach
            val poster = element.selectFirst("img")?.attr("src")

            results.add(
                newMovieSearchResponse(title, href) {
                    this.posterUrl = poster
                }
            )
        }

        return results
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = getDocument(url)
        val title = doc.selectFirst("h1.entry-title")?.text() ?: "فيلم"
        val poster = doc.selectFirst("div.movie-cover img")?.attr("src")
        val description = doc.selectFirst("div.widget-body div.text-white")?.text()

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = getDocument(data)

        // 1️⃣ Find download page link
        val downloadPageUrl = doc.selectFirst("a[href*=\"/download/\"]")?.attr("href")
            ?: return false

        val downloadDoc = getDocument(downloadPageUrl)

        // 2️⃣ Find direct mp4 link
        val videoUrl = Regex("""https:\/\/s\d+\.downet\.net\/download\/[^\"]+\.mp4""")
            .find(downloadDoc.html())
            ?.value ?: return false

        // 3️⃣ Return clean link
        callback.invoke(
            newExtractorLink(
                this.name,               // source
                "Akwam",                 // name
                videoUrl,                // url
                mainUrl,                 // referer
                Qualities.P1080.value,   // quality
                ExtractorLinkType.VIDEO  // link type
            )
        )

        return true
    }
}
