package com.cineby

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import java.net.URLEncoder

class Cineby : MainAPI() {
    override var mainUrl = "https://www.cineby.gd"
    override var name = "Cineby"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    // ==================== DATA CLASSES ====================
    
    data class HomePageProps(
        val pageProps: PageProps
    )
    
    data class PageProps(
        val initialData: InitialData
    )
    
    data class InitialData(
        val page: String,
        val data: PageData
    )
    
    data class PageData(
        val providers: List<Provider>? = null,
        val trending: List<MediaItem>? = null,
        val popular: List<MediaItem>? = null,
        val topRated: List<MediaItem>? = null,
        val results: List<MediaItem>? = null,
        val media: MediaDetails? = null
    )
    
    data class Provider(
        val name: String,
        val movies: List<MediaItem>
    )
    
    data class MediaItem(
        val id: Int,
        val title: String,
        val poster: String,
        val slug: String,
        val image: String? = null,
        val description: String? = null,
        val genre_ids: List<Int>? = null,
        val original_language: String? = null,
        val rating: Double? = null,
        val release_date: String? = null,
        val mediaType: String
    )

    data class MediaDetails(
        val id: Int,
        val title: String? = null,
        val name: String? = null,
        val poster_path: String? = null,
        val backdrop_path: String? = null,
        val overview: String? = null,
        val release_date: String? = null,
        val first_air_date: String? = null,
        val genres: List<Genre>? = null,
        val seasons: List<Season>? = null,
        val runtime: Int? = null,
        val episode_run_time: List<Int>? = null,
        val vote_average: Double? = null
    )

    data class Genre(
        val id: Int,
        val name: String
    )

    data class Season(
        val id: Int,
        val season_number: Int,
        val name: String,
        val episode_count: Int,
        val air_date: String? = null
    )

    data class EpisodesResponse(
        val episodes: List<Episode>
    )

    data class Episode(
        val id: Int,
        val name: String,
        val episode_number: Int,
        val season_number: Int,
        val air_date: String? = null,
        val overview: String? = null
    )

    // ==================== MAIN PAGE ====================
    
    override val mainPage = mainPageOf(
        "trending" to "Trending Today",
        "popular" to "Popular",
        "topRated" to "Top Rated",
        "netflix" to "Netflix",
        "disney" to "Disney+",
        "hbo" to "HBO",
        "appletv" to "Apple TV+",
        "paramount" to "Paramount+"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = when (request.data) {
            // For provider categories
            "netflix", "disney", "hbo", "appletv", "paramount" -> {
                // Get the page data (this would be from the API endpoint)
                val response = app.get("$mainUrl/api/home").text
                val data = parseJson<HomePageProps>(response)
                
                // Find the specific provider
                data.pageProps.initialData.data.providers
                    ?.find { it.name == request.data }
                    ?.movies
                    ?.map { it.toSearchResponse() }
                    ?: emptyList()
            }
            // For other categories
            else -> {
                val response = app.get("$mainUrl/api/${request.data}?page=$page").text
                val data = parseJson<PageData>(response)
                
                when (request.data) {
                    "trending" -> data.trending
                    "popular" -> data.popular
                    "topRated" -> data.topRated
                    else -> data.results
                }?.map { it.toSearchResponse() } ?: emptyList()
            }
        }
        
        return newHomePageResponse(request.name, items, hasNext = items.size >= 20)
    }

    // ==================== SEARCH ====================

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/api/search?q=${URLEncoder.encode(query, "UTF-8")}"
        val response = app.get(searchUrl).text
        val data = parseJson<PageData>(response)
        
