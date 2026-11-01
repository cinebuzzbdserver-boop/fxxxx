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

class MovieBaazProvider : MainAPI {
    override var name = "MovieBaaz"
    override var mainUrl = "https://moviebaaz.baby"

    private var userAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Mobile Safari/537.36"

    private var baseClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private fun getActiveUserAgent(): String {
        return System.getProperty("moviefy.cf.userAgent")?.takeIf { it.isNotBlank() } ?: userAgent
    }

    private fun getBaseUrl(): String {
        val clean = mainUrl.trim().trimEnd('/')
        return if (clean.isEmpty()) "https://moviebaaz.baby" else clean
    }

    private suspend fun fetchHtml(url: String, referer: String? = null): String = withContext(Dispatchers.IO) {
        try {
            val reqBuilder = Request.Builder()
                .url(url)
                .header("User-Agent", getActiveUserAgent())
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            if (!referer.isNullOrEmpty()) {
                reqBuilder.header("Referer", referer)
            }

            baseClient.newCall(reqBuilder.build()).execute().use { response ->
                response.body?.string().orEmpty()
            }
        } catch (_: Exception) {
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
        if (trimmed.isEmpty() || trimmed.contains("placeholder.com", ignoreCase = true)) return ""

        val resolved = when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
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

    private fun extractCardImage(cardHtml: String, base: String): String {
        val tmdbRegex = Regex("""(?:https?://image\.tmdb\.org)?/t/p/(?:[a-zA-Z0-9_]+/)?([a-zA-Z0-9_-]+\.(?:jpg|jpeg|png|webp))""", RegexOption.IGNORE_CASE)
        val tmdbMatch = tmdbRegex.find(cardHtml)
        if (tmdbMatch != null) {
            val fileName = tmdbMatch.groupValues[1].trim()
            return "https://image.tmdb.org/t/p/original/$fileName"
        }

        val imgSrcRegex = Regex("""<img\b[^>]*?\bsrc=['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)
        val srcMatch = imgSrcRegex.find(cardHtml)?.groupValues?.get(1)?.trim().orEmpty()

        if (srcMatch.isNotEmpty() &&
            !srcMatch.contains("placeholder.com", ignoreCase = true) &&
            !srcMatch.startsWith("data:image", ignoreCase = true)
        ) {
            return fixUrl(srcMatch, base)
        }

        return ""
    }

    private fun parseMovieGrid(html: String, base: String): List<MovieItem> {
        val list = mutableListOf<MovieItem>()

        val articleRegex = Regex("""<article\b[^>]*class=['"][^'"]*mbx-c[^'"]*['"][^>]*>([\s\S]*?)</article>""", RegexOption.IGNORE_CASE)
        val linkRegex = Regex("""<a\b[^>]*class=['"][^'"]*mbx-c-p[^'"]*['"][^>]*href=['"]([^'"]+)['"][^>]*title=['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)
        val fallbackLinkRegex = Regex("""<h3>\s*<a\b[^>]*href=['"]([^'"]+)['"][^>]*>([\s\S]*?)</a>\s*</h3>""", RegexOption.IGNORE_CASE)

        for (match in articleRegex.findAll(html)) {
            val cardHtml = match.groupValues[1]

            var postUrl = ""
            var title = ""

            val linkMatch = linkRegex.find(cardHtml)
            if (linkMatch != null) {
                postUrl = linkMatch.groupValues[1].trim()
                title = linkMatch.groupValues[2].trim()
            } else {
                val fbMatch = fallbackLinkRegex.find(cardHtml)
                if (fbMatch != null) {
                    postUrl = fbMatch.groupValues[1].trim()
                    title = fbMatch.groupValues[2].replace(Regex("<[^>]*>"), "").trim()
                }
            }

            val poster = extractCardImage(cardHtml, base)

            if (postUrl.isNotEmpty() && title.isNotEmpty()) {
                val fullUrl = fixUrl(postUrl, base)
                list.add(
                    MovieItem(
                        title = decodeHtmlEntities(title),
                        image = poster,
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
            "$base/movies-and-series/"
        } else {
            "$base/movies-and-series/page/$page/"
        }
        val html = fetchHtml(targetUrl, "$base/")
        return parseMovieGrid(html, base)
    }

    override suspend fun search(query: String, page: Int): List<MovieItem> = withContext(Dispatchers.IO) {
        val base = getBaseUrl()
        val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8")
        val targetUrl = if (page <= 1) {
            "$base/?s=$encodedQuery"
        } else {
            "$base/page/$page/?s=$encodedQuery"
        }

        val html = fetchHtml(targetUrl, "$base/")
        parseMovieGrid(html, base)
    }

    override suspend fun loadMediaStructure(postUrl: String): MediaStructure = withContext(Dispatchers.IO) {
        val base = getBaseUrl()
        val html = fetchHtml(postUrl, "$base/")
        val rawLinks = mutableListOf<Pair<String, String>>()

        val blockRegex = Regex("""class=['"]mbx-dl-q['"][^>]*>([\s\S]*?)</div>[\s\S]*?class=['"]mbx-dl-i['"][^>]*>([\s\S]*?)</div>[\s\S]*?class=['"]mbx-dl-btn['"][^>]*>[\s\S]*?href=['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)

        for (match in blockRegex.findAll(html)) {
            val qHtml = match.groupValues[1]
            val iHtml = match.groupValues[2]
            val link = match.groupValues[3].trim()

            val quality = qHtml.replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim()
            val titleMatch = Regex("""<h4[^>]*>([\s\S]*?)</h4>""", RegexOption.IGNORE_CASE).find(iHtml)
            val dlTitle = titleMatch?.groupValues?.get(1)?.replace(Regex("<[^>]*>"), " ")?.trim()?.replace(Regex("\\s+"), " ").orEmpty()

            val sizeMatch = Regex("""class=['"]mbx-size['"][^>]*>([\s\S]*?)</span>""", RegexOption.IGNORE_CASE).find(iHtml)
            val size = sizeMatch?.groupValues?.get(1)?.replace(Regex("<[^>]*>"), " ")?.trim()?.replace(Regex("\\s+"), " ").orEmpty()

            if (link.isNotEmpty()) {
                val labelBuilder = StringBuilder()
                if (quality.isNotEmpty()) {
                    labelBuilder.append("[$quality] ")
                }
                if (dlTitle.isNotEmpty()) {
                    labelBuilder.append(dlTitle)
                }
                if (size.isNotEmpty()) {
                    labelBuilder.append(" ($size)")
                }

                val finalLabel = if (labelBuilder.isNotEmpty()) labelBuilder.toString().trim() else "Download"
                rawLinks.add(Pair(decodeHtmlEntities(finalLabel), fixUrl(link, base)))
            }
        }

        if (rawLinks.isEmpty()) {
            val fallbackBtnRegex = Regex("""<a\b[^>]*href=['"](https?://moviebaaz\.baby/links/[^'"]+)['"][^>]*>[\s\S]*?<button""", RegexOption.IGNORE_CASE)
            var count = 1
            for (match in fallbackBtnRegex.findAll(html)) {
                val link = match.groupValues[1].trim()
                rawLinks.add(Pair("Download Link $count", link))
                count++
            }
        }

        val isSeries = postUrl.contains("/tvshows/", ignoreCase = true) || html.contains("mbx-k-series", ignoreCase = true)

        MediaStructure(
            isSeries = isSeries,
            rawMovieLinks = rawLinks.distinctBy { it.second },
            seasons = emptyList()
        )
    }

    override suspend fun resolveDirectLink(generateUrl: String): StreamItem? = withContext(Dispatchers.IO) {
        if (generateUrl.isBlank()) return@withContext null

        try {
            val base = getBaseUrl()
            
            // ১. `/links/...` পেজ থেকে gdmax এর লিংকের HTML ফেচ করা
            val intermediateHtml = fetchHtml(generateUrl, "$base/")

            // ২. gdmax লিংক ও slug বের করার রেজেক্স
            val gdMaxRegex = Regex("""https?://[^'"\s>]*gdmax\.[^'"\s>]*/file/([a-zA-Z0-9_-]+)""", RegexOption.IGNORE_CASE)
            val gdMatch = gdMaxRegex.find(intermediateHtml)
            
            val fileSlug = gdMatch?.groupValues?.get(1)?.trim().orEmpty()

            if (fileSlug.isEmpty()) return@withContext null

            // ৩. gdmax.xyz/gofile/slug ফরম্যাটে কনভার্ট করা
            val targetUrl = "https://gdmax.xyz/gofile/$fileSlug"
            val activeUa = getActiveUserAgent()
            
            // রেফারার হিসেবে ঠিক gdmax.xyz ব্যবহার করা (যেমনটা আপনার রিকোয়ারমেন্ট ছিল)
            val gdReferer = "https://gdmax.xyz/"

            var finalStreamUrl = targetUrl
            
            // ৪. রিকোয়েস্ট করে রিডাইরেকশন ফলো করা
            val streamReq = Request.Builder()
                .url(targetUrl)
                .header("User-Agent", activeUa)
                .header("Referer", gdReferer)
                .build()

            baseClient.newCall(streamReq).execute().use { response ->
                // রিডাইরেক্ট হয়ে যে লিংকে যাবে (যেমন gofile.io/d/...) সেটি কালেক্ট করা
                finalStreamUrl = response.request.url.toString()
            }

            // ৫. যদি gofile.io তে রিডাইরেক্ট হয়ে থাকে, তবে ডোমেইন রিপ্লেস করে tg.blmbd.workers.dev বসানো
            if (finalStreamUrl.contains("gofile.io", ignoreCase = true)) {
                finalStreamUrl = finalStreamUrl.replace(Regex("""https?://(?:www\.)?gofile\.io""", RegexOption.IGNORE_CASE), "https://tg.blmbd.workers.dev")
            }

            StreamItem(
                quality = "Direct Stream",
                streamUrl = finalStreamUrl,
                headers = mapOf(
                    "User-Agent" to activeUa
                )
            )
        } catch (_: Throwable) {
            null
        }
    }
}
