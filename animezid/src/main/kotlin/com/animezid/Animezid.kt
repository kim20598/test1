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

    // ==================== LOAD LINKS - MEGAMAX SPECIFIC ====================

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

            // METHOD 1: Extract ALL server buttons and their embed URLs
            val serverMap = mutableMapOf<String, String>()
            
            // Get all server buttons and their data
            document.select("#xservers button, .server-btn, .server-button").forEach { serverButton ->
                val serverName = serverButton.text().trim().takeIf { it.isNotBlank() } ?: "Unknown Server"
                val embedUrl = serverButton.attr("data-embed").takeIf { it.isNotBlank() }
                    ?: serverButton.attr("data-src").takeIf { it.isNotBlank() }
                    ?: return@forEach

                // Skip if it's HTML or invalid
                if (embedUrl.startsWith("<") || embedUrl.contains("javascript")) return@forEach

                serverMap[serverName] = fixUrl(embedUrl)
            }

            // METHOD 2: Try each server in order
            for ((serverName, embedUrl) in serverMap) {
                try {
                    println("🔄 Trying server: $serverName with URL: $embedUrl")
                    
                    when {
                        // MEGAMAX HANDLING
                        embedUrl.contains("megamax", ignoreCase = true) -> {
                            foundLinks = handleMegamaxEmbed(embedUrl, episodeUrl, callback) || foundLinks
                        }
                        // VIDTUBE HANDLING  
                        embedUrl.contains("vidtube", ignoreCase = true) -> {
                            loadExtractor(embedUrl, episodeUrl, subtitleCallback, callback)
                            foundLinks = true
                        }
                        // ZID HANDLING
                        embedUrl.contains("zid", ignoreCase = true) -> {
                            loadExtractor(embedUrl, episodeUrl, subtitleCallback, callback)
                            foundLinks = true
                        }
                        // DEFAULT: Use loadExtractor for other hosts
                        else -> {
                            loadExtractor(embedUrl, episodeUrl, subtitleCallback, callback)
                            foundLinks = true
                        }
                    }
                } catch (e: Exception) {
                    println("❌ Failed to extract from $serverName: ${e.message}")
                    continue
                }
            }

            // METHOD 3: Direct iframe extraction as fallback
            if (!foundLinks) {
                document.select("iframe").forEach { iframe ->
                    val iframeSrc = iframe.attr("src").takeIf { it.isNotBlank() } ?: return@forEach
                    val fixedIframeUrl = fixUrl(iframeSrc)
                    
                    println("🔄 Trying iframe: $fixedIframeUrl")
                    
                    when {
                        fixedIframeUrl.contains("megamax", ignoreCase = true) -> {
                            foundLinks = handleMegamaxEmbed(fixedIframeUrl, episodeUrl, callback) || foundLinks
                        }
                        else -> {
                            loadExtractor(fixedIframeUrl, episodeUrl, subtitleCallback, callback)
                            foundLinks = true
                        }
                    }
                }
            }

            // METHOD 4: Look for direct video links
            if (!foundLinks) {
                document.select("video source").forEach { source ->
                    val videoUrl = source.attr("src").takeIf { it.isNotBlank() } ?: return@forEach
                    
                    callback(
                        newExtractorLink(
                            source = name,
                            name = "Animezid Direct Video",
                            url = fixUrl(videoUrl)
                        ) {
                            this.referer = episodeUrl
                            this.quality = getQualityFromName(source.attr("data-quality") ?: "720p")
                            this.type = ExtractorLinkType.VIDEO
                        }
                    )
                    foundLinks = true
                }
            }

            foundLinks
        }.getOrElse { 
            false 
        }
    }

    // ==================== MEGAMAX SPECIFIC HANDLER ====================

    private suspend fun handleMegamaxEmbed(embedUrl: String, referer: String, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            println("🔍 Processing Megamax embed: $embedUrl")
            
            // Extract the embed code from the URL
            val embedCode = when {
                embedUrl.contains("/iframe/") -> {
                    embedUrl.substringAfter("/iframe/").substringBefore("?")
                }
                embedUrl.contains("src=") -> {
                    embedUrl.substringAfter("src=").substringBefore("&")
                }
                else -> {
                    embedUrl.substringAfterLast("/")
                }
            }.takeIf { it.isNotBlank() } ?: return false

            println("📦 Megamax embed code: $embedCode")

            // Construct the direct megamax URL
            val directUrl = "https://megamax.me/embed-$embedCode.html"
            
            // Try to extract from the direct URL
            val response = app.get(directUrl, referer = embedUrl)
            val doc = response.document

            // Look for video sources in Megamax player
            val videoSource = doc.selectFirst("video source")?.attr("src")
            val m3u8Url = doc.selectFirst("source[src*=.m3u8]")?.attr("src")
            val mp4Url = doc.selectFirst("source[src*=.mp4]")?.attr("src")

            when {
                videoSource != null -> {
                    callback(
                        newExtractorLink(
                            source = name,
                            name = "Megamax Direct",
                            url = fixUrl(videoSource)
                        ) {
                            this.referer = directUrl
                            this.quality = getQualityFromName("1080p")
                            this.type = ExtractorLinkType.VIDEO
                        }
                    )
                    true
                }
                m3u8Url != null -> {
                    callback(
                        newExtractorLink(
                            source = name,
                            name = "Megamax HLS",
                            url = fixUrl(m3u8Url)
                        ) {
                            this.referer = directUrl
                            this.quality = getQualityFromName("1080p")
                            this.type = ExtractorLinkType.HLS
                        }
                    )
                    true
                }
                mp4Url != null -> {
                    callback(
                        newExtractorLink(
                            source = name,
                            name = "Megamax MP4",
                            url = fixUrl(mp4Url)
                        ) {
                            this.referer = directUrl
                            this.quality = getQualityFromName("1080p")
                            this.type = ExtractorLinkType.VIDEO
                        }
                    )
                    true
                }
                else -> {
                    // Fallback to regular extractor
                    loadExtractor(directUrl, referer) { link ->
                        callback(link)
                    }
                    true
                }
            }
        } catch (e: Exception) {
            println("❌ Megamax extraction failed: ${e.message}")
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
