package com.egydead

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.Locale

// Data classes for subtitle APIs
data class SubtitlesAPI(
    val subtitles: List<SubtitleItem>?
)

data class SubtitleItem(
    val lang: String?,
    val url: String?
)

data class WyZIESUB(
    val display: String?,
    val url: String?
)

object SubUtils {

    // Extended language mapping
    private val languageMap = mapOf(
        "en" to "English", "es" to "Spanish", "fr" to "French", "de" to "German",
        "it" to "Italian", "pt" to "Portuguese", "ru" to "Russian", "ja" to "Japanese",
        "ko" to "Korean", "zh" to "Chinese", "ar" to "Arabic", "hi" to "Hindi",
        "ta" to "Tamil", "te" to "Telugu", "ml" to "Malayalam", "kn" to "Kannada",
        "tr" to "Turkish", "pl" to "Polish", "nl" to "Dutch", "sv" to "Swedish",
        "da" to "Danish", "no" to "Norwegian", "fi" to "Finnish", "he" to "Hebrew",
        "th" to "Thai", "vi" to "Vietnamese", "id" to "Indonesian", "ms" to "Malay"
    )

    private fun getLanguage(code: String?): String {
        return languageMap[code?.lowercase()] ?: code?.uppercase() ?: "Unknown"
    }

    private fun String.capitalizeLanguage(): String {
        return this.replaceFirstChar { 
            if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() 
        }
    }

    suspend fun invokeSubtitleAPI(
        id: String? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
    ) {
        if (id.isNullOrBlank()) return
        
        try {
            val url = if (season == null) {
                "https://opensubtitles-v3.strem.io/subtitles/movie/$id.json"
            } else {
                "https://opensubtitles-v3.strem.io/subtitles/series/$id:$season:$episode.json"
            }
            
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36"
            )
            
            app.get(url, headers = headers, timeout = 30)
                .parsedSafe<SubtitlesAPI>()
                ?.subtitles
                ?.forEach { subtitle ->
                    val language = getLanguage(subtitle.lang).capitalizeLanguage()
                    val subUrl = subtitle.url ?: return@forEach
                    
                    subtitleCallback.invoke(
                        SubtitleFile(language, subUrl)
                    )
                }
        } catch (e: Exception) {
            // Log error if needed
        }
    }

    suspend fun invokeWyZIESUBAPI(
        id: String? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
    ) {
        if (id.isNullOrBlank()) return
        
        try {
            val baseUrl = "https://sub.wyzie.ru"
            val url = if (season == null) {
                "$baseUrl/search?id=$id"
            } else {
                "$baseUrl/search?id=$id&season=$season&episode=$episode"
            }

            val response = app.get(url).text
            val gson = Gson()
            val listType = object : TypeToken<List<WyZIESUB>>() {}.type
            val subtitles: List<WyZIESUB> = gson.fromJson(response, listType)
            
            subtitles.forEach { subtitle ->
                val language = (subtitle.display ?: "Unknown").capitalizeLanguage()
                val subUrl = subtitle.url ?: return@forEach
                
                subtitleCallback.invoke(
                    SubtitleFile(language, subUrl)
                )
            }
        } catch (e: Exception) {
            // Log error if needed
        }
    }

    // Combined function to try multiple subtitle sources
    suspend fun getAllSubtitles(
        id: String? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
    ) {
        invokeSubtitleAPI(id, season, episode, subtitleCallback)
        invokeWyZIESUBAPI(id, season, episode, subtitleCallback)
    }
}
