package com.animezid

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.AppUtils
import org.jsoup.nodes.Element
import java.net.URLEncoder

class Animezid : MainAPI() {
    override var lang = "ar"
    override var mainUrl = "https://animezid.cam"
    override var name = "Animezid"
    override val usesWebView = false
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Anime, TvType.Movie)

    // ==================== MAIN PAGE ====================
    
    override val mainPage = mainPageOf(
        "$mainUrl/" to "أحدث الإضافات",
        "$mainUrl/anime/" to "أنمي",
        "$mainUrl/movies/" to "أفلام أنمي",
        "$mainUrl/ongoing/" to "مسلسلات مستمرة",
        "$mainUrl/completed/" to "مسلسلات مكتملة"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document
        
        val items = document.select("a.movie").mapNotNull { it.toRealSearchResponse() }

        return newHomePageResponse(request.name, items, hasNext = false)
    }

    // ==================== SEARCH ====================

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search.php?keywords=${URLEncoder.encode(query, "UTF-8")}"
        val document = app.get(searchUrl).document
        
        return document.select("a.movie").mapNotNull { it.toRealSearchResponse() }
    }

    // ==================== LOAD ====================

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        
        val title = document.selectFirst("h1 span[itemprop=name]")?.text() 
            ?: document.selectFirst("h1")?.text() 
            ?: throw ErrorLoadingException("Unable to extract title")

        val poster = document.selectFirst("img.lazy")?.attr("data-src")?.let { 
            fixUrl(it)
        } ?: ""

        val description = document.selectFirst(".pm-video-description")?.text()?.trim() ?: ""

        val year = document.selectFirst("a[href*='filter=years']")?.text()?.toIntOrNull()

        val hasSeasons = document.select(".tab-seasons li").isNotEmpty()
        val hasEpisodes = document.select(".SeasonsEpisodes a").isNotEmpty()
        val isMovie = !hasSeasons && !hasEpisodes

        return if (isMovie) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
            }
        } else {
            val episodes = extractRealEpisodesFromSeasons(document, url)
            
            newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
            }
        }
    }

    // ==================== LOAD LINKS - COMPLETELY REWRITTEN ====================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return kotlin.runCatching {
            val episodeUrl = data
            val document = app.get(episodeUrl).document
            var foundLinks = false

            // METHOD 1: Extract from server buttons with proper handling
            val serverButtons = document.select("#xservers button, .server-btn, .server-button")
            
            for (serverButton in serverButtons) {
                val embedUrl = serverButton.attr("data-embed").takeIf { it.isNotBlank() }
                    ?: serverButton.attr("data-src").takeIf { it.isNotBlank() }
                    ?: continue

                // Skip if it's HTML or invalid
                if (embedUrl.startsWith("<") || embedUrl.contains("javascript")) continue

                val fixedEmbedUrl = fixUrl(embedUrl)
                
                // Add proper headers to bypass ads
                val headers = mapOf(
                    "Referer" to episodeUrl,
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                )

                try {
                    // Try to get the final URL after redirects
                    val response = app.get(fixedEmbedUrl, headers = headers, allowRedirects = true)
                    val finalUrl = response.url
                    
                    // If we got a different URL, use it for extraction
                    if (finalUrl != fixedEmbedUrl && finalUrl.contains("http")) {
                        loadExtractor(finalUrl, episodeUrl, subtitleCallback, callback)
                        foundLinks = true
                    } else {
                        // Otherwise try to extract from the response document
                        val embedDoc = response.document
                        
                        // Look for video sources in the embed
                        val videoSource = embedDoc.selectFirst("video source")?.attr("src")
                        val iframeSrc = embedDoc.selectFirst("iframe")?.attr("src")
                        
                        when {
                            videoSource != null -> {
                                callback(
                                    newExtractorLink(
                                        source = name,
                                        name = "Animezid Direct",
                                        url = fixUrl(videoSource),
                                        referer = finalUrl,
                                        quality = getQualityFromName("1080p"),
                                        type = ExtractorLinkType.VIDEO
                                    )
                                )
                                foundLinks = true
                            }
                            iframeSrc != null -> {
                                loadExtractor(fixUrl(iframeSrc), episodeUrl, subtitleCallback, callback)
                                foundLinks = true
                            }
                            else -> {
                                // Fallback to regular extractor
                                loadExtractor(finalUrl, episodeUrl, subtitleCallback, callback)
                                foundLinks = true
                            }
                        }
                    }
                } catch (e: Exception) {
                    // If direct extraction fails, try the original URL
                    loadExtractor(fixedEmbedUrl, episodeUrl, subtitleCallback, callback)
                    foundLinks = true
                }
            }

            // METHOD 2: Try direct video player iframe
            if (!foundLinks) {
                document.select("#Playerholder iframe, .player-iframe, iframe[src*='embed']").forEach { iframe ->
                    val iframeSrc = iframe.attr("src").takeIf { it.isNotBlank() } ?: return@forEach
                    
                    val fixedIframeUrl = fixUrl(iframeSrc)
                    loadExtractor(fixedIframeUrl, episodeUrl, subtitleCallback, callback)
                    foundLinks = true
                }
            }

            // METHOD 3: Look for direct video links in the page
            if (!foundLinks) {
                document.select("video source").forEach { source ->
                    val videoUrl = source.attr("src").takeIf { it.isNotBlank() } ?: return@forEach
                    
                    callback(
                        newExtractorLink(
                            source = name,
                            name = "Animezid Direct Video",
                            url = fixUrl(videoUrl),
                            referer = episodeUrl,
                            quality = getQualityFromName(source.attr("data-quality") ?: "720p"),
                            type = ExtractorLinkType.VIDEO
                        )
                    )
                    foundLinks = true
                }
            }

            // METHOD 4: Try download links as last resort
            if (!foundLinks) {
                document.select("a.dl.show_dl.api[target='_blank'], a[href*='download']").forEach { downloadLink ->
                    val downloadUrl = downloadLink.attr("href").takeIf { it.isNotBlank() } ?: return@forEach
                    
                    callback(
                        newExtractorLink(
                            source = name,
                            name = "Animezid Download - ${downloadLink.text().trim()}",
                            url = fixUrl(downloadUrl),
                            referer = episodeUrl,
                            quality = getQualityFromName(downloadLink.text()) ?: getQualityFromName("1080p"),
                            type = ExtractorLinkType.VIDEO
                        )
                    )
                    foundLinks = true
                }
            }

            foundLinks
        }.getOrElse { 
            false 
        }
    }

    // ==================== HELPER FUNCTIONS ====================

    private fun extractRealEpisodesFromSeasons(document: org.jsoup.nodes.Document, baseUrl: String): List<Episode> {
        return document.select(".SeasonsEpisodes a, .episode-item a").mapNotNull { episodeElement ->
            val episodeUrl = fixUrl(episodeElement.attr("href"))
            val episodeNumberText = episodeElement.select("em, .episode-num").text().trim()
            val episodeNumber = episodeNumberText.toIntOrNull() ?: return@mapNotNull null
            val episodeTitle = episodeElement.select("span, .episode-title").text().trim()
            
            val name = if (episodeTitle.isNotBlank() && episodeTitle != "الحلقة") {
                episodeTitle
            } else {
                "الحلقة $episodeNumberText"
            }
            
            newEpisode(episodeUrl) {
                this.name = name
                this.episode = episodeNumber
            }
        }.distinctBy { it.episode }
    }

    private fun Element.toRealSearchResponse(): SearchResponse? {
        val title = this.attr("title").takeIf { it.isNotBlank() } 
            ?: this.select(".title, .movie-title").text().takeIf { it.isNotBlank() }
            ?: return null
            
        val href = this.attr("href").takeIf { it.isNotBlank() } ?: return null
        val poster = this.select("img.lazy, img[data-src]").attr("data-src")
        
        val isMovie = title.contains("فيلم") || href.contains("/movie/") || this.select(".movie-type").text().contains("فيلم")

        return if (isMovie) {
            newMovieSearchResponse(title, fixUrl(href), TvType.Movie) {
                this.posterUrl = fixUrl(poster)
            }
        } else {
            newTvSeriesSearchResponse(title, fixUrl(href), TvType.Anime) {
                this.posterUrl = fixUrl(poster)
            }
        }
    }

    private fun fixUrl(url: String): String {
        return when {
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$mainUrl$url"
            else -> "$mainUrl/$url"
        }
    }
}
