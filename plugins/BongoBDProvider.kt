package com.moviefy.stream.providers

import com.moviefy.stream.ui.screens.EpisodeItem
import com.moviefy.stream.ui.screens.MainAPI
import com.moviefy.stream.ui.screens.MediaStructure
import com.moviefy.stream.ui.screens.MovieItem
import com.moviefy.stream.ui.screens.SeasonItem
import com.moviefy.stream.ui.screens.StreamItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class BongoBDProvider : MainAPI {
    override var name = "BongoBD"
    override var mainUrl = "https://bongobd.com"

    private var cachedToken: String? = null
    private val platformId = "abfea462-f64d-491e-9cd9-75ee001f45b0"
    private val userAgent = "Mozilla/5.0 (Linux; Android 16; 23090RA98I Build/BP2A.250605.031.A3) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.47 Mobile Safari/537.36"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    // 1. Token Generate and Cache
    private fun getToken(): String {
        cachedToken?.let { return it }
        
        try {
            val formBody = FormBody.Builder()
                .add("grant_type", "client_credentials")
                .add("client_id", "anonymous")
                .add("client_secret", "G1TBNg5HQ2gfd2Qucqss6KQgE1kkxJhc")
                .build()

            val request = Request.Builder()
                .url("https://accounts.bongobd.com/realms/bongo/protocol/openid-connect/token")
                .post(formBody)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Origin", mainUrl)
                .header("Referer", "$mainUrl/")
                .build()

            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: ""
            response.close()

            val json = JSONObject(body)
            val token = json.optString("access_token")
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
                .header("authorization", "Bearer $token")
                .header("platform-id", platformId)
                .header("country-code", "QkQ=")
                .header("x-client-info", "platform=web; os_name=Android; os_version=16; app=-; device=browser; form_factor=mobile; locale=en-BD")
                .header("user-agent", userAgent)
                .header("origin", mainUrl)
                .header("referer", "$mainUrl/")
                .header("accept", "*/*")
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

    // 2. Fetch Item List (Bachelor Point Season 5 as per logic)
    override suspend fun getMainPage(page: Int): List<MovieItem> = withContext(Dispatchers.IO) {
        val limit = 100
        val offset = if (page <= 1) 0 else (page - 1) * limit
        val targetUrl = "https://api.bongo-solutions.com/ironman/api/v2/widget/bachelor-point-season-5?limit=$limit&offset=$offset"

        val responseBody = fetchApiSync(targetUrl)
        val items = mutableListOf<MovieItem>()

        if (responseBody.isNotEmpty()) {
            try {
                val json = JSONObject(responseBody)
                val jsonArray = json.optJSONArray("items") ?: return@withContext emptyList()

                for (i in 0 until jsonArray.length()) {
                    val item = jsonArray.getJSONObject(i)
                    val id = item.optString("id") // Content ID
                    val title = item.optString("title")

                    // Extract THUMB_PORTRAIT image
                    var imageUrl = ""
                    val imagesArr = item.optJSONArray("images")
                    if (imagesArr != null) {
                        for (j in 0 until imagesArr.length()) {
                            val imgObj = imagesArr.getJSONObject(j)
                            if (imgObj.optString("code") == "THUMB_PORTRAIT") {
                                imageUrl = imgObj.optString("location")
                                break
                            }
                        }
                    }

                    if (id.isNotEmpty() && title.isNotEmpty()) {
                        items.add(
                            MovieItem(
                                title = title,
                                image = imageUrl,
                                url = id, // Storing ID in url so it passes to loadMediaStructure
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

    // 3. Search Logic Added Here
    override suspend fun search(query: String, page: Int): List<MovieItem> = withContext(Dispatchers.IO) {
        val limit = 20
        val offset = if (page <= 1) 0 else (page - 1) * limit
        val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8")
        
        // Search API url
        val targetUrl = "https://api.bongo-solutions.com/spiderman/api/v1/search?q=$encodedQuery&offset=$offset&limit=$limit"

        val responseBody = fetchApiSync(targetUrl)
        val items = mutableListOf<MovieItem>()

        if (responseBody.isNotEmpty()) {
            try {
                val json = JSONObject(responseBody)
                val jsonArray = json.optJSONArray("items") ?: return@withContext emptyList()

                for (i in 0 until jsonArray.length()) {
                    val item = jsonArray.getJSONObject(i)
                    val id = item.optString("id") // Content ID
                    val title = item.optString("title")

                    // Extract THUMB_PORTRAIT image
                    var imageUrl = ""
                    val imagesArr = item.optJSONArray("images")
                    if (imagesArr != null) {
                        for (j in 0 until imagesArr.length()) {
                            val imgObj = imagesArr.getJSONObject(j)
                            if (imgObj.optString("code") == "THUMB_PORTRAIT") {
                                imageUrl = imgObj.optString("location")
                                break
                            }
                        }
                    }

                    if (id.isNotEmpty() && title.isNotEmpty()) {
                        items.add(
                            MovieItem(
                                title = title,
                                image = imageUrl,
                                url = id, // Storing ID in url to pass to loadMediaStructure
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

    // 4. Load Media Structure (passing ID forward)
    override suspend fun loadMediaStructure(postUrl: String): MediaStructure = withContext(Dispatchers.IO) {
        // Here postUrl is actually the 'id' (e.g. 89ee9643-532d-4124-82cb-bc78ee3bb85d)
        MediaStructure(
            isSeries = false,
            rawMovieLinks = listOf(Pair("HD", postUrl)),
            seasons = emptyList()
        )
    }

    // 5. Resolve Direct HLS Link
    override suspend fun resolveDirectLink(generateUrl: String): StreamItem? = withContext(Dispatchers.IO) {
        val contentId = generateUrl // The ID passed from loadMediaStructure
        val targetUrl = "https://api.bongo-solutions.com/ironman/api/v1/contents/$contentId"

        val responseBody = fetchApiSync(targetUrl)
        
        if (responseBody.isNotEmpty()) {
            try {
                val json = JSONObject(responseBody)
                val vod = json.optJSONObject("vod")
                val activeEncode = vod?.optJSONObject("active_encode")
                val urls = activeEncode?.optJSONObject("urls")
                val hls = urls?.optJSONObject("hls")
                val m3u8RelativeUrl = hls?.optString("url") ?: ""

                if (m3u8RelativeUrl.isNotEmpty()) {
                    // Prepend vod link for full m3u8 path
                    val streamUrl = if (m3u8RelativeUrl.startsWith("/")) {
                        "https://vod.bongobd.com/vod/vod$m3u8RelativeUrl"
                    } else {
                        "https://vod.bongobd.com/vod/vod/$m3u8RelativeUrl"
                    }

                    return@withContext StreamItem(
                        quality = "HD",
                        streamUrl = streamUrl,
                        headers = mapOf(
                            "Referer" to "$mainUrl/",
                            "User-Agent" to userAgent,
                            "Origin" to mainUrl
                        )
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return@withContext null
    }
}
