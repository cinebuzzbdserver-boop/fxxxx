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
        if (trimmed.isEmpty()) return ""
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

    private fun extractImageFromBlock(block: String, base: String): String {
        val posterDivRegex = Regex("""<div[^>]*class="[^"]*poster[^"]*"[^>]*>([\s\S]*?)</div>""", RegexOption.IGNORE_CASE)
        val targetArea = posterDivRegex.find(block)?.groupValues?.get(1) ?: block

        val dataSrcMatch = Regex("""<img[^>]+data-src="([^"]+)"""", RegexOption.IGNORE_CASE).find(targetArea)
        val srcMatch = Regex("""<img[^>]+src="([^"]+)"""", RegexOption.IGNORE_CASE).find(targetArea)

        val rawImg = dataSrcMatch?.groupValues?.get(1)?.takeIf { !it.startsWith("data:image") }
            ?: srcMatch?.groupValues?.get(1)?.takeIf { !it.startsWith("data:image") }
            ?: ""

        return fixUrl(rawImg, base)
    }

    // স্লাইডার ও Featured বাদ দিয়ে ১ নম্বর আইটেম থেকে শুরু করে সব রিসেন্টলি অ্যাডেড পোস্টার পার্স করার ফাংশন
    private fun parseMainPageItems(html: String, base: String): List<MovieItem> {
        val list = mutableListOf<MovieItem>()

        // সাইডবার (Recent Update widget) বাদ দেওয়া হচ্ছে যেন নিচের ছোট থাম্বনেইল না আসে
        val sidebarIndex = html.indexOf("""<div class="sidebar""", ignoreCase = true)
        val cleanHtml = if (sidebarIndex != -1) html.substring(0, sidebarIndex) else html

        // প্রতিটি <article ...> ব্লক আলাদাভাবে চেক করা হচ্ছে
        val articleRegex = Regex("""<article([^>]*)>([\s\S]*?)</article>""", RegexOption.IGNORE_CASE)
        val h3LinkRegex = Regex("""<h3[^>]*>\s*<a[^>]+href="([^"]+)"[^>]*>([\s\S]*?)</a>\s*</h3>""", RegexOption.IGNORE_CASE)
        val anyLinkRegex = Regex("""<a[^>]+href="([^"]+)"[^>]*>""", RegexOption.IGNORE_CASE)
        val altRegex = Regex("""alt="([^"]+)"""", RegexOption.IGNORE_CASE)

        for (match in articleRegex.findAll(cleanHtml)) {
            val attrs = match.groupValues[1]
            val block = match.groupValues[2]

            // ১. যদি এটি Featured সেকশনের আইটেম হয় (post-featured- किंवा class="featu"), তবে স্কিপ করবে
            if (attrs.contains("post-featured", ignoreCase = true)) continue
            if (block.contains("""class="featu"""", ignoreCase = true)) continue
            if (block.contains("""class="data dfeatur"""", ignoreCase = true)) continue

            // ২. স্লাইডারের আইটেমে <div class="image"> থাকে আর আসল রিসেন্ট মুভিতে <div class="poster"> থাকে
            if (!block.contains("""class="poster"""", ignoreCase = true)) continue

            val fullImg = extractImageFromBlock(block, base)
            if (fullImg.isEmpty()) continue

            val h3Match = h3LinkRegex.find(block)
            val rawUrl = h3Match?.groupValues?.get(1)?.trim()
                ?: anyLinkRegex.find(block)?.groupValues?.get(1)?.trim()
                .orEmpty()

            val rawTitle = h3Match?.groupValues?.get(2)?.replace(Regex("<[^>]*>"), "")?.trim()
                ?: altRegex.find(block)?.groupValues?.get(1)?.trim()
                .orEmpty()

            if (rawUrl.isNotEmpty() && rawTitle.isNotEmpty()) {
                val fullUrl = fixUrl(rawUrl, base)

                list.add(
                    MovieItem(
                        title = decodeHtmlEntities(rawTitle),
                        image = fullImg,
                        url = fullUrl,
                        isPoster = true,
                        referer = null
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
        val titleLinkRegex = Regex("""<div\s+class="title"[^>]*>\s*<a[^>]+href="([^"]+)"[^>]*>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE)
        val fallbackLinkRegex = Regex("""<a[^>]+href="([^"]+)"[^>]*>""", RegexOption.IGNORE_CASE)
        val altRegex = Regex("""alt="([^"]+)"""", RegexOption.IGNORE_CASE)

        for (match in resultBlockRegex.findAll(html)) {
            val block = match.groupValues[1]

            val fullImg = extractImageFromBlock(block, base)
            val titleMatch = titleLinkRegex.find(block)

            val rawUrl = titleMatch?.groupValues?.get(1)?.trim()
                ?: fallbackLinkRegex.find(block)?.groupValues?.get(1)?.trim()
                .orEmpty()

            val rawTitle = titleMatch?.groupValues?.get(2)?.replace(Regex("<[^>]*>"), "")?.trim()
                ?: altRegex.find(block)?.groupValues?.get(1)?.trim()
                .orEmpty()

            if (rawUrl.isNotEmpty() && rawTitle.isNotEmpty()) {
                val fullUrl = fixUrl(rawUrl, base)

                list.add(
                    MovieItem(
                        title = decodeHtmlEntities(rawTitle),
                        image = fullImg,
                        url = fullUrl,
                        isPoster = true,
                        referer = null
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
        val result = mutableListOf<MovieItem>()

        if (page <= 1) {
            // প্রথমে মূল হোমপেজ ও /movies/ উভয়ই চেক করা হচ্ছে যেন কোনোভাবেই ১ নম্বর রিসেন্ট মুভি বাদ না পড়ে
            val homeHtml = fetchHtml("$base/")
            result.addAll(parseMainPageItems(homeHtml, base))

            if (result.size < 10) {
                val moviesHtml = fetchHtml("$base/movies/")
                result.addAll(parseMainPageItems(moviesHtml, base))
            }
        } else {
            val pageHtml = fetchHtml("$base/movies/page/$page/")
            result.addAll(parseMainPageItems(pageHtml, base))
        }

        return result.distinctBy { it.url }
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
