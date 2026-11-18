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

    // 1. Fetch JSON/RSC using custom headers
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

    // 2. Fetch Item List (Home Page RSC Parser)
    override suspend fun getMainPage(page: Int): List<MovieItem> = withContext(Dispatchers.IO) {
        val targetUrl = "$mainUrl/movie?_rsc=1d6mw"
        val responseBody = fetchApiSync(targetUrl)
        val items = mutableListOf<MovieItem>()
        val addedUrls = mutableSetOf<String>()

        if (responseBody.isNotEmpty()) {
            try {
                // RSC Payload-কে মডেল অনুযায়ী স্লিট করা হচ্ছে
                val chunks = responseBody.split("\"classname\":\"App\\\\Models\\\\Video\"")
                
                for (i in 1 until chunks.size) {
                    val chunk = chunks[i].take(2500) // পরবর্তী অবজেক্টে যেন না যায় তার জন্য লিমিট
                    
                    val title = """"title":"([^"\\]*(?:\\.[^"\\]*)*)"""".toRegex().find(chunk)?.groups?.get(1)?.value ?: ""
                    val routeUri = """"route_uri":"([^"]+)"""".toRegex().find(chunk)?.groups?.get(1)?.value ?: ""
                    
                    // পোস্টার বা থাম্বনেইল কালেক্ট করা
                    var poster = """"poster":"([^"]+)"""".toRegex().find(chunk)?.groups?.get(1)?.value ?: ""
                    if (poster.isEmpty() || poster == "null") {
                        poster = """"thumbnail":"([^"]+)"""".toRegex().find(chunk)?.groups?.get(1)?.value ?: ""
                    }
                    
                    // JSON-এর ভেতরের ব্যাকস্ল্যাশ রিমুভ করা
                    poster = poster.replace("\\/", "/")

                    // ক্লিপ এবং ট্রেইলার ইগনোর করা
                    if (title.isNotEmpty() && routeUri.isNotEmpty() && routeUri != "null" && !routeUri.contains("/clip/")) {
                        if (!addedUrls.contains(routeUri)) {
                            addedUrls.add(routeUri)
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
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return@withContext items
    }

    // 3. Search Logic (API JSON Parser - অক্ষত রাখা হয়েছে)
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

    // 4. Load Media Structure (Movies & Series RSC Parser)
    override suspend fun loadMediaStructure(postUrl: String): MediaStructure = withContext(Dispatchers.IO) {
        val targetUrl = "$mainUrl$postUrl?_rsc=1d6mw"
        val responseBody = fetchApiSync(targetUrl)
        
        var mainMovieUrl = ""
        val episodesList = mutableListOf<EpisodeItem>()
        val seasonsList = mutableListOf<SeasonItem>()

        if (responseBody.isNotEmpty()) {
            try {
                // ভিডিও অবজেক্টগুলো আলাদা করা হচ্ছে
                val chunks = responseBody.split("\"classname\":\"App\\\\Models\\\\Video\"")
                
                for (i in 1 until chunks.size) {
                    val chunk = chunks[i].take(3500)
                    
                    val title = """"title":"([^"\\]*(?:\\.[^"\\]*)*)"""".toRegex().find(chunk)?.groups?.get(1)?.value ?: ""
                    val routeUri = """"route_uri":"([^"]+)"""".toRegex().find(chunk)?.groups?.get(1)?.value ?: ""
                    var url = """"url":"([^"]+)"""".toRegex().find(chunk)?.groups?.get(1)?.value ?: ""
                    
                    // URL থেকে এস্কেপ করা স্ল্যাশ সড়িয়ে নেওয়া
                    url = url.replace("\\/", "/")

                    // ট্রেইলার ও ক্লিপ ইগনোর করা
                    if (routeUri.contains("/clip/") || url.isEmpty() || url == "null") continue

                    // মূল মুভি লিংকের সাথে মিললে
                    if (routeUri == postUrl) {
                        mainMovieUrl = url
                    } 
                    // যদি এপিসোড হয়
                    else if (routeUri.contains("/episode/")) {
                        episodesList.add(EpisodeItem(name = title, rawGenerateLinks = listOf(Pair("HD", url))))
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // রিটার্ন লজিক (সিরিজ নাকি মুভি)
        if (episodesList.isNotEmpty()) {
            seasonsList.add(SeasonItem(name = "Episodes", episodes = episodesList))
            return@withContext MediaStructure(
                isSeries = true,
                rawMovieLinks = emptyList(),
                seasons = seasonsList
            )
        } else if (mainMovieUrl.isNotEmpty()) {
            return@withContext MediaStructure(
                isSeries = false,
                rawMovieLinks = listOf(Pair("HD", mainMovieUrl)),
                seasons = emptyList()
            )
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
