package com.example.akwam

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.network.CloudflareKiller

class AkwamProvider : MainAPI() {
    override var mainUrl = "https://ak.sv"
    override var name = "Akwam"
    override val supportedTypes = setOf(TvType.Movie)
    override var lang = "ar"
    override val hasMainPage = false

    private val cfKiller = CloudflareKiller()

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search?q=$query"
        val doc = cfKiller.getDocument(searchUrl)
        val items = doc.select("div.item a")

        return items.mapNotNull {
            val href = it.attr("href")
            val title = it.selectFirst("h3")?.text() ?: return@mapNotNull null
            val poster = it.selectFirst("img")?.attr("src")
            MovieSearchResponse(
                name = title,
                url = href,
                apiName = this.name,
                type = TvType.Movie,
                posterUrl = poster
            )
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = cfKiller.getDocument(url)
        val title = doc.selectFirst("h1.entry-title")?.text() ?: "فيلم"
        val poster = doc.selectFirst("div.movie-cover img")?.attr("src")
        val description = doc.selectFirst("div.widget-body div.text-white")?.text()

        return MovieLoadResponse(
            name = title,
            url = url,
            apiName = this.name,
            type = TvType.Movie,
            dataUrl = url,
            posterUrl = poster,
            plot = description
        )
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Step 1: Go to the movie page
        val doc = cfKiller.getDocument(data)
        val watchLink = doc.selectFirst("a[href*=\"/download/\"]")?.attr("href")

        if (watchLink != null) {
            // Step 2: Go to download page
            val downloadPage = cfKiller.getDocument(watchLink)

            // Step 3: Find the actual .mp4 file link
            val videoUrl = Regex("""https:\/\/s\d+\.downet\.net\/download\/[^\"]+\.mp4""")
                .find(downloadPage.html())
                ?.value

            if (videoUrl != null) {
                callback.invoke(
                    ExtractorLink(
                        source = this.name,
                        name = "Akwam",
                        url = videoUrl,
                        referer = mainUrl,
                        quality = Qualities.P1080.value,
                        isM3u8 = false
                    )
                )
                return true
            }
        }
        return false
    }
}
