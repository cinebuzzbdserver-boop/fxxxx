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

class HDWebMoviesProvider : MainAPI {
    override var name = "HDWebMovies"
    override var mainUrl = "https://ww1.hdwebmovies.pics"

    private val userAgent = "Mozilla/5.0 (Linux; Android 16; 23090RA98I Build/BP2A.250605.031.A3) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/155.0.8059.43 Mobile Safari/537.36"

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private fun getBaseUrl(): String {
        val clean = mainUrl.trim().trimEnd('/')
        return if (clean.isEmpty()) {
            "https://ww1.hdwebmovies.pics"
        } else {
            clean
        }
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

    private fun decodeHtmlEntities(text: String): String {
        return text.trim()
            .replace("&#8211;", "–")
            .replace("&#8212;", "—")
            .replace("&#8217;", "'")
            .replace("&#039;", "'")
            .replace("&quot;", "\"")
            .replace("&amp;", "&")
            .replace("&nbsp;", " ")
    }

    private fun fixUrl(url: String, base: String): String {
        val trimmed = url.trim()
        val resolved = when {
            trimmed.startsWith("http") -> trimmed
            trimmed.startsWith("//") -> "https:$trimmed"
            trimmed.startsWith("/") -> "$base$trimmed"
            else -> "$base/$trimmed"
        }

        return if (resolved.contains("image.tmdb.org/t/p/")) {
            resolved.replace(Regex("/t/p/[a-zA-Z0-9]+(?:_[a-zA-Z0-9]+)?/"), "/t/p/original/")
        } else {
            resolved
        }
    }

    // ইউনিভার্সাল পার্সার যা সব ধরনের আর্টিকেল বা লিস্ট আইটেম থেকে সঠিক ইমেজ, লিংক ও টাইটেল মিলিয়ে বের করবে
    private fun parseItemsFromHtml(html: String, base: String): List<MovieItem> {
        val list = mutableListOf<MovieItem>()

        // বিভিন্ন স্ট্রাকচারের কন্টেন্ট ব্লক (item, result-item, w_item_b) ক্যাপচার করার জন্য
        val blockRegex = Regex(
            """<(?:article|div)[^>]*class="[^"]*(?:item|result-item|w_item_b)[^"]*"[^>]*>([\s\S]*?)</(?:article|div)>""",
            RegexOption.IGNORE_CASE
        )

        val imgRegex = Regex("""<img[^>]+src="([^"]+)"[^>]*>""", RegexOption.IGNORE_CASE)
        val linkRegex = Regex("""<a\s+href="([^"]+)"[^>]*>""", RegexOption.IGNORE_CASE)
        val titleTagRegex = Regex("""<(?:h3|div)\s+class="title"[^>]*>([\s\S]*?)</(?:h3|div)>""", RegexOption.IGNORE_CASE)
        val altRegex = Regex("""alt="([^"]+)"""", RegexOption.IGNORE_CASE)
        val textTagRegex = Regex("""<h3[^>]*>([\s\S]*?)</h3>""", RegexOption.IGNORE_CASE)

        for (match in blockRegex.findAll(html)) {
            val block = match.groupValues[1]

            val rawImg = imgRegex.find(block)?.groupValues?.get(1)?.trim().orEmpty()
            if (rawImg.isEmpty()) continue

            // ব্লকের ভেতরের সঠিক লিংক এবং টাইটেল বের করা
            val allLinks = linkRegex.findAll(block).map { it.groupValues[1].trim() }.toList()
            val rawUrl = allLinks.firstOrNull { it.contains("/movies/") } ?: allLinks.firstOrNull() ?: continue

            val titleMatch = titleTagRegex.find(block)?.groupValues?.get(1)
                ?: textTagRegex.find(block)?.groupValues?.get(1)
                ?: ""

            val cleanTitleFromTag = titleMatch.replace(Regex("<[^>]*>"), "").trim()
            val rawTitle = if (cleanTitleFromTag.isNotEmpty()) {
                cleanTitleFromTag
            } else {
                altRegex.find(block)?.groupValues?.get(1)?.trim().orEmpty()
            }

            if (rawUrl.isNotEmpty() && rawTitle.isNotEmpty()) {
                val fullUrl = fixUrl(rawUrl, base)
                val fullImg = fixUrl(rawImg, base)

                list.add(
                    MovieItem(
                        title = decodeHtmlEntities(rawTitle),
                        image = fullImg,
                        url = fullUrl,
                        isPoster = true,
                        referer = "$base/"
                    )
                )
            }
        }
        return list.distinctBy { it.url }
    }

    override suspend fun getMainPage(page: Int): List<MovieItem> {
        val base = getBaseUrl()
        val targetUrl = if (page <= 1) {
            "$base/movies/"
        } else {
            "$base/movies/page/$page/"
        }
        val html = fetchHtml(targetUrl)
        return parseItemsFromHtml(html, base)
    }

    override suspend fun search(query: String, page: Int): List<MovieItem> {
        val base = getBaseUrl()
        val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8")
        val targetUrl = if (page <= 1) {
            "$base/?s=$encodedQuery"
        } else {
            "$base/page/$page/?s=$encodedQuery"
        }
        val html = fetchHtml(targetUrl)
        return parseItemsFromHtml(html, base)
    }

    override suspend fun loadMediaStructure(postUrl: String): MediaStructure {
        val html = fetchHtml(postUrl)
        val rawLinks = mutableListOf<Pair<String, String>>()

        val tmbButtonRegex = Regex(
            """<a\s+href="(https?://[^"]*tmbcloud\.dev/[^"]+)"[^>]*>([\s\S]*?)</a>""",
            RegexOption.IGNORE_CASE
        )

        for (match in tmbButtonRegex.findAll(html)) {
            val linkUrl = match.groupValues[1].trim()
            val innerHtml = match.groupValues[2]

            val buttonText = innerHtml.replace(Regex("<[^>]*>"), " ")
                .replace(Regex("(?i)\\bdownload\\b"), "")
                .replace(Regex("\\s+"), " ")
                .trim()

            val qualityLabel = buttonText.ifEmpty { "HD" }

            rawLinks.add(Pair(qualityLabel, linkUrl))
        }

        val distinctLinks = rawLinks.distinctBy { it.second }

        return MediaStructure(
            isSeries = false,
            rawMovieLinks = distinctLinks.ifEmpty { listOf(Pair("Direct Stream", postUrl)) },
            seasons = emptyList()
        )
    }

    override suspend fun resolveDirectLink(generateUrl: String): StreamItem? {
        val base = getBaseUrl()
        if (generateUrl.isEmpty()) return null

        return StreamItem(
            quality = "HD",
            streamUrl = generateUrl,
            headers = mapOf(
                "Referer" to "$base/",
                "User-Agent" to userAgent
            )
        )
    }
}
