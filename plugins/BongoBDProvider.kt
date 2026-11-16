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

    // Helper to check if an item is useless (Trailer, Teaser, Song, or missing Season/Episode pattern)
    private fun isUselessEpisode(title: String, contentType: String): Boolean {
        if (contentType == "PROGRAM") return true
        
        val lowerTitle = title.lowercase()
        if (lowerTitle.contains("trailer") || 
            lowerTitle.contains("teaser") || 
            lowerTitle.contains("promo") || 
            lowerTitle.contains("song")) {
            return true
        }

        // Strict Check: Must contain a pattern like "S1 E2", "Season 1", "E12", "Part 1", "Episode" etc.
        val seasonEpRegex = Regex("""(?i)(S\d+|Season\s*\d+|E\d+|Episode\s*\d+|Part\s*\d+|Vol\s*\d+)""")
        return !seasonEpRegex.containsMatchIn(title)
    }

    // 2. Fetch Item List
    override suspend fun getMainPage(page: Int): List<MovieItem> = withContext(Dispatchers.IO) {
        val limit = 100
        val offset = if (page <= 1) 0 else (page - 1) * limit
        // Home page API
        val targetUrl = "https://api.bongo-solutions.com/ironman/api/v1/widget/placeholder/bongo-originals?limit=$limit&offset=$offset"

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

                    // Ignore only Trailers/Teasers on the main page
                    if (title.contains("Trailer", ignoreCase = true) || 
                        title.contains("Teaser", ignoreCase = true) ||
                        title.contains("Promo", ignoreCase = true)) {
                        continue
                    }

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
                                url = id, // Always passing ID
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

    // 4. Load Media Structure (Updated with program -> entity_program -> master_id Fallback)
    override suspend fun loadMediaStructure(postUrl: String): MediaStructure = withContext(Dispatchers.IO) {
        val contentId = postUrl 
        
        try {
            val targetUrl = "https://api.bongo-solutions.com/ironman/api/v1/contents/$contentId"
            val responseBody = fetchApiSync(targetUrl)
            
            if (responseBody.isNotEmpty()) {
                val json = JSONObject(responseBody)
                val contentType = json.optString("content_type", "")
                
                val programObj = json.optJSONObject("program")
                val vodObj = json.optJSONObject("vod")
                val innerProgramObj = vodObj?.optJSONObject("program")

                var programId = ""

                // Priority 0: If it's the root PROGRAM itself
                if (contentType == "PROGRAM") {
                    programId = json.optString("id", "")
                }

                // Priority 1: Check inside "program" -> "id"
                if (programId.isEmpty()) programId = programObj?.optString("id", "") ?: ""
                if (programId.isEmpty()) programId = innerProgramObj?.optString("id", "") ?: ""

                // Priority 2: Check "entity_program" (usually inside vod)
                if (programId.isEmpty()) programId = vodObj?.optString("entity_program", "") ?: ""
                if (programId.isEmpty()) programId = json.optString("entity_program", "")

                // Priority 3: Fallback to "master_id"
                if (programId.isEmpty()) programId = programObj?.optString("master_id", "") ?: ""
                if (programId.isEmpty()) programId = innerProgramObj?.optString("master_id", "") ?: ""
                if (programId.isEmpty()) programId = json.optString("master_id", "")

                // Extract Max Season
                var maxSeason = json.optInt("max_season", 0)
                if (programObj != null && programObj.has("max_season")) {
                    maxSeason = maxOf(maxSeason, programObj.optInt("max_season", 0))
                }
                if (innerProgramObj != null && innerProgramObj.has("max_season")) {
                    maxSeason = maxOf(maxSeason, innerProgramObj.optInt("max_season", 0))
                }
                if (maxSeason < 1) maxSeason = 1

                // Ensure we proceed if it's a program/multipart or has seasons
                if (programId.isNotEmpty() && (contentType == "PROGRAM" || contentType == "VOD_MULTIPART" || maxSeason > 1)) {
                    
                    val allEpisodes = mutableListOf<JSONObject>()
                    
                    // Loop through all available seasons exactly (Limit set to 1000)
                    for (seasonNum in 1..maxSeason) {
                        val seasonApiUrl = "https://api.bongo-solutions.com/ironman/api/v1/content/season?programId=$programId&season=$seasonNum&limit=1000&offset=0&sort=episode"
                        val seasonResp = fetchApiSync(seasonApiUrl)
                        
                        if (seasonResp.isNotEmpty()) {
                            try {
                                val seasonJson = JSONObject(seasonResp)
                                val itemsArray = seasonJson.optJSONArray("items")
                                if (itemsArray != null) {
                                    for (i in 0 until itemsArray.length()) {
                                        allEpisodes.add(itemsArray.getJSONObject(i))
                                    }
                                }
                            } catch (e: Exception) {}
                        }
                    }

                    if (allEpisodes.isNotEmpty()) {
                        val seasonMap = linkedMapOf<String, MutableList<EpisodeItem>>()
                        val processedIds = mutableSetOf<String>()
                        
                        for (epItem in allEpisodes) {
                            val epId = epItem.optString("id")
                            val epTitle = epItem.optString("title")
                            val epContentType = epItem.optString("contentType")

                            // IGNORE LOGIC
                            if (isUselessEpisode(epTitle, epContentType)) {
                                continue
                            }
                            
                            if (epId.isNotEmpty() && processedIds.add(epId)) {
                                val seasonMatch = Regex("""(?i)S(\d+)""").find(epTitle)
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
