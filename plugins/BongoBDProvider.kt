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
import org.json.JSONArray
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

    // 2. Fetch Item List
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
                    val id = item.optString("id") 
                    val title = item.optString("title")
                    val contentType = item.optString("contentType")

                    // Exclude root PROGRAM items and Trailers from main page list too just in case
                    if (contentType == "PROGRAM" || title.contains("Trailer", ignoreCase = true)) continue

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
                                url = id, 
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
        val limit = 20
        val offset = if (page <= 1) 0 else (page - 1) * limit
        val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8")
        
        val targetUrl = "https://api.bongo-solutions.com/spiderman/api/v1/search?q=$encodedQuery&offset=$offset&limit=$limit"

        val responseBody = fetchApiSync(targetUrl)
        val items = mutableListOf<MovieItem>()

        if (responseBody.isNotEmpty()) {
            try {
                val json = JSONObject(responseBody)
                val jsonArray = json.optJSONArray("items") ?: return@withContext emptyList()

                for (i in 0 until jsonArray.length()) {
                    val item = jsonArray.getJSONObject(i)
                    val id = item.optString("id") 
                    val title = item.optString("title")

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
                                url = id,
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

    // 4. Load Media Structure (Series Handle Update with Ignore Logic)
    override suspend fun loadMediaStructure(postUrl: String): MediaStructure = withContext(Dispatchers.IO) {
        val contentId = postUrl 
        
        try {
            val targetUrl = "https://api.bongo-solutions.com/ironman/api/v1/contents/$contentId"
            val responseBody = fetchApiSync(targetUrl)
            
            if (responseBody.isNotEmpty()) {
                val json = JSONObject(responseBody)
                val contentType = json.optString("content_type", "")
                val originalSlug = json.optString("slug", "")

                if (contentType == "PROGRAM" && originalSlug.isNotEmpty()) {
                    
                    fun fetchWidgetItems(slugToTry: String): JSONArray {
                        val widgetUrl = "https://api.bongo-solutions.com/ironman/api/v2/widget/$slugToTry?limit=100&offset=0"
                        val widgetResp = fetchApiSync(widgetUrl)
                        if (widgetResp.isNotEmpty()) {
                            try {
                                return JSONObject(widgetResp).optJSONArray("items") ?: JSONArray()
                            } catch (e: Exception) {}
                        }
                        return JSONArray()
                    }

                    var items = fetchWidgetItems(originalSlug)

                    if (items.length() == 0) {
                        val titleObj = json.optJSONObject("title")
                        val titleEn = titleObj?.optString("en")?.takeIf { it.isNotEmpty() } ?: json.optString("title")
                        
                        val titleSlug = titleEn.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
                        
                        if (titleSlug.isNotEmpty() && titleSlug != originalSlug) {
                            items = fetchWidgetItems(titleSlug)
                        }
                    }

                    if (items.length() == 0) {
                        val strippedSlug = originalSlug.replace(Regex("-[a-f0-9]{8,15}$"), "")
                        if (strippedSlug != originalSlug) {
                            items = fetchWidgetItems(strippedSlug)
                        }
                    }

                    if (items.length() > 0) {
                        val seasonMap = linkedMapOf<String, MutableList<EpisodeItem>>()
                        
                        for (i in 0 until items.length()) {
                            val epItem = items.getJSONObject(i)
                            val epId = epItem.optString("id")
                            val epTitle = epItem.optString("title")
                            val epContentType = epItem.optString("contentType")

                            // IGNORE LOGIC: Skip PROGRAM types (like root series links) and Trailers
                            if (epContentType == "PROGRAM" || epTitle.contains("Trailer", ignoreCase = true)) {
                                continue
                            }
                            
                            if (epId.isNotEmpty()) {
                                val seasonMatch = Regex("""S(\d+)""", RegexOption.IGNORE_CASE).find(epTitle)
                                val seasonName = if (seasonMatch != null) "Season ${seasonMatch.groupValues[1].toInt()}" else "Season 1"
                                
                                val rawLinks = listOf(Pair("HD", epId))
                                seasonMap.getOrPut(seasonName) { mutableListOf() }.add(
                                    EpisodeItem(name = epTitle, rawGenerateLinks = rawLinks)
                                )
                            }
                        }
                        
                        val seasonsList = seasonMap.map { SeasonItem(name = it.key, episodes = it.value) }
                        
                        if (seasonsList.isNotEmpty()) {
                            return@withContext MediaStructure(
                                isSeries = true,
                                rawMovieLinks = emptyList(),
                                seasons = seasonsList
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Fallback for Single Movie
        return@withContext MediaStructure(
            isSeries = false,
            rawMovieLinks = listOf(Pair("HD", postUrl)),
            seasons = emptyList()
        )
    }

    // 5. Resolve Direct HLS Link
    override suspend fun resolveDirectLink(generateUrl: String): StreamItem? = withContext(Dispatchers.IO) {
        val contentId = generateUrl
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
