package com.moviefy.stream.providers

import com.moviefy.stream.ui.screens.EpisodeItem
import com.moviefy.stream.ui.screens.MainAPI
import com.moviefy.stream.ui.screens.MediaStructure
import com.moviefy.stream.ui.screens.MovieItem
import com.moviefy.stream.ui.screens.SeasonItem
import com.moviefy.stream.ui.screens.StreamItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class ChorkiProvider : MainAPI {
    override var name = "Chorki"
    override var mainUrl = "https://www.chorki.net"

    private val userAgent = "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/157.0.8103.38 Mobile Safari/537.36"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    // 1. Fetch JSON using custom RSC headers
    private fun fetchApiSync(targetUrl: String): String {
        return try {
            val request = Request.Builder()
                .url(targetUrl)
                .header("rsc", "1")
                .header("next-router-prefetch", "1")
                .header("accept", "*/*")
                .header("accept-language", "en-IN,en-US;q=0.9,en;q=0.8")
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

    // 2. Fetch Item List (Home Page - Example using Movie route)
    override suspend fun getMainPage(page: Int): List<MovieItem> = withContext(Dispatchers.IO) {
        val targetUrl = "$mainUrl/movie?_rsc=1d6mw"
        val responseBody = fetchApiSync(targetUrl)
        val items = mutableListOf<MovieItem>()

        if (responseBody.isNotEmpty()) {
            try {
                // RSC responses might have multiple JSON blocks separated by newlines.
                // We find the block containing "resource_type" or "items"
                val lines = responseBody.split("\n")
                for (line in lines) {
                    if (line.contains("\"items\":")) {
                        // Extract valid JSON substring if prepended with identifiers (e.g. 1:[...])
                        val jsonStr = line.substringAfter(":", line) 
                        val jsonArray = try {
                            JSONObject(jsonStr).optJSONArray("items")
                        } catch (e: Exception) {
                            try {
                                org.json.JSONArray(jsonStr)
                            } catch (ex: Exception) { null }
                        } ?: continue

                        for (i in 0 until jsonArray.length()) {
                            val item = jsonArray.optJSONObject(i) ?: continue
                            val content = item.optJSONObject("content") ?: continue
                            
                            val title = content.optString("title")
                            val routeUri = content.optString("route_uri") // e.g., /movie/rockstar
                            var poster = content.optString("poster")
                            if (poster.isEmpty() || poster == "null") {
                                poster = content.optString("thumbnail")
                            }

                            if (routeUri.isNotEmpty() && title.isNotEmpty()) {
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
        val targetUrl = "https://api-dynamic.chorki.net/v1/search?query=$encodedQuery&country=BD&platform=web&language=en"

        return@withContext try {
            val request = Request.Builder().url(targetUrl).header("user-agent", userAgent).build()
            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            response.close()
            
            val items = mutableListOf<MovieItem>()
            
            if (responseBody.isNotEmpty()) {
                val json = JSONObject(responseBody)
                val resultObj = json.optJSONObject("result")
                val dataObj = resultObj?.optJSONObject("data")
                val jsonArray = dataObj?.optJSONArray("data") ?: return@withContext emptyList()

                for (i in 0 until jsonArray.length()) {
                    val item = jsonArray.getJSONObject(i)
                    val title = item.optString("title")
                    val routeUri = item.optString("route_uri")
                    var poster = item.optString("poster")
                    if (poster.isEmpty() || poster == "null") {
                        poster = item.optString("thumbnail")
                    }

                    if (routeUri.isNotEmpty() && title.isNotEmpty()) {
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

    // 4. Load Media Structure (Movies & Series Handled Together)
    override suspend fun loadMediaStructure(postUrl: String): MediaStructure = withContext(Dispatchers.IO) {
        // postUrl holds the route_uri like "/movie/rockstar" or "/series/sugar-free"
        val targetUrl = "$mainUrl$postUrl?_rsc=1d6mw"
        
        try {
            val responseBody = fetchApiSync(targetUrl)
            
            if (responseBody.isNotEmpty()) {
                // Find the main JSON block containing "response": {"result": {"data": {...}}}
                val lines = responseBody.split("\n")
                var dataObj: JSONObject? = null
                var sectionsArray: org.json.JSONArray? = null
                
                for (line in lines) {
                    if (line.contains("\"result\":")) {
                        try {
                            val cleanJson = if (line.matches(Regex("^[0-9a-zA-Z]+:\\[?\\{.*"))) {
                                line.substringAfter(":", line)
                            } else {
                                line
                            }
                            
                            val json = JSONObject(if (cleanJson.startsWith("[")) cleanJson.removeSurrounding("[", "]") else cleanJson)
                            val responseObj = json.optJSONObject("response") ?: json
                            val resultObj = responseObj.optJSONObject("result")
                            
                            if (resultObj != null) {
                                dataObj = resultObj.optJSONObject("data")
                                sectionsArray = json.optJSONArray("sections") ?: responseObj.optJSONArray("sections") ?: resultObj.optJSONArray("sections")
                                break
                            }
                        } catch (e: Exception) {
                            continue
                        }
                    }
                }

                if (dataObj != null) {
                    val type = dataObj.optString("type")
                    val movieUrl = dataObj.optString("url")

                    // Series Logic Check
                    if (type == "series" || sectionsArray != null) {
                        val seasonsList = mutableListOf<SeasonItem>()
                        val episodesList = mutableListOf<EpisodeItem>()

                        // Check if direct episodes are inside "sections"
                        if (sectionsArray != null) {
                            for (i in 0 until sectionsArray.length()) {
                                val section = sectionsArray.getJSONObject(i)
                                val itemsArray = section.optJSONArray("items")
                                
                                if (itemsArray != null) {
                                    for (j in 0 until itemsArray.length()) {
                                        val epObj = itemsArray.getJSONObject(j)
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
                            }
                        }
                        
                        // Fallback: Check if single episode URL is present but type is series
                        if (episodesList.isEmpty() && movieUrl.isNotEmpty() && movieUrl != "null") {
                            episodesList.add(EpisodeItem(name = dataObj.optString("title"), rawGenerateLinks = listOf(Pair("HD", movieUrl))))
                        }

                        if (episodesList.isNotEmpty()) {
                            seasonsList.add(SeasonItem(name = "Episodes", episodes = episodesList))
                            return@withContext MediaStructure(
                                isSeries = true,
                                rawMovieLinks = emptyList(),
                                seasons = seasonsList
                            )
                        }
                    } 
                    
                    // Single Movie Logic
                    if (movieUrl.isNotEmpty() && movieUrl != "null") {
                        return@withContext MediaStructure(
                            isSeries = false,
                            rawMovieLinks = listOf(Pair("HD", movieUrl)),
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
        // Because Chorki already provides the .m3u8 path inside 'url', generateUrl is the direct link
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
