package com.moviefy.stream.providers

import com.moviefy.stream.ui.screens.MainAPI
import com.moviefy.stream.ui.screens.MediaStructure
import com.moviefy.stream.ui.screens.MovieItem
import com.moviefy.stream.ui.screens.StreamItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
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

        val imgSrcRegex = Regex("""<img\b[^>]*?\bsrc=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
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

        val articleRegex = Regex("""<article\b[^>]*class=["'][^"']*mbx-c[^"']*["'][^>]*>([\s\S]*?)</article>""", RegexOption.IGNORE_CASE)
        val linkRegex = Regex("""<a\b[^>]*class=["'][^"']*mbx-c-p[^"']*["'][^>]*href=["']([^"']+)["'][^>]*title=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        val fallbackLinkRegex = Regex("""<h3>\s*<a\b[^>]*href=["']([^"']+)["'][^>]*>([\s\S]*?)</a>\s*</h3>""", RegexOption.IGNORE_CASE)

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

        val dlBlockRegex = Regex("""<div\b[^>]*class=["'][^"']*mbx-dl[^"']*["'][^>]*>([\s\S]*?)</div>\s*</div>""", RegexOption.IGNORE_CASE)
        val qualityRegex = Regex("""<div\b[^>]*class=["'][^"']*mbx-dl-q[^"']*["'][^>]*>([\s\S]*?)</div>""", RegexOption.IGNORE_CASE)
        val titleRegex = Regex("""<div\b[^>]*class=["'][^"']*mbx-dl-i[^"']*["'][\s\S]*?<h4>([\s\S]*?)</h4>""", RegexOption.IGNORE_CASE)
        val sizeRegex = Regex("""<span\b[^>]*class=["'][^"']*mbx-size[^"']*["'][^>]*>([\s\S]*?)</span>""", RegexOption.IGNORE_CASE)
        val btnRegex = Regex("""<div\b[^>]*class=["'][^"']*mbx-dl-btn[^"']*["'][\s\S]*?<a\b[^>]*href=["']([^"']+)["']""", RegexOption.IGNORE_CASE)

        for (match in dlBlockRegex.findAll(html)) {
            val block = match.groupValues[1]

            val quality = qualityRegex.find(block)?.groupValues?.get(1)?.replace(Regex("<[^>]*>"), " ")?.trim()?.replace(Regex("\\s+"), " ").orEmpty()
            val dlTitle = titleRegex.find(block)?.groupValues?.get(1)?.replace(Regex("<[^>]*>"), " ")?.trim()?.replace(Regex("\\s+"), " ").orEmpty()
            val size = sizeRegex.find(block)?.groupValues?.get(1)?.replace(Regex("<[^>]*>"), " ")?.trim()?.replace(Regex("\\s+"), " ").orEmpty()
            val link = btnRegex.find(block)?.groupValues?.get(1)?.trim().orEmpty()

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
            val intermediateHtml = fetchHtml(generateUrl, "$base/")

            // লিঙ্ক পেজ থেকে gdmax লিঙ্ক এবং slug এক্সট্রাক্ট করা
            val gdMaxRegex = Regex("""https?://[^"'\s]*gdmax\.[^"'\s]*/file/([a-zA-Z0-9_-]+)""", RegexOption.IGNORE_CASE)
            val gdMatch = gdMaxRegex.find(intermediateHtml)

            val originalGdMaxUrl = gdMatch?.value?.trim().orEmpty()
            val fileSlug = gdMatch?.groupValues?.get(1)?.trim().orEmpty()

            val targetUrl = if (fileSlug.isNotEmpty()) {
                "https://gdmax.xyz/gofile/$fileSlug"
            } else {
                generateUrl
            }

            // gdmax লিঙ্ক থেকে Referer ও Origin সেট করা
            val activeUa = getActiveUserAgent()
            val gdReferer = originalGdMaxUrl.ifEmpty { "https://gdmax.org/" }
            val gdOrigin = try {
                val uri = URI(gdReferer)
                "${uri.scheme}://${uri.host}"
            } catch (_: Exception) {
                "https://gdmax.org"
            }

            var finalStreamUrl = targetUrl
            val streamReq = Request.Builder()
                .url(targetUrl)
                .header("User-Agent", activeUa)
                .header("Referer", gdReferer)
                .header("Origin", gdOrigin)
                .header("Accept", "*/*")
                .build()

            // রিডাইরেকশন ফলো করে আসল সরাসরি প্লেযোগ্য ভিডিও লিঙ্ক বের করা
            baseClient.newCall(streamReq).execute().use { response ->
                finalStreamUrl = response.request.url.toString()
            }

            StreamItem(
                quality = "Direct Stream",
                streamUrl = finalStreamUrl,
                headers = mapOf(
                    "User-Agent" to activeUa,
                    "Referer" to gdReferer,
                    "Origin" to gdOrigin
                )
            )
        } catch (_: Throwable) {
            null
        }
    }
}
