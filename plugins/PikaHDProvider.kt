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
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class PikaHDProvider : MainAPI {
    override var name = "PikaHD"
    override var mainUrl = "https://new.pikahd.co"

    private val userAgent = "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/157.0.8103.38 Mobile Safari/537.36"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    // 1. Common GET Request Helper
    private fun fetchGetSync(targetUrl: String, referer: String? = null): String {
        return try {
            val builder = Request.Builder()
                .url(targetUrl)
                .header("User-Agent", userAgent)
                .header("Accept", "*/*")
            if (!referer.isNullOrEmpty()) {
                builder.header("Referer", referer)
            }
            val response = httpClient.newCall(builder.build()).execute()
            val body = response.body?.string() ?: ""
            response.close()
            body
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
    }

    // Helper: Parse SvelteKit __data.json chunk items (Used by both Home & Search)
    private fun parseSvelteChunkItems(responseBody: String): List<MovieItem> {
        val items = mutableListOf<MovieItem>()
        if (responseBody.isEmpty()) return items

        try {
            val lines = responseBody.split("\n")
            val chunkLine = lines.firstOrNull { it.trim().startsWith("{\"type\":\"chunk\"") } ?: return items
            val chunkJson = JSONObject(chunkLine)
            val dataArr = chunkJson.optJSONArray("data") ?: return items

            // Find the object containing "items" index
            var itemsArrayIdx = -1
            for (i in 0 until dataArr.length()) {
                val obj = dataArr.optJSONObject(i)
                if (obj != null && obj.has("items")) {
                    itemsArrayIdx = obj.optInt("items", -1)
                    break
                }
            }

            if (itemsArrayIdx == -1) return items
            val itemIndices = dataArr.optJSONArray(itemsArrayIdx) ?: return items

            for (i in 0 until itemIndices.length()) {
                val itemIdx = itemIndices.optInt(i, -1)
                if (itemIdx == -1) continue
                val itemObj = dataArr.optJSONObject(itemIdx) ?: continue

                val titleIdx = itemObj.optInt("post_title", -1)
                val slugIdx = itemObj.optInt("slug", -1)
                val imgIdx = itemObj.optInt("thumbnail_image", -1)

                val title = if (titleIdx != -1) dataArr.optString(titleIdx, "").trim() else ""
                val slug = if (slugIdx != -1) dataArr.optString(slugIdx, "").trim() else ""
                val image = if (imgIdx != -1) dataArr.optString(imgIdx, "").trim() else ""

                if (title.isNotEmpty() && slug.isNotEmpty()) {
                    items.add(
                        MovieItem(
                            title = title,
                            image = image,
                            url = slug,
                            isPoster = true,
                            referer = "$mainUrl/"
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return items
    }

    // 2. Fetch Item List (Home Page)
    override suspend fun getMainPage(page: Int): List<MovieItem> = withContext(Dispatchers.IO) {
        val targetUrl = "$mainUrl/__data.json?page=$page"
        val responseBody = fetchGetSync(targetUrl, "$mainUrl/")
        return@withContext parseSvelteChunkItems(responseBody)
    }

    // 3. Search Logic
    override suspend fun search(query: String, page: Int): List<MovieItem> = withContext(Dispatchers.IO) {
        val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8")
        val targetUrl = "$mainUrl/__data.json?q=$encodedQuery&page=$page&x-sveltekit-trailing-slash=1&x-sveltekit-invalidated=01"
        val responseBody = fetchGetSync(targetUrl, "$mainUrl/")
        return@withContext parseSvelteChunkItems(responseBody)
    }

    // Helper: Clean HTML tags from text
    private fun stripHtml(input: String): String {
        return input
            .replace(Regex("<[^>]*>"), "")
            .replace("&nbsp;", " ")
            .replace("Â", "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    // 4. Load Media Structure (Episodes & Movies from post_content HTML)
    override suspend fun loadMediaStructure(postUrl: String): MediaStructure = withContext(Dispatchers.IO) {
        val slug = postUrl.trim().removePrefix("/")
        val targetUrl = "$mainUrl/$slug/__data.json?x-sveltekit-invalidated=01"
        val responseBody = fetchGetSync(targetUrl, "$mainUrl/")

        if (responseBody.isNotEmpty()) {
            try {
                val lines = responseBody.split("\n")
                val chunkLine = lines.firstOrNull { it.trim().startsWith("{\"type\":\"chunk\"") }
                if (chunkLine != null) {
                    val chunkJson = JSONObject(chunkLine)
                    val dataArr = chunkJson.optJSONArray("data")

                    var postContentHtml = ""
                    if (dataArr != null) {
                        for (i in 0 until dataArr.length()) {
                            val obj = dataArr.optJSONObject(i)
                            if (obj != null && obj.has("post_content")) {
                                val contentIdx = obj.optInt("post_content", -1)
                                if (contentIdx != -1) {
                                    postContentHtml = dataArr.optString(contentIdx, "")
                                }
                                break
                            }
                        }
                    }

                    if (postContentHtml.isNotEmpty()) {
                        val episodesList = mutableListOf<EpisodeItem>()
                        val h3Regex = Regex("<h3[^>]*>(.*?)</h3>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                        val linkRegex = Regex("<a[^>]*href=\"(https?://links\\.kmhd\\.[^/]+/file/[^\"]+)\"[^>]*>(.*?)</a>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                        val epLabelRegex = Regex("""(E\d+|Episode\s*\d+)""", RegexOption.IGNORE_CASE)

                        // Parse single episodes inside <h3> tags
                        for (h3Match in h3Regex.findAll(postContentHtml)) {
                            val h3Inner = h3Match.groupValues[1]
                            val plainText = stripHtml(h3Inner)
                            val epMatch = epLabelRegex.find(plainText) ?: continue

                            val epLinks = mutableListOf<Pair<String, String>>()
                            for (aMatch in linkRegex.findAll(h3Inner)) {
                                val href = aMatch.groupValues[1].trim()
                                val qualityText = stripHtml(aMatch.groupValues[2]).ifEmpty { "HD" }
                                epLinks.add(Pair(qualityText, href))
                            }

                            if (epLinks.isNotEmpty()) {
                                val epName = epMatch.value.uppercase()
                                episodesList.add(EpisodeItem(name = epName, rawGenerateLinks = epLinks))
                            }
                        }

                        if (episodesList.isNotEmpty()) {
                            return@withContext MediaStructure(
                                isSeries = true,
                                rawMovieLinks = emptyList(),
                                seasons = listOf(SeasonItem(name = "Episodes", episodes = episodesList))
                            )
                        }

                        // Fallback: Single Movie Links (e.g. inside <h2> or anywhere in post_content)
                        val movieLinks = mutableListOf<Pair<String, String>>()
                        for (aMatch in linkRegex.findAll(postContentHtml)) {
                            val href = aMatch.groupValues[1].trim()
                            val qualityText = stripHtml(aMatch.groupValues[2]).ifEmpty { "HD" }
                            movieLinks.add(Pair(qualityText, href))
                        }

                        if (movieLinks.isNotEmpty()) {
                            return@withContext MediaStructure(
                                isSeries = false,
                                rawMovieLinks = movieLinks,
                                seasons = emptyList()
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        return@withContext MediaStructure(false, emptyList(), emptyList())
    }

    // 5. Resolve Direct Link (kmhd -> touchme -> hubcloud -> var url -> pixeldrain direct)
    override suspend fun resolveDirectLink(generateUrl: String): StreamItem? = withContext(Dispatchers.IO) {
        try {
            // Step 1: Extract file ID from https://links.kmhd.me/file/LIAR_a97be3da
            val fileId = generateUrl.substringAfter("/file/", "").substringBefore("?").trim()
            if (fileId.isEmpty()) return@withContext null

            val kmhdHost = Regex("^(https?://[^/]+)").find(generateUrl)?.groupValues?.get(1) ?: "https://links.kmhd.me"
            val touchMeUrl = "$kmhdHost/api/touchme/$fileId?c=hubdrive_res"

            // Step 2: POST request to touchme API
            val postReq = Request.Builder()
                .url(touchMeUrl)
                .post("".toRequestBody(null))
                .header("User-Agent", userAgent)
                .header("Referer", generateUrl)
                .build()

            val postResp = httpClient.newCall(postReq).execute()
            val postBody = postResp.body?.string() ?: ""
            postResp.close()

            val linkId = JSONObject(postBody).optString("linkId", "").trim()
            if (linkId.isEmpty()) return@withContext null

            // Step 3: GET request to HubCloud linkId (follows redirects automatically)
            val hubHtml = fetchGetSync(linkId, "$kmhdHost/")
            if (hubHtml.isEmpty()) return@withContext null

            // Step 4: Ignore <a id="download">, extract JS `var url = '...'`
            val varUrlMatch = Regex("""var\s+url\s*=\s*['"]([^'"]+)['"]""").find(hubHtml)
            val gamerUrl = varUrlMatch?.groupValues?.get(1)?.trim() ?: ""
            if (gamerUrl.isEmpty()) return@withContext null

            // Step 5: GET request to gamerxyt URL (follows multiple redirects)
            val finalHtml = fetchGetSync(gamerUrl, linkId)
            if (finalHtml.isEmpty()) return@withContext null

            // Step 6: Extract `var pxl = "https://pixeldrain.dev/u/..."`
            val pxlMatch = Regex("""var\s+pxl\s*=\s*['"](https?://[^'"]+/u/([a-zA-Z0-9_-]+))['"]""").find(finalHtml)
                ?: Regex("""(https?://pixeldrain\.[^/'"]+/u/([a-zA-Z0-9_-]+))""").find(finalHtml)

            if (pxlMatch != null) {
                val fullPxlUrl = pxlMatch.groupValues[1]
                val pxlId = pxlMatch.groupValues[2]
                val pxlHost = Regex("^(https?://[^/]+)").find(fullPxlUrl)?.groupValues?.get(1) ?: "https://pixeldrain.dev"

                // Step 7: Convert to direct download/stream URL
                val directUrl = "$pxlHost/api/file/$pxlId?download"

                return@withContext StreamItem(
                    quality = "HD",
                    streamUrl = directUrl,
                    headers = mapOf(
                        "User-Agent" to userAgent,
                        "Referer" to "$pxlHost/"
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext null
    }
}
