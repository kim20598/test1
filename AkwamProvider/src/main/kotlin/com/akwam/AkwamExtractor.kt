package com.akwam

import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup

object AkwamExtractor {
    suspend fun extractLinks(
        url: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val doc = app.get(url).document
            val links = doc.select("a.download-link")

            for (a in links) {
                val link = a.attr("href")
                val quality = Qualities.Unknown.value
                callback(
                    newExtractorLink(
                        source = "Akwam",
                        name = "Akwam Server",
                        url = link,
                        type = ExtractorLinkType.Direct
                    ) {
                        this.quality = quality
                    }
                )
            }
            return true
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false
    }
}
