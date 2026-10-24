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

class XMishtiProvider : MainAPI {
    override var name = "XMishti"
    override var mainUrl = "https://www.xmishti.com"

    private val userAgent = "Mozilla/5.0 (Linux; Android 16; 23090RA98I Build/BP2A.250605.031.A3) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/155.0.8059.43 Mobile Safari/537.36"

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    // JSON কনফিগে www ছাড়া লিংক থাকলেও যেন www সহ কাজ করে
    private fun getBaseUrl(): String {
        val clean = mainUrl.trim().trimEnd('/')
        return if (clean.contains("://xmishti.com")) {
            clean.replace("://xmishti.com", "://www.xmishti.com")
        } else if (clean.isEmpty()) {
            "https://www.xmishti.com"
        } else {
            clean
        }
    }

    // হুবহু তোমার curl -s -H 'user-agent: ...' এর মতো কাজ করবে
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

    // হুবহু তোমার grep কমান্ডের মতো Link, Title এবং Image বের করার ফাংশন
    private fun parseItemsFromRawHtml(html: String, base: String): List<MovieItem> {
        val list = mutableListOf<MovieItem>()
        
        // তোমার grep প্যাটার্ন: <a href="(.../video/...)" title="(...)" ... <img ... src="(....jpg)"
        val itemRegex = Regex(
            """<a\s+href="([^"]+/video/[^"]+)"\s+title="([^"]+)"[\s\S]*?<img[^>]+src="([^"]+\.jpg)"""",
            RegexOption.IGNORE_CASE
        )

        for (match in itemRegex.findAll(html)) {
            val rawUrl = match.groupValues[1].trim()
            val rawTitle = match.groupValues[2].trim()
                .replace("&#039;", "'")
                .replace("&quot;", "\"")
                .replace("&amp;", "&")
            val rawImg = match.groupValues[3].trim()

            val fullUrl = if (rawUrl.startsWith("http")) rawUrl else "$base$rawUrl"
            val fullImg = when {
                rawImg.startsWith("http") -> rawImg
                rawImg.startsWith("//") -> "https:$rawImg"
                else -> "$base$rawImg"
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

        // তোমার নির্দেশনা অনুযায়ী: 4000/4053/4053.mp4 পাথ বের করে https://cdn.xmishti.com/ বসানো
        val pathRegex = Regex("""get_file/\d+/[a-zA-Z0-9]+/(\d+/\d+/\d+\.mp4)""")
        val match = pathRegex.find(html) ?: Regex("""/(\d+/\d+/\d+\.mp4)""").find(html)

        if (match != null) {
            val filePath = match.groupValues[1]
            val directStreamUrl = "https://cdn.xmishti.com/$filePath"

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
