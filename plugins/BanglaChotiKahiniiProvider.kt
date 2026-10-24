package com.moviefy.stream.providers

import com.moviefy.stream.ui.screens.MainAPI
import com.moviefy.stream.ui.screens.MediaStructure
import com.moviefy.stream.ui.screens.MovieItem
import com.moviefy.stream.ui.screens.StreamItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class BanglaChotiKahiniiProvider : MainAPI {
    override var name = "BanglaChotiKahinii"
    override var mainUrl = "https://www.banglachotikahinii.com/videos"

    private val userAgent = "Mozilla/5.0 (Linux; Android 16; 23090RA98I Build/BP2A.250605.031.A3) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/155.0.8059.43 Mobile Safari/537.36"

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private fun getBaseUrl(): String {
        var clean = mainUrl.trim().trimEnd('/')
        if (clean.contains("://banglachotikahinii.com")) {
            clean = clean.replace("://banglachotikahinii.com", "://www.banglachotikahinii.com")
        }
        if (clean.isEmpty()) {
            return "https://www.banglachotikahinii.com/videos"
        }
        if (!clean.endsWith("/videos")) {
            clean = "$clean/videos"
        }
        return clean
    }

    private suspend fun fetchHtml(url: String): String = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .build()

            client.newCall(request).execute().use { response ->
                response.body?.string().orEmpty()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
    }

    // তোমার টার্মিনালের সফল কার্ল ও গ্রিপ লজিক অনুযায়ী নিখুঁত পার্সিং
    private fun parseItemsFromRawHtml(html: String, base: String): List<MovieItem> {
        val list = mutableListOf<MovieItem>()

        // থাম্ব ব্লক বা প্রতিটি আইটেম আলাদা করে নেওয়া
        val itemBlocks = html.split(Regex("""<div[^>]*class="[^"]*thumb[^"]*"[^>]*>""", RegexOption.IGNORE_CASE))

        for (block in itemBlocks) {
            val hrefMatch = Regex("""href="(https?://[^"]+/videos/[^"]+|/[^"]+/videos/[^"]+)"""", RegexOption.IGNORE_CASE).find(block)
            val titleMatch = Regex("""title="([^"]+)"(?:\s+data-rt|[^>]*data-rt)""", RegexOption.IGNORE_CASE).find(block) 
                ?: Regex("""title="([^"]+)"""", RegexOption.IGNORE_CASE).find(block)
            val imgMatch = Regex("""src="(https?://[^"]+/contents/[^"]+\.jpg|/[^"]+/contents/[^"]+\.jpg)"|data-webp="(https?://[^"]+\.jpg|/[^"]+\.jpg)"""", RegexOption.IGNORE_CASE).find(block)

            if (hrefMatch != null && titleMatch != null) {
                var rawUrl = hrefMatch.groupValues[1].trim()
                val rawTitle = titleMatch.groupValues[1].trim()
                    .replace("&#039;", "'")
                    .replace("&quot;", "\"")
                    .replace("&amp;", "&")

                var rawImg = ""
                if (imgMatch != null) {
                    rawImg = (imgMatch.groupValues[1].ifEmpty { imgMatch.groupValues[2] }).trim()
                }

                if (rawUrl.isEmpty() || rawTitle.isEmpty()) continue

                val rootDomain = "https://www.banglachotikahinii.com"
                val fullUrl = if (rawUrl.startsWith("http")) rawUrl else "$rootDomain$rawUrl"
                val fullImg = when {
                    rawImg.startsWith("http") -> rawImg
                    rawImg.startsWith("//") -> "https:$rawImg"
                    rawImg.startsWith("/") -> "$rootDomain$rawImg"
                    else -> "$rootDomain/$rawImg"
                }

                list.add(
                    MovieItem(
                        title = rawTitle,
                        image = fullImg,
                        url = fullUrl,
                        isPoster = false,
                        referer = "$base/"
                    )
                )
            }
        }
        return list.distinctBy { it.url }
    }

    override suspend fun getMainPage(page: Int): List<MovieItem> {
        val base = getBaseUrl()
        val targetUrl = "$base/?mode=async&function=get_block&block_id=list_videos_most_recent_videos&sort_by=post_date&from=$page"
        val html = fetchHtml(targetUrl)
        return parseItemsFromRawHtml(html, base)
    }

    override suspend fun search(query: String, page: Int): List<MovieItem> {
        val base = getBaseUrl()
        val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
        val targetUrl = "$base/search/$encodedQuery/?mode=async&function=get_block&block_id=list_videos_videos_list_search_result&q=$encodedQuery&category_ids=&sort_by=&from_videos=$page"
        val html = fetchHtml(targetUrl)
        return parseItemsFromRawHtml(html, base)
    }

    override suspend fun loadMediaStructure(postUrl: String): MediaStructure {
        return MediaStructure(
            isSeries = false,
            rawMovieLinks = listOf(Pair("Direct Stream", postUrl)),
            seasons = emptyList()
        )
    }

    override suspend fun resolveDirectLink(generateUrl: String): StreamItem? {
        val base = getBaseUrl()
        val html = fetchHtml(generateUrl)
        if (html.isEmpty()) return null

        val pathRegex = Regex("""get_file/\d+/[a-zA-Z0-9]+/(\d+/\d+/\d+\.mp4)""")
        val match = pathRegex.find(html) ?: Regex("""/(\d+/\d+/\d+\.mp4)""").find(html)

        if (match != null) {
            val filePath = match.groupValues[1]
            val directStreamUrl = "https://cdn.banglachotikahinii.com/$filePath"

            return StreamItem(
                quality = "HD",
                streamUrl = directStreamUrl,
                headers = mapOf(
                    "Referer" to "$base/",
                    "User-Agent" to userAgent
                )
            )
        }
        return null
    }
}
