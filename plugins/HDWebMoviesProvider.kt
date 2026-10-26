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

        // শুধুমাত্র image.tmdb.org এর ক্ষেত্রে কোয়ালিটি বাড়িয়ে original করে দেওয়া হচ্ছে
        return if (resolved.contains("image.tmdb.org/t/p/")) {
            resolved.replace(Regex("/t/p/[a-zA-Z0-9]+(?:_[a-zA-Z0-9]+)?/"), "/t/p/original/")
        } else {
            resolved
        }
    }

    // স্লাইডার (<div class="image">) এবং Featured সেকশন সম্পূর্ণ বাদ দিয়ে শুধুমাত্র রিসেন্টলি অ্যাডেড (<div class="poster">) পোস্টারগুলো কালেক্ট করা হচ্ছে
    private fun parseMainPageItems(html: String, base: String): List<MovieItem> {
        val list = mutableListOf<MovieItem>()

        // যদি পেজে <div class="items full"> থাকে তবে শুধু তার পরবর্তী অংশ নেওয়া হবে
        val recentContainerStart = html.indexOf("""<div class="items full">""", ignoreCase = true)
        val targetHtml = if (recentContainerStart != -1) {
            val sub = html.substring(recentContainerStart)
            val sidebarStart = sub.indexOf("""<div class="sidebar""", ignoreCase = true)
            if (sidebarStart != -1) sub.substring(0, sidebarStart) else sub
        } else {
            html
        }

        // শুধুমাত্র যেসব article-এর ভেতরে <div class="poster"> আছে সেগুলোই ম্যাচ করবে (স্লাইডারে <div class="image"> থাকে তাই স্লাইডার ১০০% বাদ পড়বে)
        val posterArticleRegex = Regex(
            """<article\s+id="([^"]+)"\s+class="item\s+movies"[^>]*>\s*<div\s+class="poster">\s*<img\s+src="([^"]+)"[^>]*>[\s\S]*?<div\s+class="data">\s*<h3>\s*<a\s+href="([^"]+)"[^>]*>([\s\S]*?)</a>\s*</h3>""",
            RegexOption.IGNORE_CASE
        )

        for (match in posterArticleRegex.findAll(targetHtml)) {
            val articleId = match.groupValues[1].trim()
            // Featured titles সেকশনের ডুপ্লিকেট আইটেম বাদ দেওয়া হচ্ছে
            if (articleId.contains("featured", ignoreCase = true)) continue

            val rawImg = match.groupValues[2].trim()
            val rawUrl = match.groupValues[3].trim()
            val rawTitle = match.groupValues[4].replace(Regex("<[^>]*>"), "").trim()

            if (rawUrl.isNotEmpty() && rawTitle.isNotEmpty() && rawImg.isNotEmpty()) {
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

    // সার্চ পেজের (<div class="result-item">) আইটেম পার্স করার ফাংশন
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
