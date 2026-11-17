package com.moviefy.stream.providers

import com.moviefy.stream.ui.screens.EpisodeItem
import com.moviefy.stream.ui.screens.MainAPI
import com.moviefy.stream.ui.screens.MediaStructure
import com.moviefy.stream.ui.screens.MovieItem
import com.moviefy.stream.ui.screens.SeasonItem
import com.moviefy.stream.ui.screens.StreamItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class IScreenProvider : MainAPI {
    override var name = "iScreen"
    override var mainUrl = "https://iscreen.com.bd"

    private var cachedToken: String? = null
    private val apiBaseUrl = "https://api.rockstreamer.com"
    private val userAgent = "Mozilla/5.0 (Linux; Android 16; 23090RA98I Build/BP2A.250605.031.A3) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/157.0.8103.38 Mobile Safari/537.36"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    // 1. Token Generate and Cache
    private fun getToken(): String {
        cachedToken?.let { return it }

        try {
            val jsonPayload = JSONObject().apply {
                put("platform", "iscreen")
            }.toString()

            val requestBody = jsonPayload.toRequestBody("application/json".toMediaTypeOrNull())

            val request = Request.Builder()
                .url("$apiBaseUrl/auth/token/temp")
                .post(requestBody)
                .header("Accept", "*/*")
                .header("Content-Type", "application/json")
                .build()

            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: ""
            response.close()

            val json = JSONObject(body)
            val token = json.optString("token")
            if (token.isNotEmpty()) {
                cachedToken = token
                return token
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return ""
    }

    // Common API fetcher with Token
    private fun fetchApiSync(targetUrl: String): String {
        val token = getToken()
        return try {
            val request = Request.Builder()
                .url(targetUrl)
                .header("Authorization", "Bearer $token")
                .header("Referer", "$mainUrl/")
                .header("Origin", mainUrl)
                .header("Accept", "application/json")
                .header("User-Agent", userAgent)
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

    // Helper to extract Best Image URL
    private fun extractImage(item: JSONObject): String {
        // Try vertical thumbnails first
        val vThumbs = item.optJSONArray("verticalThumbnails")
        if (vThumbs != null && vThumbs.length() > 0) {
            val path = vThumbs.getJSONObject(0).optString("fullPath")
            if (path.isNotEmpty() && path != "null") return path
        }
        
        // Try horizontal thumbnails next
        val hThumbs = item.optJSONArray("horizontalThumbnails")
        if (hThumbs != null && hThumbs.length() > 0) {
            val path = hThumbs.getJSONObject(0).optString("fullPath")
            if (path.isNotEmpty() && path != "null") return path
        }

        // Try direct thumbnail string (used in search API)
        val thumb = item.optString("thumbnail")
        if (thumb.isNotEmpty() && thumb != "null") {
            return if (thumb.startsWith("http")) thumb else "https://img.rockstreamer.com/220xauto/$thumb"
        }
        
        return ""
    }

    // 2. Fetch Item List (Home Page)
    override suspend fun getMainPage(page: Int): List<MovieItem> = withContext(Dispatchers.IO) {
        val targetUrl = "$apiBaseUrl/api/v2/content/seemore/slug/hot-happening?page=$page"
        val responseBody = fetchApiSync(targetUrl)
        val items = mutableListOf<MovieItem>()

        if (responseBody.isNotEmpty()) {
            try {
                val json = JSONObject(responseBody)
                val contents = json.optJSONObject("contents")
                val jsonArray = contents?.optJSONArray("items") ?: return@withContext emptyList()

                for (i in 0 until jsonArray.length()) {
                    val item = jsonArray.getJSONObject(i)
                    val title = item.optString("title")
                    val slug = item.optString("slug")
                    val type = item.optString("type") // 'video' or 'series'
                    
                    val imageUrl = extractImage(item)

                    if (slug.isNotEmpty() && title.isNotEmpty()) {
                        // Create intelligent URL param to distinguish series and videos
                        val urlParam = if (type == "series") "series/slug/$slug" else "slug/$slug"
                        
                        items.add(
                            MovieItem(
                                title = title,
                                image = imageUrl,
                                url = urlParam, // Sent dynamically to loadMediaStructure
                                isPoster = true,
                                referer = "$mainUrl/"
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
        val targetUrl = "$apiBaseUrl/api/v2/search?page=$page&title=$encodedQuery&type=all"

        val responseBody = fetchApiSync(targetUrl)
        val items = mutableListOf<MovieItem>()

        if (responseBody.isNotEmpty()) {
            try {
                val json = JSONObject(responseBody)
                val videosObj = json.optJSONObject("videos")
                val jsonArray = videosObj?.optJSONArray("items") ?: return@withContext emptyList()

                for (i in 0 until jsonArray.length()) {
                    val item = jsonArray.getJSONObject(i)
                    val title = item.optString("title")
                    val slug = item.optString("slug")
                    val type = item.optString("type")

                    val imageUrl = extractImage(item)

                    if (slug.isNotEmpty() && title.isNotEmpty()) {
                        val urlParam = if (type == "series") "series/slug/$slug" else "slug/$slug"

                        items.add(
                            MovieItem(
                                title = title,
                                image = imageUrl,
                                url = urlParam,
                                isPoster = true,
                                referer = "$mainUrl/"
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

    // 4. Load Media Structure (Movies & Series Handled Together)
    override suspend fun loadMediaStructure(postUrl: String): MediaStructure = withContext(Dispatchers.IO) {
        // postUrl holds something like "series/slug/caf-umbrella" or "slug/caf-umbrella"
        val targetUrl = "$apiBaseUrl/api/v2/content/$postUrl"
        
        try {
            val responseBody = fetchApiSync(targetUrl)
            if (responseBody.isNotEmpty()) {
                val json = JSONObject(responseBody)
                
                // Is it a Series?
                if (postUrl.startsWith("series/slug/")) {
                    val seriesInfo = json.optJSONObject("seriesInfo") ?: json
                    val rootTrailerPath = seriesInfo.optString("trailerPath", "")
                    
                    val seasonsList = mutableListOf<SeasonItem>()
                    val seasonsArray = json.optJSONArray("contents") // List of seasons

                    if (seasonsArray != null) {
                        for (i in 0 until seasonsArray.length()) {
                            val seasonObj = seasonsArray.getJSONObject(i)
                            val seasonTitle = seasonObj.optString("title").ifEmpty { "Season ${i + 1}" }
                            
                            val episodesList = mutableListOf<EpisodeItem>()
                            val episodesArray = seasonObj.optJSONArray("contents")
                            
                            if (episodesArray != null) {
                                for (j in 0 until episodesArray.length()) {
                                    val epObj = episodesArray.getJSONObject(j)
                                    val epTitle = epObj.optString("title")
                                    val epPath = epObj.optString("path")
                                    
                                    // Ignore if path is empty OR matches the main series trailer path
                                    if (epPath.isNotEmpty() && epPath != "null" && epPath != rootTrailerPath) {
                                        episodesList.add(
                                            EpisodeItem(
                                                name = epTitle,
                                                rawGenerateLinks = listOf(Pair("HD", epPath))
                                            )
                                        )
                                    }
                                }
                            }
                            
                            if (episodesList.isNotEmpty()) {
                                seasonsList.add(SeasonItem(name = seasonTitle, episodes = episodesList))
                            }
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
                    // It is a Single Video / Movie
                    val path = json.optString("path")
                    if (path.isNotEmpty() && path != "null") {
                        return@withContext MediaStructure(
                            isSeries = false,
                            rawMovieLinks = listOf(Pair("HD", path)),
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
        // Because rockstreamer directly provides the .m3u8 path, generateUrl is already the playable link!
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
