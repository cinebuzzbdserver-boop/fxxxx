package com.moviefy.stream.providers

import com.moviefy.stream.ui.screens.EpisodeItem
import com.moviefy.stream.ui.screens.MainAPI
import com.moviefy.stream.ui.screens.MediaStructure
import com.moviefy.stream.ui.screens.MovieItem
import com.moviefy.stream.ui.screens.SeasonItem
import com.moviefy.stream.ui.screens.StreamItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class DeeptoPlayProvider : MainAPI {
    override var name = "DeeptoPlay"
    override var mainUrl = "https://www.deeptoplay.com"
    private val apiBaseUrl = "https://api.deeptoplay.com/v1"

    private val userAgent = "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/157.0.8103.38 Mobile Safari/537.36"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    // 1. Common JSON Fetcher
    private fun fetchApiSync(targetUrl: String): String {
        return try {
            val request = Request.Builder()
                .url(targetUrl)
                .header("accept", "application/json")
                .header("user-agent", userAgent)
                .build()

            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: ""
            response.close()
            body
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
    }

    // 2. Fetch Item List (Home Page - Top 10 List)
    override suspend fun getMainPage(page: Int): List<MovieItem> = withContext(Dispatchers.IO) {
        val targetUrl = "$apiBaseUrl/lists/deeptoplay-top-10?page=$page"
        val responseBody = fetchApiSync(targetUrl)
        val items = mutableListOf<MovieItem>()

        if (responseBody.isNotEmpty()) {
            try {
                val json = JSONObject(responseBody)
                val resultObj = json.optJSONObject("result")
                val dataObj = resultObj?.optJSONObject("data")
                val jsonArray = dataObj?.optJSONArray("data") ?: return@withContext emptyList()

                for (i in 0 until jsonArray.length()) {
                    val item = jsonArray.getJSONObject(i)
                    val title = item.optString("title")
                    val slug = item.optString("slug")
                    val type = item.optString("type")

                    var poster = item.optString("poster")
                    if (poster.isEmpty() || poster == "null") {
                        poster = item.optString("thumbnail")
                    }

                    var routeUri = item.optString("route_uri")
                    if (routeUri.isEmpty() || routeUri == "null") {
                        routeUri = "$type/$slug"
                    } else {
                        routeUri = routeUri.removePrefix("/")
                    }

                    if (slug.isNotEmpty() && title.isNotEmpty()) {
                        items.add(
                            MovieItem(
                                title = title,
                                image = poster,
                                url = routeUri, 
                                isPoster = true,
                                referer = mainUrl
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return@withContext items
    }

    // 3. Search Logic
    override suspend fun search(query: String, page: Int): List<MovieItem> = withContext(Dispatchers.IO) {
        val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8")
        val targetUrl = "$apiBaseUrl/search?query=$encodedQuery"

        return@withContext try {
            val responseBody = fetchApiSync(targetUrl)
            val items = mutableListOf<MovieItem>()
            
            if (responseBody.isNotEmpty()) {
                val json = JSONObject(responseBody)
                val resultObj = json.optJSONObject("result")
                val dataObj = resultObj?.optJSONObject("data")
                val jsonArray = dataObj?.optJSONArray("data") ?: return@withContext emptyList()

                for (i in 0 until jsonArray.length()) {
                    val item = jsonArray.getJSONObject(i)
                    val title = item.optString("title")
                    val slug = item.optString("slug")
                    val type = item.optString("type")

                    var poster = item.optString("poster")
                    if (poster.isEmpty() || poster == "null") {
                        poster = item.optString("thumbnail")
                    }

                    var routeUri = item.optString("route_uri")
                    if (routeUri.isEmpty() || routeUri == "null") {
                        routeUri = "$type/$slug"
                    } else {
                        routeUri = routeUri.removePrefix("/")
                    }

                    if (slug.isNotEmpty() && title.isNotEmpty()) {
                        items.add(
                            MovieItem(
                                title = title,
                                image = poster,
                                url = routeUri, 
                                isPoster = true,
                                referer = mainUrl
                            )
                        )
                    }
                }
            }
            items
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    // Helper Function to Fetch Episodes for a specific Season
    private suspend fun fetchEpisodesForSeason(seasonUri: String, seasonTitle: String): SeasonItem? = withContext(Dispatchers.IO) {
        val targetUrl = "$apiBaseUrl/$seasonUri"
        val responseBody = fetchApiSync(targetUrl)
        
        if (responseBody.isNotEmpty()) {
            try {
                val json = JSONObject(responseBody)
                val resultObj = json.optJSONObject("result") ?: return@withContext null
                val dataObj = resultObj.optJSONObject("data") ?: return@withContext null
                
                val episodesArray = dataObj.optJSONArray("data")
                val episodesList = mutableListOf<EpisodeItem>()

                if (episodesArray != null) {
                    for (i in 0 until episodesArray.length()) {
                        val epObj = episodesArray.getJSONObject(i)
                        val epTitle = epObj.optString("title")
                        val epUrl = epObj.optString("url")
                        
                        if (epUrl.isNotEmpty() && epUrl != "null") {
                            episodesList.add(
                                EpisodeItem(
                                    name = epTitle,
                                    rawGenerateLinks = listOf(Pair("HD", epUrl))
                                )
                            )
                        }
                    }
                }
                if (episodesList.isNotEmpty()) {
                    episodesList.reverse() // লেটেস্ট এপিসোড উপরে আনার জন্য রিভার্স করা হলো
                    return@withContext SeasonItem(name = seasonTitle, episodes = episodesList)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return@withContext null
    }

    // 4. Load Media Structure (Handle All Types Dynamically with Deep Fetching for Series)
    override suspend fun loadMediaStructure(postUrl: String): MediaStructure = withContext(Dispatchers.IO) {
        val targetUrl = "$apiBaseUrl/$postUrl"
        
        try {
            val responseBody = fetchApiSync(targetUrl)
            if (responseBody.isNotEmpty()) {
                val json = JSONObject(responseBody)
                val resultObj = json.optJSONObject("result") ?: return@withContext MediaStructure(false, emptyList(), emptyList())
                val dataObj = resultObj.optJSONObject("data") ?: resultObj
                
                val isSeries = postUrl.startsWith("series/")

                if (isSeries) {
                    val seasonsList = mutableListOf<SeasonItem>()
                    val sectionsArray = resultObj.optJSONArray("sections")
                    
                    var seasonLinks = mutableListOf<Pair<String, String>>()

                    if (sectionsArray != null) {
                        for (i in 0 until sectionsArray.length()) {
                            val section = sectionsArray.getJSONObject(i)
                            val sectionTitle = section.optString("title")
                            
                            if (sectionTitle.contains("Seasons", ignoreCase = true)) {
                                val itemsArray = section.optJSONArray("items")
                                if (itemsArray != null) {
                                    for (j in 0 until itemsArray.length()) {
                                        val seasonObj = itemsArray.getJSONObject(j)
                                        val sTitle = seasonObj.optString("title").ifEmpty { "Season ${j+1}" }
                                        var sRouteUri = seasonObj.optString("route_uri")
                                        if (sRouteUri.isNotEmpty() && sRouteUri != "null") {
                                            sRouteUri = sRouteUri.removePrefix("/")
                                            seasonLinks.add(Pair(sTitle, sRouteUri))
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (seasonLinks.isNotEmpty()) {
                        val fetchedSeasons = seasonLinks.map { (sTitle, sRouteUri) ->
                            async { fetchEpisodesForSeason(sRouteUri, sTitle) }
                        }.awaitAll()

                        seasonsList.addAll(fetchedSeasons.filterNotNull().reversed()) // লেটেস্ট সিজন উপরে আনার জন্য রিভার্স করা হলো
                    } 
                    else {
                        if (sectionsArray != null) {
                            for (i in 0 until sectionsArray.length()) {
                                val section = sectionsArray.getJSONObject(i)
                                val itemsArray = section.optJSONArray("items")
                                
                                if (itemsArray != null && itemsArray.length() > 0 && section.optString("type") == "series") {
                                    val episodesList = mutableListOf<EpisodeItem>()
                                    
                                    for (j in 0 until itemsArray.length()) {
                                        val epObj = itemsArray.getJSONObject(j)
                                        val epTitle = epObj.optString("title")
                                        val epUrl = epObj.optString("url")
                                        
                                        if (epUrl.isNotEmpty() && epUrl != "null") {
                                            episodesList.add(EpisodeItem(name = epTitle, rawGenerateLinks = listOf(Pair("HD", epUrl))))
                                        }
                                    }
                                    
                                    if (episodesList.isNotEmpty()) {
                                        episodesList.reverse() // লেটেস্ট এপিসোড উপরে আনার জন্য রিভার্স করা হলো
                                        val seasonTitle = section.optString("title").ifEmpty { "Season 1" }
                                        seasonsList.add(SeasonItem(name = seasonTitle, episodes = episodesList))
                                    }
                                }
                            }
                            seasonsList.reverse() // লেটেস্ট সিজন উপরে আনার জন্য রিভার্স করা হলো
                        }
                    }
                    
                    if (seasonsList.isNotEmpty()) {
                        return@withContext MediaStructure(
                            isSeries = true,
                            rawMovieLinks = emptyList(),
                            seasons = seasonsList
                        )
                    }

                } else {
                    val filmUrl = dataObj.optString("url")
                    if (filmUrl.isNotEmpty() && filmUrl != "null") {
                        return@withContext MediaStructure(
                            isSeries = false,
                            rawMovieLinks = listOf(Pair("HD", filmUrl)),
                            seasons = emptyList()
                        )
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return@withContext MediaStructure(false, emptyList(), emptyList())
    }

    // 5. Resolve Direct Link
    override suspend fun resolveDirectLink(generateUrl: String): StreamItem? = withContext(Dispatchers.IO) {
        if (generateUrl.isNotEmpty()) {
            return@withContext StreamItem(
                quality = "HD",
                streamUrl = generateUrl,
                headers = mapOf(
                    "Referer" to "$mainUrl/",
                    "Origin" to mainUrl,
                    "User-Agent" to userAgent
                )
            )
        }
        return@withContext null
    }
}
