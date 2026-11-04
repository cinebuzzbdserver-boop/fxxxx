package com.moviefy.stream.providers

import com.moviefy.stream.ui.screens.EpisodeItem
import com.moviefy.stream.ui.screens.MainAPI
import com.moviefy.stream.ui.screens.MediaStructure
import com.moviefy.stream.ui.screens.MovieItem
import com.moviefy.stream.ui.screens.SeasonItem
import com.moviefy.stream.ui.screens.StreamItem
import com.moviefy.stream.ui.screens.TurnstileBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class XDMoviesProvider : MainAPI {
    override var name = "XDMovies"
    override var mainUrl = "https://huh.xdmovies.wtf"

    private val userAgent = "Mozilla/5.0 (Linux; Android 16; 23090RA98I Build/BP2A.250605.031.A3) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/155.0.8059.43 Mobile Safari/537.36"

    private val resolveMutex = Mutex()

    private val baseClient = OkHttpClient.Builder()
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
        return if (clean.isEmpty()) "https://huh.xdmovies.wtf" else clean
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

        val itemRegex = Regex(
            """<a\s+[^>]*href=["']([^"']+)["'][^>]*class=["'][^"']*movie-link[^"']*["'][^>]*>([\s\S]*?)</a>""",
            RegexOption.IGNORE_CASE
        )
        val titleRegex = Regex("""<h3[^>]*>([\s\S]*?)</h3>""", RegexOption.IGNORE_CASE)
        val yearRegex = Regex("""<p[^>]*>(\d{4})</p>""", RegexOption.IGNORE_CASE)

        for (match in itemRegex.findAll(html)) {
            val rawPath = match.groupValues[1].trim()
            val innerCard = match.groupValues[2]

            val rawTitle = titleRegex.find(innerCard)?.groupValues?.get(1)?.replace(Regex("<[^>]*>"), "")?.trim().orEmpty()
            val year = yearRegex.find(innerCard)?.groupValues?.get(1)?.trim()
            val fullImg = extractCardImage(innerCard, base)

            if (rawPath.isNotEmpty() && rawTitle.isNotEmpty()) {
                val fullUrl = fixUrl(rawPath, base)
                val displayTitle = if (!year.isNullOrEmpty() && !rawTitle.contains(year)) {
                    "$rawTitle ($year)"
                } else {
                    rawTitle
                }

                list.add(
                    MovieItem(
                        title = decodeHtmlEntities(displayTitle),
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
            "$base/"
        } else {
            "$base/?page=$page"
        }
        val html = fetchHtml(targetUrl, "$base/")
        return parseMovieGrid(html, base)
    }

    override suspend fun search(query: String, page: Int): List<MovieItem> = withContext(Dispatchers.IO) {
        val base = getBaseUrl()
        val list = mutableListOf<MovieItem>()
        val activeUa = getActiveUserAgent()

        try {
            val tokenUrl = "$base/php/get_token.php"
            val tokenReq = Request.Builder()
                .url(tokenUrl)
                .header("User-Agent", activeUa)
                .header("Referer", "$base/")
                .header("Accept", "application/json, text/javascript, */*; q=0.01")
                .header("X-Requested-With", "XMLHttpRequest")
                .build()

            var authToken = ""
            baseClient.newCall(tokenReq).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (body.isNotEmpty()) {
                    authToken = JSONObject(body).optString("token", "").trim()
                }
            }

            if (authToken.isEmpty()) return@withContext emptyList()

            val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8")
            val searchApiUrl = "$base/php/search_api.php?query=$encodedQuery&fuzzy=true"

            val searchReq = Request.Builder()
                .url(searchApiUrl)
                .header("User-Agent", activeUa)
                .header("Referer", "$base/")
                .header("Accept", "application/json, text/javascript, */*; q=0.01")
                .header("x-auth-token", authToken)
                .header("x-requested-with", "XMLHttpRequest")
                .build()

            val searchRespText = baseClient.newCall(searchReq).execute().use { resp ->
                resp.body?.string().orEmpty()
            }

            if (searchRespText.isNotBlank()) {
                val jsonArray = JSONArray(searchRespText)
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.optJSONObject(i) ?: continue

                    val title = obj.optString("title", "").trim()
                    val path = obj.optString("path", "").trim()
                    val posterPath = obj.optString("poster", "").trim()
                    val year = obj.optString("release_year", "").trim()

                    if (title.isNotEmpty() && path.isNotEmpty()) {
                        val fullUrl = fixUrl(path, base)
                        val fullImg = if (posterPath.isNotEmpty()) {
                            val cleanPoster = if (posterPath.startsWith("/")) posterPath else "/$posterPath"
                            "https://image.tmdb.org/t/p/original$cleanPoster"
                        } else {
                            ""
                        }

                        val displayTitle = if (year.isNotEmpty() && !title.contains(year)) {
                            "$title ($year)"
                        } else {
                            title
                        }

                        list.add(
                            MovieItem(
                                title = decodeHtmlEntities(displayTitle),
                                image = fullImg,
                                url = fullUrl,
                                isPoster = true,
                                referer = "$base/"
                            )
                        )
                    }
                }
            }
        } catch (_: Exception) {
            return@withContext emptyList()
        }

        list.distinctBy { it.url }
    }

    private data class ParsedData(val title: String, val url: String, val size: String)

    override suspend fun loadMediaStructure(postUrl: String): MediaStructure = withContext(Dispatchers.IO) {
        val base = getBaseUrl()
        val html = fetchHtml(postUrl, "$base/")
        val extractedItems = mutableListOf<ParsedData>()

        fun extractByRegex(titleClass: String) {
            val combinedRegex = Regex(
                """<[a-zA-Z0-9]+[^>]*class=["'][^"']*$titleClass[^"']*["'][^>]*>([\s\S]*?)</[a-zA-Z0-9]+>[\s\S]*?<a\s+[^>]*href=["']([^"']+)["'][^>]*>([\s\S]*?)</a>""",
                RegexOption.IGNORE_CASE
            )
            for (match in combinedRegex.findAll(html)) {
                val title = decodeHtmlEntities(match.groupValues[1].replace(Regex("<[^>]*>"), "").trim())
                val url = match.groupValues[2].trim()

                var size = decodeHtmlEntities(match.groupValues[3].replace(Regex("<[^>]*>"), "").replace(Regex("\\s+"), " ").trim())
                if (size.equals("Download", ignoreCase = true)) {
                    size = ""
                }

                if (title.isNotEmpty() && url.isNotEmpty()) {
                    extractedItems.add(ParsedData(title, url, size))
                }
            }
        }

        extractByRegex("episode-title")
        extractByRegex("custom-title")
        extractByRegex("pack-title")

        if (extractedItems.isEmpty()) {
            val fallbackRegex = Regex(
                """<a\s+[^>]*href=["']([^"']+)["'][^>]*class=["'][^"']*(?:download-button|btn)[^"']*["'][^>]*>([\s\S]*?)</a>""",
                RegexOption.IGNORE_CASE
            )
            for (match in fallbackRegex.findAll(html)) {
                val url = match.groupValues[1].trim()
                var innerText = decodeHtmlEntities(match.groupValues[2].replace(Regex("<[^>]*>"), "").replace(Regex("\\s+"), " ").trim())
                if (innerText.equals("Download", ignoreCase = true)) {
                    innerText = ""
                }
                val title = if (innerText.isNotEmpty()) innerText else "Download Link"
                if (url.isNotEmpty()) {
                    extractedItems.add(ParsedData(title, url, ""))
                }
            }
        }

        val distinctItems = extractedItems.distinctBy { it.url }
        val seriesRegex = Regex("""(?i)(?:^|[.\s\-_])[Ss](\d+)(?:[Ee](\d+))?(?:[.\s\-_]|$)""")
        val isSeriesPost = distinctItems.any { seriesRegex.containsMatchIn(it.title) }

        if (isSeriesPost) {
            val seasonCategoryMap = mutableMapOf<String, MutableMap<String, MutableList<Pair<String, String>>>>()

            distinctItems.forEach { item ->
                val match = seriesRegex.find(item.title)
                val seasonName: String
                val epShortName: String

                if (match != null) {
                    val sNum = match.groupValues[1].toIntOrNull() ?: 1
                    val eStr = match.groupValues[2]

                    if (eStr.isEmpty()) {
                        seasonName = "Season $sNum Packs/Zips"
                        epShortName = "S%02d".format(sNum)
                    } else {
                        seasonName = "Season $sNum"
                        val eNum = eStr.toIntOrNull() ?: 1
                        epShortName = "S%02dE%02d".format(sNum, eNum)
                    }
                } else {
                    seasonName = "Extras / Other Files"
                    epShortName = item.title
                }

                val linkLabel = if (item.size.isNotEmpty()) "${item.title} - ${item.size}" else item.title

                seasonCategoryMap.getOrPut(seasonName) { mutableMapOf() }
                    .getOrPut(epShortName) { mutableListOf() }
                    .add(Pair(linkLabel, item.url))
            }

            val sortedSeasonNames = seasonCategoryMap.keys.sortedWith(Comparator { a, b ->
                val numA = Regex("""\d+""").find(a)?.value?.toIntOrNull() ?: 999
                val numB = Regex("""\d+""").find(b)?.value?.toIntOrNull() ?: 999
                if (numA != numB) {
                    numA.compareTo(numB)
                } else {
                    a.compareTo(b)
                }
            })

            val seasonsList = sortedSeasonNames.map { sName ->
                val epMap = seasonCategoryMap[sName]!!
                val episodesList = epMap.keys.sorted().map { epName ->
                    EpisodeItem(name = epName, rawGenerateLinks = epMap[epName]!!)
                }
                SeasonItem(name = sName, episodes = episodesList)
            }

            MediaStructure(
                isSeries = true,
                rawMovieLinks = emptyList(),
                seasons = seasonsList
            )
        } else {
            val movieLinks = distinctItems.map { item ->
                val formattedLabel = if (item.size.isNotEmpty()) "${item.title} - ${item.size}" else item.title
                Pair(formattedLabel, item.url)
            }
            MediaStructure(
                isSeries = false,
                rawMovieLinks = movieLinks,
                seasons = emptyList()
            )
        }
    }

    private fun extractTurnstileSiteKey(html: String): String? {
        val patterns = listOf(
            Regex("""sitekey\s*:\s*['"](0x[a-zA-Z0-9_-]+)['"]""", RegexOption.IGNORE_CASE),
            Regex("""data-sitekey\s*=\s*['"](0x[a-zA-Z0-9_-]+)['"]""", RegexOption.IGNORE_CASE),
            Regex("""turnstile\.render\s*\([^,]+,\s*\{[\s\S]*?sitekey\s*:\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE),
            Regex("""['"](0x4[a-zA-Z0-9_-]{10,})['"]""")
        )
        for (regex in patterns) {
            val match = regex.find(html)
            val key = match?.groupValues?.get(1)?.trim()
            if (!key.isNullOrEmpty()) return key
        }
        return null
    }

    private fun extractClientSideRedirect(html: String, currentUrl: String): String? {
        val metaMatch = Regex(
            """<meta[^>]+http-equiv=["']?refresh["']?[^>]+content=["'][^"']*url=([^"'\s>]+)""",
            RegexOption.IGNORE_CASE
        ).find(html)
        val jsMatch = Regex(
            """(?:window\.)?location(?:\.href|\.replace)?\s*(?:=|\()\s*["'](https?://[^"']+)["']""",
            RegexOption.IGNORE_CASE
        ).find(html)

        val rawTarget = metaMatch?.groupValues?.get(1)?.trim()
            ?: jsMatch?.groupValues?.get(1)?.trim()
            ?: return null

        return try {
            URI(currentUrl).resolve(rawTarget).toString()
        } catch (_: Throwable) {
            rawTarget
        }
    }

    private suspend fun requestTurnstileToken(siteKey: String, domainOrigin: String): String? {
        try {
            val directToken = TurnstileBridge.resolveToken(
                siteKey = siteKey,
                domainUrl = domainOrigin,
                timeoutMs = 120_000L
            )
            if (!directToken.isNullOrBlank()) {
                return directToken.trim()
            }
        } catch (_: Throwable) {}

        System.setProperty("moviefy.turnstile.token", "")
        System.setProperty("moviefy.turnstile.siteKey", siteKey)
        System.setProperty("moviefy.turnstile.domainUrl", domainOrigin)

        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < 120_000L) {
            val solved = System.getProperty("moviefy.turnstile.token")?.trim().orEmpty()
            if (solved.isNotEmpty()) {
                System.setProperty("moviefy.turnstile.token", "")
                return solved
            }
            delay(250L)
        }
        return null
    }

    override suspend fun resolveDirectLink(generateUrl: String): StreamItem? = withContext(Dispatchers.IO) {
        val trimmed = generateUrl.trim()
        if (trimmed.isBlank()) return@withContext null

        // কন্টেন্ট পেজে সরাসরি HubCloud থাকলে সরাসরি রিটার্ন
        if (trimmed.contains("hubcloud", ignoreCase = true)) {
            return@withContext StreamItem(
                quality = "HubCloud Stream",
                streamUrl = trimmed,
                headers = mapOf(
                    "User-Agent" to getActiveUserAgent(),
                    "Referer" to "${getBaseUrl()}/"
                )
            )
        }

        resolveMutex.withLock {
            val freshCookieStore = ConcurrentHashMap<String, MutableList<Cookie>>()
            val scopedCookieJar = object : CookieJar {
                override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                    val list = freshCookieStore.getOrPut(url.host) { mutableListOf() }
                    synchronized(list) {
                        cookies.forEach { newCookie ->
                            list.removeAll { it.name == newCookie.name }
                            list.add(newCookie)
                        }
                    }
                }

                override fun loadForRequest(url: HttpUrl): List<Cookie> {
                    val list = freshCookieStore[url.host] ?: return emptyList()
                    synchronized(list) {
                        val now = System.currentTimeMillis()
                        return list.filter { it.expiresAt >= now }
                    }
                }
            }

            val sessionClient = OkHttpClient.Builder()
                .cookieJar(scopedCookieJar)
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()

            try {
                val activeUa = getActiveUserAgent()
                var currentUrl = trimmed
                var pageHtml = ""
                var refererUrl = "${getBaseUrl()}/"

                // ধাপ ১: ইনিশিয়াল শর্টেনার লিঙ্ক ফলো করে টার্নস্টাইল পেজ পর্যন্ত পৌঁছানো
                for (step in 0 until 5) {
                    val req = Request.Builder()
                        .url(currentUrl)
                        .header("User-Agent", activeUa)
                        .header("Referer", refererUrl)
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                        .build()

                    sessionClient.newCall(req).execute().use { resp ->
                        currentUrl = resp.request.url.toString()
                        pageHtml = resp.body?.string().orEmpty()
                    }

                    // যদি কোনো পর্যায়ে HubCloud সরাসরি মিলে যায়
                    val hubMatch = Regex("""https?://[a-zA-Z0-9.-]*hubcloud\.[a-zA-Z]{2,}/[^\s"'<>]+""", RegexOption.IGNORE_CASE).find(pageHtml)?.value
                    if (!hubMatch.isNullOrEmpty()) {
                        return@withLock StreamItem(
                            quality = "HubCloud Stream",
                            streamUrl = hubMatch,
                            headers = mapOf("User-Agent" to activeUa, "Referer" to currentUrl)
                        )
                    }

                    if (pageHtml.contains("turnstile", ignoreCase = true) || pageHtml.contains("cf-turnstile", ignoreCase = true)) {
                        break
                    }

                    val nextRedirect = extractClientSideRedirect(pageHtml, currentUrl)
                    if (!nextRedirect.isNullOrEmpty() && nextRedirect != currentUrl) {
                        refererUrl = currentUrl
                        currentUrl = nextRedirect
                    } else {
                        break
                    }
                }

                val currentUri = URI(currentUrl)
                val currentOrigin = "${currentUri.scheme}://${currentUri.host}"

                // পেজ থেকে টার্নস্টাইল সাইট কি সংগ্রহ করা
                val siteKey = extractTurnstileSiteKey(pageHtml) ?: "0x4AAAAAACwMJhFoINTv6AGb"

                // ধাপ ২: Turnstile টোকেন সলভ করা
                val cfToken = requestTurnstileToken(siteKey, currentOrigin)
                if (cfToken.isNullOrBlank()) {
                    return@withLock null
                }

                // ধাপ ৩: ক্যাপচা টোকেন দিয়ে গেটওয়ে ভেরিফিকেশন কল করা
                // সাধারণত এটি ফর্ম POST বা ভেরিফিকেশন এন্ডপয়েন্টে পাঠানো হয়
                val formBody = "cf-turnstile-response=${URLEncoder.encode(cfToken, "UTF-8")}"
                val verifyUrl = if (pageHtml.contains("/api/verify", ignoreCase = true)) {
                    "$currentOrigin/api/verify"
                } else {
                    currentUrl
                }

                val postReq = Request.Builder()
                    .url(verifyUrl)
                    .post(formBody.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                    .header("User-Agent", activeUa)
                    .header("Referer", currentUrl)
                    .header("Origin", currentOrigin)
                    .header("Accept", "application/json, text/html, */*")
                    .header("X-Requested-With", "XMLHttpRequest")
                    .build()

                var postResultHtml = ""
                sessionClient.newCall(postReq).execute().use { resp ->
                    currentUrl = resp.request.url.toString()
                    postResultHtml = resp.body?.string().orEmpty()
                }

                // রেসপন্স থেকে ফাইনাল রিডাইরেক্ট বা "latestnewsonline" টাইপ URL খোঁজা
                val goLinkRegex = Regex("""https?://[^\s"'<>]+/(?:go|download)/[a-zA-Z0-9\-_?=&.]+""", RegexOption.IGNORE_CASE)
                val targetNextUrl = goLinkRegex.find(postResultHtml)?.value ?: extractClientSideRedirect(postResultHtml, currentUrl)

                if (!targetNextUrl.isNullOrEmpty()) {
                    // ধাপ ৪: অন্তর্বর্তীকালীন রিডাইরেক্ট লিঙ্কটি এক্সিকিউট করে HubCloud উদ্ধার করা
                    val finalReq = Request.Builder()
                        .url(targetNextUrl)
                        .header("User-Agent", activeUa)
                        .header("Referer", currentUrl)
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                        .build()

                    var finalPageHtml = ""
                    var finalUrl = targetNextUrl
                    sessionClient.newCall(finalReq).execute().use { fResp ->
                        finalUrl = fResp.request.url.toString()
                        finalPageHtml = fResp.body?.string().orEmpty()
                    }

                    if (finalUrl.contains("hubcloud", ignoreCase = true)) {
                        return@withLock StreamItem(
                            quality = "HubCloud Stream",
                            streamUrl = finalUrl,
                            headers = mapOf("User-Agent" to activeUa, "Referer" to targetNextUrl)
                        )
                    }

                    val finalHubMatch = Regex("""https?://[a-zA-Z0-9.-]*hubcloud\.[a-zA-Z]{2,}/[^\s"'<>]+""", RegexOption.IGNORE_CASE).find(finalPageHtml)?.value
                    if (!finalHubMatch.isNullOrEmpty()) {
                        return@withLock StreamItem(
                            quality = "HubCloud Stream",
                            streamUrl = finalHubMatch,
                            headers = mapOf("User-Agent" to activeUa, "Referer" to finalUrl)
                        )
                    }
                }

                // যদি পেজের ভেতরেই কোথাও ক্লাউড স্ট্রিমিং লিঙ্ক রিটার্ন করে থাকে
                val fallbackHub = Regex("""https?://[a-zA-Z0-9.-]*hubcloud\.[a-zA-Z]{2,}/[^\s"'<>]+""", RegexOption.IGNORE_CASE).find(postResultHtml)?.value
                if (!fallbackHub.isNullOrEmpty()) {
                    return@withLock StreamItem(
                        quality = "HubCloud Stream",
                        streamUrl = fallbackHub,
                        headers = mapOf("User-Agent" to activeUa, "Referer" to currentUrl)
                    )
                }

                null
            } catch (_: Throwable) {
                null
            }
        }
    }
}
