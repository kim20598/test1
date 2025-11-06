package com.akwam

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toAbsolute

class Akwam : MainAPI() {
    override var mainUrl = "https://ak.sv"
    override var name = "Akwam"
    override val hasMainPage = true
    override var lang = "ar"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(mainUrl).document
        val movies = doc.select("div.Block--Item").mapNotNull {
            val title = it.selectFirst("h3 a")?.text() ?: return@mapNotNull null
            val href = it.selectFirst("h3 a")?.attr("href") ?: return@mapNotNull null
            val poster = it.selectFirst("img")?.attr("data-src")
            val type = if (href.contains("/series/")) TvType.TvSeries else TvType.Movie
            MovieSearchResponse(
                title,
                href.toAbsolute(mainUrl),
                this.name,
                type,
                poster,
                null
            )
        }
        return HomePageResponse(listOf(HomePageList("Latest", movies)))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?query=$query"
        val doc = app.get(url).document
        return doc.select("div.Block--Item").mapNotNull {
            val title = it.selectFirst("h3 a")?.text() ?: return@mapNotNull null
            val href = it.selectFirst("h3 a")?.attr("href") ?: return@mapNotNull null
            val poster = it.selectFirst("img")?.attr("data-src")
            val type = if (href.contains("/series/")) TvType.TvSeries else TvType.Movie
            MovieSearchResponse(
                title,
                href.toAbsolute(mainUrl),
                this.name,
                type,
                poster,
                null
            )
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1")?.text() ?: "Akwam"
        val poster = doc.selectFirst("div.Poster img")?.attr("data-src")
        val description = doc.selectFirst("div.Story p")?.text()
        return MovieLoadResponse(
            title,
            url,
            this.name,
            TvType.Movie,
            poster,
            description,
        )
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        // Step 1: Try to get watch/download button link
        val watchOrDownload = document.selectFirst("a.btn.watch, a.btn-download, a.watch-btn, a[href*=\"/download/\"]")
            ?.attr("href")
            ?.toAbsolute(mainUrl)

        if (watchOrDownload == null) return false

        // Step 2: Go to that page
        val subDoc = app.get(watchOrDownload, referer = data).document

        // Step 3: Find iframe or final direct link
        val iframeSrc = subDoc.selectFirst("iframe")?.attr("src")?.toAbsolute(mainUrl)

        if (iframeSrc != null) {
            if (iframeSrc.endsWith(".mp4")) {
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "Akwam Direct",
                        url = iframeSrc,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.quality = Qualities.P1080.value
                        this.referer = mainUrl
                    }
                )
                return true
            } else {
                val iframeDoc = app.get(iframeSrc, referer = watchOrDownload).document
                val videoTag = iframeDoc.selectFirst("video source")?.attr("src")
                if (!videoTag.isNullOrBlank()) {
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = "Akwam",
                            url = videoTag.toAbsolute(mainUrl),
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.quality = Qualities.P1080.value
                            this.referer = iframeSrc
                        }
                    )
                    return true
                }
            }
        }

        // Step 4: Fallback regex (for older Akwam structure)
        val mp4Regex = Regex("""https:\/\/s\d+\.downet\.net\/download\/[^\"]+\.mp4""")
        val videoUrl = mp4Regex.find(subDoc.html())?.value
        if (videoUrl != null) {
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "Akwam Mirror",
                    url = videoUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.quality = Qualities.P1080.value
                    this.referer = mainUrl
                }
            )
            return true
        }

        return false
    }
}
