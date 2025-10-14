package com.example.akwam

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.mvvm.safeApiCall
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup

class AkwamProvider : MainAPI() {
    override var mainUrl = "https://ak.sv"
    override var name = "Akwam"
    override val supportedTypes = setOf(TvType.Movie)
    override var lang = "ar"
    override val hasMainPage = false

    // Basic HTTP helper
    private suspend fun fetchDocument(url: String) =
        app.get(url).document

    override suspend fun search(query: String): List<SearchResponse> = safeApiCall {
        val searchUrl = "$mainUrl/search?q=$query"
        val doc = fetchDocument(searchUrl)
        val items = doc.select("div.item a")

        items.mapNotNull { element ->
            val href = element.attr("href")
            val title = element.selectFirst("h3")?.text() ?: return@mapNotNull null
            val poster = element.selectFirst("img")?.attr("src")

            newMovieSearchResponse(title, href) {
                this.posterUrl = poster
            }
        }
    } ?: emptyList()

    override suspend fun load(url: String): LoadResponse {
        val doc = fetchDocument(url)
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
        val doc = fetchDocument(data)

        // 1️⃣ Find the download page link
        val downloadLink = doc.selectFirst("a[href*=\"/download/\"]")?.attr("href")
            ?: return false

        // 2️⃣ Fetch the download page
        val downloadPage = fetchDocument(downloadLink)

        // 3️⃣ Find direct .mp4 URL
        val videoUrl = Regex("""https:\/\/s\d+\.downet\.net\/download\/[^\"]+\.mp4""")
            .find(downloadPage.html())
            ?.value ?: return false

        // 4️⃣ Return as ExtractorLink
        callback.invoke(
            newExtractorLink(
                this.name,
                "Akwam",
                videoUrl,
                mainUrl,
                Qualities.P1080,
                isM3u8 = false
            )
        )

        return true
    }
}
