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
        return when {
            trimmed.startsWith("http") -> trimmed
            trimmed.startsWith("//") -> "https:$trimmed"
            trimmed.startsWith("/") -> "$base$trimmed"
            else -> "$base/$trimmed"
        }
    }

    // হোমপেজের (<article ... class="item movies">) HTML পার্স করার ফাংশন
    private fun parseMainPageItems(html: String, base: String): List<MovieItem> {
        val list = mutableListOf<MovieItem>()

        val articleRegex = Regex(
            """<article[^>]*class="[^"]*item[^"]*"[^>]*>([\s\S]*?)</article>""",
            RegexOption.IGNORE_CASE
        )
        val imgRegex = Regex("""<img[^>]+src="([^"]+)"[^>]*>""", RegexOption.IGNORE_CASE)
        val qualityRegex = Regex("""<span\s+class="quality"[^>]*>([^<]+)</span>""", RegexOption.IGNORE_CASE)
        val titleLinkRegex = Regex("""<h3>\s*<a\s+href="([^"]+)"[^>]*>([\s\S]*?)</a>\s*</h3>""", RegexOption.IGNORE_CASE)
        val fallbackLinkRegex = Regex("""<a\s+href="([^"]+)"[^>]*>""", RegexOption.IGNORE_CASE)
        val altRegex = Regex("""alt="([^"]+)"""", RegexOption.IGNORE_CASE)

        for (match in articleRegex.findAll(html)) {
            val block = match.groupValues[1]

            val rawImg = imgRegex.find(block)?.groupValues?.get(1)?.trim().orEmpty()
            val qualityText = qualityRegex.find(block)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }

            val titleLinkMatch = titleLinkRegex.find(block)
            val rawUrl = titleLinkMatch?.groupValues?.get(1)?.trim()
                ?: fallbackLinkRegex.find(block)?.groupValues?.get(1)?.trim()
                .orEmpty()

            val rawTitle = titleLinkMatch?.groupValues?.get(2)?.replace(Regex("<[^>]*>"), "")?.trim()
                ?: altRegex.find(block)?.groupValues?.get(1)?.trim()
                .orEmpty()

            if (rawUrl.isNotEmpty() && rawTitle.isNotEmpty()) {
                val fullUrl = fixUrl(rawUrl, base)
                // TMDB ইমেজ আরও পরিষ্কার দেখানোর জন্য w185 থেকে w342 করা হলো
                val fullImg = fixUrl(rawImg, base).replace("/w185/", "/w342/")

                list.add(
                    MovieItem(
                        title = decodeHtmlEntities(rawTitle),
                        image = fullImg,
                        url = fullUrl,
                        isPoster = true,
                        referer = "$base/",
                        quality = qualityText
                    )
                )
            }
        }
        return list.distinctBy { it.url }
    }

    // সার্চ রেজাল্টের (<div class="result-item">) HTML পার্স করার ফাংশন
    private fun parseSearchItems(html: String, base: String): List<MovieItem> {
        val list = mutableListOf<MovieItem>()

        val resultBlockRegex = Regex(
            """<div\s+class="result-item"[^>]*>[\s\S]*?<article[^>]*>([\s\S]*?)</article>""",
            RegexOption.IGNORE_CASE
        )
        val imgRegex = Regex("""<img[^>]+src="([^"]+)"[^>]*>""", RegexOption.IGNORE_CASE)
        val titleLinkRegex = Regex("""<div\s+class="title"[^>]*>\s*<a\s+href="([^"]+)"[^>]*>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE)
        val fallbackLinkRegex = Regex("""<a\s+href="([^"]+)"[^>]*>""", RegexOption.IGNORE_CASE)
        val altRegex = Regex("""alt="([^"]+)"""", RegexOption.IGNORE_CASE)

        for (match in resultBlockRegex.findAll(html)) {
            val block = match.groupValues[1]

            val rawImg = imgRegex.find(block)?.groupValues?.get(1)?.trim().orEmpty()
            val titleMatch = titleLinkRegex.find(block)

            val rawUrl = titleMatch?.groupValues?.get(1)?.trim()
                ?: fallbackLinkRegex.find(block)?.groupValues?.get(1)?.trim()
                .orEmpty()

            val rawTitle = titleMatch?.groupValues?.get(2)?.replace(Regex("<[^>]*>"), "")?.trim()
                ?: altRegex.find(block)?.groupValues?.get(1)?.trim()
                .orEmpty()

            if (rawUrl.isNotEmpty() && rawTitle.isNotEmpty()) {
                val fullUrl = fixUrl(rawUrl, base)
                // সার্চের ছোট w92 ইমেজকে হাই-রেজোলিউশন পোস্টারে রূপান্তর
                val fullImg = fixUrl(rawImg, base).replace("/w92/", "/w342/")

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

        // যদি কোনো কারণে সার্চ পেজে সাধারণ আইটেম স্ট্রাকচার থাকে তবে ফলব্যাক হিসেবে কাজ করবে
        if (list.isEmpty()) {
            return parseMainPageItems(html, base)
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
        return parseMainPageItems(html, base)
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
        return parseSearchItems(html, base)
    }

    override suspend fun loadMediaStructure(postUrl: String): MediaStructure {
        val html = fetchHtml(postUrl)
        val rawLinks = mutableListOf<Pair<String, String>>()

        // শুধুমাত্র https://tmbcloud.dev এর ডাউনলোড বাটনগুলো খুঁজে বের করার Regex
        val tmbButtonRegex = Regex(
            """<a\s+href="(https?://[^"]*tmbcloud\.dev/[^"]+)"[^>]*>([\s\S]*?)</a>""",
            RegexOption.IGNORE_CASE
        )

        for (match in tmbButtonRegex.findAll(html)) {
            val linkUrl = match.groupValues[1].trim()
            val innerHtml = match.groupValues[2]

            // বাটনের ভেতরের লেখা থেকে "DOWNLOAD" শব্দটি বাদ দিয়ে শুধু কোয়ালিটি (যেমন: 480p, 720p, 1080p HEVC) রাখা হলো
            val buttonText = innerHtml.replace(Regex("<[^>]*>"), " ")
                .replace(Regex("(?i)\\bdownload\\b"), "")
                .replace(Regex("\\s+"), " ")
                .trim()

            val qualityLabel = buttonText.ifEmpty { "HD" }

            // লিংক এবং কোয়ালিটি একসাথে পাঠানো হচ্ছে যেন resolveDirectLink-এ সঠিক কোয়ালিটি নাম দেখায়
            rawLinks.add(Pair(qualityLabel, "$linkUrl|||$qualityLabel"))
        }

        val distinctLinks = rawLinks.distinctBy { it.second.substringBefore("|||") }

        return MediaStructure(
            isSeries = false,
            rawMovieLinks = distinctLinks,
            seasons = emptyList()
        )
    }

    override suspend fun resolveDirectLink(generateUrl: String): StreamItem? {
        val base = getBaseUrl()
        val streamUrl = generateUrl.substringBefore("|||").trim()
        val qualityLabel = generateUrl.substringAfter("|||", "HD").trim().ifEmpty { "HD" }

        if (streamUrl.isEmpty()) return null

        return StreamItem(
            quality = qualityLabel,
            streamUrl = streamUrl,
            headers = mapOf(
                "Referer" to "$base/",
                "User-Agent" to userAgent
            )
        )
    }
}