        return data.results?.map { it.toSearchResponse() } ?: emptyList()
    }

    // ==================== LOAD ====================

    override suspend fun load(url: String): LoadResponse {
        // Extract ID and type from URL
        val mediaType = when {
            url.contains("/tv/") -> "tv"
            url.contains("/movie/") -> "movie"
            else -> "movie"
        }
        val mediaId = url.substringAfterLast("/").substringBefore("?")
        
        // Get media details
        val response = app.get("$mainUrl/api/$mediaType/$mediaId").text
        val data = parseJson<PageData>(response)
        val media = data.media ?: throw ErrorLoadingException("Media not found")
        
        val title = media.title ?: media.name ?: "Unknown"
        val posterUrl = media.poster_path?.let { 
            if (it.startsWith("http")) it else "https://image.tmdb.org/t/p/w500$it" 
        } ?: ""
        val backdropUrl = media.backdrop_path?.let { 
            if (it.startsWith("http")) it else "https://image.tmdb.org/t/p/original$it" 
        } ?: ""
        
        return if (mediaType == "tv" && media.seasons != null) {
            val episodes = mutableListOf<com.lagradost.cloudstream3.Episode>()
            
            // For each season, fetch episodes
            media.seasons.forEach { season ->
                if (season.season_number == 0) return@forEach // Skip specials
                
                try {
                    val episodesUrl = "$mainUrl/api/tv/$mediaId/season/${season.season_number}"
                    val episodesResponse = app.get(episodesUrl).parsedSafe<EpisodesResponse>()
                    
                    episodesResponse?.episodes?.forEach { ep ->
                        episodes.add(
                            newEpisode("$mainUrl/tv/$mediaId/${season.season_number}/${ep.episode_number}") {
                                this.name = ep.name
                                this.season = season.season_number
                                this.episode = ep.episode_number
                                this.description = ep.overview
                            }
                        )
                    }
                } catch (e: Exception) {
                    // If API fails, create placeholder episodes
                    for (i in 1..season.episode_count) {
                        episodes.add(
                            newEpisode("$mainUrl/tv/$mediaId/${season.season_number}/$i") {
                                this.name = "Episode $i"
                                this.season = season.season_number
                                this.episode = i
                            }
                        )
                    }
                }
            }
            
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl
                this.backgroundPosterUrl = backdropUrl
                this.plot = media.overview
                this.tags = media.genres?.map { it.name }
                this.year = (media.first_air_date ?: media.release_date)?.take(4)?.toIntOrNull()
                media.vote_average?.let { this.rating = (it * 1000).toInt() }
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = posterUrl
                this.backgroundPosterUrl = backdropUrl
                this.plot = media.overview
                this.tags = media.genres?.map { it.name }
                this.year = media.release_date?.take(4)?.toIntOrNull()
                this.duration = media.runtime
                media.vote_average?.let { this.rating = (it * 1000).toInt() }
            }
        }
    }

    // ==================== LOAD LINKS ====================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Extract media info from URL
        val mediaType = when {
            data.contains("/tv/") -> "tv"
            else -> "movie"
        }
        
        val parts = data.split("/")
        val mediaId = when (mediaType) {
            "tv" -> parts[parts.indexOf("tv") + 1]
            else -> parts[parts.indexOf("movie") + 1]
        }
        
        val season = if (mediaType == "tv" && parts.size > parts.indexOf("tv") + 2) {
            parts[parts.indexOf("tv") + 2]
        } else null
        
        val episode = if (mediaType == "tv" && parts.size > parts.indexOf("tv") + 3) {
            parts[parts.indexOf("tv") + 3]
        } else null
        
        // Build the player/embed URL
        val embedUrl = if (mediaType == "tv" && season != null && episode != null) {
            "$mainUrl/embed/tv/$mediaId/$season/$episode"
        } else {
            "$mainUrl/embed/movie/$mediaId"
        }
        
        // Get the embed page
        val embedDoc = app.get(embedUrl).document
        var foundLinks = false
        
        // Method 1: Look for iframe embeds
        embedDoc.select("iframe").forEach { iframe ->
            val iframeSrc = iframe.attr("src")
            if (iframeSrc.isNotBlank() && !iframeSrc.contains("youtube")) {
                foundLinks = true
                loadExtractor(fixUrl(iframeSrc), embedUrl, subtitleCallback, callback)
            }
        }
        
        // Method 2: Look for API endpoints in scripts
        embedDoc.select("script").forEach { script ->
            val content = script.html()
            
            // Look for API calls
            if (content.contains("/api/source/") || content.contains("/api/embed/")) {
                val sourcePattern = Regex("""['"](/api/(?:source|embed)/[^'"]+)['"]""")
                sourcePattern.findAll(content).forEach { match ->
                    val apiUrl = fixUrl(match.groupValues[1])
                    try {
                        val apiResponse = app.get(apiUrl).parsedSafe<SourceResponse>()
                        apiResponse?.sources?.forEach { source ->
                            foundLinks = true
                            callback(
                                ExtractorLink(
                                    source = name,
                                    name = source.quality ?: name,
                                    url = source.url,
                                    referer = embedUrl,
                                    quality = getQualityFromString(source.quality ?: ""),
                                    isM3u8 = source.url.contains(".m3u8")
                                )
                            )
                        }
                        
                        // Handle subtitles
                        apiResponse?.subtitles?.forEach { subtitle ->
                            subtitleCallback(
                                SubtitleFile(
                                    lang = subtitle.lang ?: "Unknown",
                                    url = fixUrl(subtitle.url)
                                )
                            )
                        }
                    } catch (e: Exception) {
                        // Continue
                    }
                }
            }
        }
        
        // Method 3: Direct server calls
        if (!foundLinks) {
            val serversUrl = "$mainUrl/api/servers/$mediaType/$mediaId"
            try {
                val serversResponse = app.get(serversUrl).parsedSafe<ServersResponse>()
                serversResponse?.servers?.forEach { server ->
                    val serverUrl = "$mainUrl/api/source/${server.id}"
                    try {
                        val sourceResponse = app.get(serverUrl).parsedSafe<SourceResponse>()
                        sourceResponse?.sources?.forEach { source ->
                            foundLinks = true
                            callback(
                                ExtractorLink(
                                    source = name,
                                    name = "${server.name} - ${source.quality}",
                                    url = source.url,
                                    referer = mainUrl,
                                    quality = getQualityFromString(source.quality ?: ""),
                                    isM3u8 = source.url.contains(".m3u8")
                                )
                            )
                        }
                    } catch (e: Exception) {
                        // Try external extractor
                        if (server.embed?.isNotBlank() == true) {
                            foundLinks = true
                            loadExtractor(server.embed, mainUrl, subtitleCallback, callback)
                        }
                    }
                }
            } catch (e: Exception) {
                // Continue
            }
        }
        
        return foundLinks
    }

    // ==================== HELPER FUNCTIONS ====================

    private fun MediaItem.toSearchResponse(): SearchResponse {
        val url = when (mediaType) {
            "tv" -> "$mainUrl/tv/$id"
            else -> "$mainUrl/movie/$id"
        }
        
        val type = when (mediaType) {
            "tv" -> TvType.TvSeries
            else -> TvType.Movie
        }
        
        return newMovieSearchResponse(title, url, type) {
            this.posterUrl = if (poster.startsWith("http")) poster 
                           else "https://image.tmdb.org/t/p/w342$poster"
            rating?.let { this.quality = SearchQuality.HD }
        }
    }

    private fun fixUrl(url: String): String {
        return when {
            url.isBlank() -> ""
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$mainUrl$url"
            else -> "$mainUrl/$url"
        }
    }

    private fun getQualityFromString(quality: String): Int {
        return when {
            quality.contains("4K") || quality.contains("2160") -> Qualities.P2160.value
            quality.contains("1080") -> Qualities.P1080.value
            quality.contains("720") -> Qualities.P720.value
            quality.contains("480") -> Qualities.P480.value
            quality.contains("360") -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    // Additional data classes for API responses
    data class ServersResponse(
        val servers: List<Server>
    )
    
    data class Server(
        val id: String,
        val name: String,
        val embed: String? = null
    )
    
    data class SourceResponse(
        val sources: List<VideoSource>? = null,
        val subtitles: List<SubtitleSource>? = null
    )
    
    data class VideoSource(
        val url: String,
        val quality: String? = null,
        val type: String? = null
    )
    
    data class SubtitleSource(
        val url: String,
        val lang: String? = null,
        val label: String? = null
    )
}
