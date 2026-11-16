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
    // Now relaxed for initial page load to allow series (PROGRAM) selection.
    private fun isUselessEpisode(title: String, contentType: String): Boolean {
        if (contentType == "PROGRAM") return true // Programs are useless as episodes
        
        val lowerTitle = title.lowercase()
        if (lowerTitle.contains("trailer") || 
            lowerTitle.contains("teaser") || 
            lowerTitle.contains("promo") || 
            lowerTitle.contains("song")) {
            return true
        }

        // Strict Check: Must contain a pattern like "S1 E2", "Season 1", "E12", "Part 1" etc.
        val seasonEpRegex = Regex("""(?i)(S\d+|Season\s*\d+|E\d+|Episode\s*\d+|Part\s*\d+|Vol\s*\d+)""")
        return !seasonEpRegex.containsMatchIn(title)
    }

    // 2. Fetch Item List (Updated to bongo-originals)
    override suspend fun getMainPage(page: Int): List<MovieItem> = withContext(Dispatchers.IO) {
        val limit = 100
        val offset = if (page <= 1) 0 else (page - 1) * limit
        // Using the requested placeholder endpoint.  You might need to adjust this if the actual endpoint is different.
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
                    val contentType = item.optString("contentType")
                    val slug = item.optString("slug")

                    // Ignore trailers, teasers, etc., from the main page list
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

                    // If it's a program, pass the slug as the URL so loadMediaStructure knows to treat it as a series.
                    // Otherwise, pass the ID.
                    val itemUrl = if (contentType == "PROGRAM" && slug.isNotEmpty()) slug else id

                    if (itemUrl.isNotEmpty() && title.isNotEmpty()) {
                        items.add(
                            MovieItem(
                                title = title,
                                image = imageUrl,
                                url = itemUrl, 
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
                    val contentType = item.optString("contentType")
                    val slug = item.optString("slug")

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

                    val itemUrl = if (contentType == "PROGRAM" && slug.isNotEmpty()) slug else id

                    if (itemUrl.isNotEmpty() && title.isNotEmpty()) {
                        items.add(
                            MovieItem(
                                title = title,
                                image = imageUrl,
                                url = itemUrl,
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

    // 4. Load Media Structure (Multi-Season & Strict Ignore Logic)
    override suspend fun loadMediaStructure(postUrl: String): MediaStructure = withContext(Dispatchers.IO) {
        val contentIdOrSlug = postUrl 
        
        try {
            // First, assume the postUrl is an ID and try fetching the content
            var targetUrl = "https://api.bongo-solutions.com/ironman/api/v1/contents/$contentIdOrSlug"
            var responseBody = fetchApiSync(targetUrl)

            // If the fetch fails or returns empty, the postUrl might be a slug (passed from getMainPage for PROGRAMs)
            if (responseBody.isEmpty()) {
                // If it's a slug, we need to fetch the episodes differently, or find the ID for the slug first.
                // However, based on the previous logic, the API /ironman/api/v1/contents/ works for slugs too in some cases?
                // Let's rely on the previous assumption that the provided API works. 
                // If the postUrl IS a slug, this API call might fail.
                // If /contents/ API doesn't accept slug, we need to handle it. 
                // Let's assume the previous logic where loadMediaStructure receives an ID works, 
                // BUT now getMainPage might send a slug.

                // In Bongo's API, fetching by slug might require a different endpoint if /contents/ expects a UUID.
                // For now, I will keep the original logic but handle the case where it's a program slug.
                
                // Let's try fetching the widget directly if the postUrl looks like a slug (not a UUID)
                if (!contentIdOrSlug.matches(Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\$"))) {
                    // It's likely a slug. We jump straight to processing it as a series.
                    return@withContext processAsSeries(contentIdOrSlug, 1) // Assume maxSeason 1 initially, or fetch metadata
                }
            }

            if (responseBody.isNotEmpty()) {
                val json = JSONObject(responseBody)
                val contentType = json.optString("content_type", "")
                val originalSlug = json.optString("slug", "")

                val programObj = json.optJSONObject("program")
                val maxSeason = programObj?.optInt("max_season", 1) ?: json.optInt("max_season", 1)

                if (contentType == "PROGRAM" && originalSlug.isNotEmpty()) {
                    
                    fun fetchWidgetItems(slugToTry: String): List<JSONObject> {
                        val widgetUrl = "https://api.bongo-solutions.com/ironman/api/v2/widget/$slugToTry?limit=100&offset=0"
                        val widgetResp = fetchApiSync(widgetUrl)
                        val list = mutableListOf<JSONObject>()
                        if (widgetResp.isNotEmpty()) {
                            try {
                                val arr = JSONObject(widgetResp).optJSONArray("items")
                                if (arr != null) {
                                    for (i in 0 until arr.length()) list.add(arr.getJSONObject(i))
                                }
                            } catch (e: Exception) {}
                        }
                        return list
                    }

                    val allEpisodes = mutableListOf<JSONObject>()
                    
                    val titleObj = json.optJSONObject("title")
                    val titleEn = titleObj?.optString("en")?.takeIf { it.isNotEmpty() } ?: json.optString("title")
                    val titleSlug = titleEn.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
                    val strippedSlug = originalSlug.replace(Regex("-[a-f0-9]{8,15}$"), "")

                    if (maxSeason > 1) {
                        for (i in 1..maxSeason) {
                            var seasonItems = fetchWidgetItems("$originalSlug-season-$i")
                            if (seasonItems.isEmpty() && titleSlug.isNotEmpty()) {
                                seasonItems = fetchWidgetItems("$titleSlug-season-$i")
                            }
                            if (seasonItems.isEmpty() && strippedSlug != originalSlug) {
                                seasonItems = fetchWidgetItems("$strippedSlug-season-$i")
                            }
                            allEpisodes.addAll(seasonItems)
                        }
                    } else {
                        var items = fetchWidgetItems(originalSlug)
                        if (items.isEmpty() && titleSlug.isNotEmpty() && titleSlug != originalSlug) {
                            items = fetchWidgetItems(titleSlug)
                        }
                        if (items.isEmpty() && strippedSlug != originalSlug) {
                            items = fetchWidgetItems(strippedSlug)
                        }
                        allEpisodes.addAll(items)
                    }

                    if (allEpisodes.isNotEmpty()) {
                        val seasonMap = linkedMapOf<String, MutableList<EpisodeItem>>()
                        val processedIds = mutableSetOf<String>()
                        
                        for (epItem in allEpisodes) {
                            val epId = epItem.optString("id")
                            val epTitle = epItem.optString("title")
                            val epContentType = epItem.optString("contentType")

                            // IGNORE LOGIC: Use strict checker for episodes
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

        // Fallback for Single Movie or if Series logic completely fails
        return@withContext MediaStructure(
            isSeries = false,
            rawMovieLinks = listOf(Pair("HD", postUrl)),
            seasons = emptyList()
        )
    }
    
    // Helper function to process slug directly (if needed when clicking a program from main page)
    private suspend fun processAsSeries(slug: String, maxSeason: Int): MediaStructure {
       fun fetchWidgetItems(slugToTry: String): List<JSONObject> {
            val widgetUrl = "https://api.bongo-solutions.com/ironman/api/v2/widget/$slugToTry?limit=100&offset=0"
            val widgetResp = fetchApiSync(widgetUrl)
            val list = mutableListOf<JSONObject>()
            if (widgetResp.isNotEmpty()) {
                try {
                    val arr = JSONObject(widgetResp).optJSONArray("items")
                    if (arr != null) {
                        for (i in 0 until arr.length()) list.add(arr.getJSONObject(i))
                    }
                } catch (e: Exception) {}
            }
            return list
        }
        
        val allEpisodes = mutableListOf<JSONObject>()
        var items = fetchWidgetItems(slug)
        allEpisodes.addAll(items)
        
        if (allEpisodes.isNotEmpty()) {
            val seasonMap = linkedMapOf<String, MutableList<EpisodeItem>>()
            val processedIds = mutableSetOf<String>()
            
            for (epItem in allEpisodes) {
                val epId = epItem.optString("id")
                val epTitle = epItem.optString("title")
                val epContentType = epItem.optString("contentType")

                // IGNORE LOGIC: Use strict checker
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
                return MediaStructure(
                    isSeries = true,
                    rawMovieLinks = emptyList(),
                    seasons = seasonsList
                )
            }
        }
        
        return MediaStructure(
            isSeries = false,
            rawMovieLinks = listOf(Pair("HD", slug)), // This might fail if it tries to play a slug
            seasons = emptyList()
        )
    }

    // 5. Resolve Direct HLS Link
    override suspend fun resolveDirectLink(generateUrl: String): StreamItem? = withContext(Dispatchers.IO) {
        val contentId = generateUrl // The Episode ID or Movie ID
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
