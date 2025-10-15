package com.akwam.utils

import com.lagradost.cloudstream3.*
import org.jsoup.Jsoup

object AkwamParser {

    suspend fun searchMovies(query: String): List<SearchResponse> {
        // Your search logic here, using Jsoup or your preferred method to parse the search results
        val document = Jsoup.connect("https://ak.sv/search?q=$query").get()
        
        // Example: return a list of search results
        return document.select("div.movie").map {
            val title = it.select("h3.title").text()
            val url = it.select("a").attr("href")
            val imageUrl = it.select("img").attr("src")
            
            SearchResponse(title, url, imageUrl)
        }
    }

    suspend fun loadMovie(url: String): LoadResponse? {
        // Your movie loading logic here, use Jsoup to scrape the movie page
        val document = Jsoup.connect(url).get()
        
        // Example: Return a movie object
        val title = document.select("h1.title").text()
        val description = document.select("div.description").text()
        val year = document.select("span.year").text()

        return MovieLoadResponse(title, description, year)
    }
}
