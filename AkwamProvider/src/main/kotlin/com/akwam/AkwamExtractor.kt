package com.akwam

import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.*
import org.jsoup.Jsoup
import com.lagradost.cloudstream3.utils.ExtractorLinkType


object AkwamExtractor : ExtractorApi() {
    override val name = "Akwam"
    override val mainUrl = "https://akwam.to"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink> {
        val links = mutableListOf<ExtractorLink>()

        val doc = app.get(url).document
        val elements = doc.select("a.download-btn")

        for (el in elements) {
            val linkUrl = el.attr("href")
            val quality = el.text().trim()
            links.add(
                ExtractorLink(
                    name,
                    name,
                    linkUrl,
                    mainUrl,
                    getQualityFromName(quality),
                    type = ExtractorLink.Type.DIRECT
                )
            )
        }

        return links
    }
}
