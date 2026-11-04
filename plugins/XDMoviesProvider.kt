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

    private val userAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Mobile Safari/537.36"

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
            return@withContext MediaStructure(isSeries = true, rawMovieLinks = emptyList(), seasons = seasonsList)
        } else {
            val movieLinks = distinctItems.map { item ->
                val formattedLabel = if (item.size.isNotEmpty()) "${item.title} - ${item.size}" else item.title
                Pair(formattedLabel, item.url)
            }
            return@withContext MediaStructure(isSeries = false, rawMovieLinks = movieLinks, seasons = emptyList())
        }
    }

    // ==========================================
    // Turnstile & Redirection Helper Functions
    // ==========================================

    private fun extractTurnstileSiteKey(html: String): String? {
        val regex = Regex("""data-sitekey\s*=\s*['"](0x[a-zA-Z0-9_-]+)['"]""", RegexOption.IGNORE_CASE)
        return regex.find(html)?.groupValues?.get(1)?.trim()
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

    // ==========================================
    // Final Link Resolver Module
    // ==========================================

    override suspend fun resolveDirectLink(generateUrl: String): StreamItem? = withContext(Dispatchers.IO) {
        val trimmed = generateUrl.trim()
        if (trimmed.isBlank()) return@withContext null

        val activeUa = getActiveUserAgent()
        val baseDomain = getBaseUrl()

        // ১. যদি লিংকটি সরাসরি HubCloud এর হয়, তবে সাথে সাথে রিটার্ন করা
        if (trimmed.contains("hubcloud", ignoreCase = true)) {
            return@withContext StreamItem(
                quality = "HubCloud Stream",
                streamUrl = trimmed,
                headers = mapOf("User-Agent" to activeUa, "Referer" to "$baseDomain/")
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
                // ধাপ ১: Gateway Page Fetch করা (যেখানে Turnstile থাকে)
                val initialReq = Request.Builder()
                    .url(trimmed)
                    .header("User-Agent", activeUa)
                    .header("Referer", "$baseDomain/")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .build()

                var pageHtml = ""
                var gatewayUrl = trimmed

                sessionClient.newCall(initialReq).execute().use { resp ->
                    gatewayUrl = resp.request.url.toString()
                    pageHtml = resp.body?.string().orEmpty()
                }

                // লোকাল ভেরিয়েবলে রেখে দেওয়া (Smart cast error এড়ানোর জন্য)
                val safeGatewayUrl = gatewayUrl 
                val currentUri = URI(safeGatewayUrl)
                val gatewayOrigin = "${currentUri.scheme}://${currentUri.host}"

                // পেজের ভেতরেই যদি HubCloud থাকে তবে রিটার্ন 
                val hubRegex = Regex("""https?://[a-zA-Z0-9.-]*hubcloud\.[a-zA-Z]{2,}/[^\s"'<>]+""", RegexOption.IGNORE_CASE)
                val hubMatch = hubRegex.find(pageHtml)?.value
                if (!hubMatch.isNullOrEmpty()) {
                    return@withLock StreamItem(
                        quality = "HubCloud Stream", 
                        streamUrl = hubMatch, 
                        headers = mapOf("User-Agent" to activeUa, "Referer" to safeGatewayUrl)
                    )
                }

                // ধাপ ২: Turnstile SiteKey এক্সট্র্যাক্ট করা
                val siteKey = extractTurnstileSiteKey(pageHtml) ?: "0x4AAAAAACwMJhFoINTv6AGb"

                // ধাপ ৩: Turnstile Bridge ব্যবহার করে টোকেন সলভ করা
                val cfToken = requestTurnstileToken(siteKey, gatewayOrigin)
                if (cfToken.isNullOrBlank()) {
                    return@withLock null
                }

                // ধাপ ৪: টোকেন দিয়ে POST রিকোয়েস্ট পাঠানো
                val formBody = "cf-turnstile-response=${URLEncoder.encode(cfToken, "UTF-8")}"
                val postReq = Request.Builder()
                    .url(safeGatewayUrl) // সাধারণত একই URL এ ফর্ম সাবমিট হয়
                    .post(formBody.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                    .header("User-Agent", activeUa)
                    .header("Referer", safeGatewayUrl)
                    .header("Origin", gatewayOrigin)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .build()

                var postResultHtml = ""
                var postResponseUrl = safeGatewayUrl

                sessionClient.newCall(postReq).execute().use { resp ->
                    postResponseUrl = resp.request.url.toString()
                    postResultHtml = resp.body?.string().orEmpty()
                }
                
                val safePostResponseUrl = postResponseUrl // Smart cast safe string

                // POST এর রেসপন্সে যদি সরাসরি HubCloud এ রিডাইরেক্ট হয়ে যায়
                if (safePostResponseUrl.contains("hubcloud", ignoreCase = true)) {
                    return@withLock StreamItem(
                        quality = "HubCloud Stream", 
                        streamUrl = safePostResponseUrl, 
                        headers = mapOf("User-Agent" to activeUa, "Referer" to safeGatewayUrl)
                    )
                }

                // ধাপ ৫: POST রেসপন্স থেকে go/download লিংক এক্সট্র্যাক্ট করা (e.g. latestnewsonline.sbs)
                val goLinkRegex = Regex("""https?://[^\s"'<>]+/(?:go|download)/[a-zA-Z0-9\-_?=&.]+""", RegexOption.IGNORE_CASE)
                var nextTargetUrl = goLinkRegex.find(postResultHtml)?.value ?: extractClientSideRedirect(postResultHtml, safePostResponseUrl)

                // JSON ফরম্যাটে রেসপন্স আসলে
                if (nextTargetUrl.isNullOrEmpty() && postResultHtml.trim().startsWith("{")) {
                    try {
                        val json = JSONObject(postResultHtml)
                        val urlFromJson = json.optString("url", "").trim()
                        if (urlFromJson.isNotEmpty()) nextTargetUrl = urlFromJson
                    } catch (_: Exception) {}
                }

                // ধাপ ৬: ফাইনাল ইন্টারমিডিয়েট লিংকে ভিজিট করে অরিজিনাল HubCloud বের করা
                if (!nextTargetUrl.isNullOrEmpty()) {
                    val safeNextTarget = nextTargetUrl ?: "" 
                    
                    val finalReq = Request.Builder()
                        .url(safeNextTarget)
                        .header("User-Agent", activeUa)
                        .header("Referer", safePostResponseUrl)
                        .build()

                    var finalPageHtml = ""
                    var finalEndUrl = safeNextTarget

                    sessionClient.newCall(finalReq).execute().use { fResp ->
                        finalEndUrl = fResp.request.url.toString()
                        finalPageHtml = fResp.body?.string().orEmpty()
                    }
                    
                    val safeFinalEndUrl = finalEndUrl

                    // যদি URL টাই Hubcloud এ পৌঁছে যায়
                    if (safeFinalEndUrl.contains("hubcloud", ignoreCase = true)) {
                        return@withLock StreamItem(
                            quality = "HubCloud Stream",
                            streamUrl = safeFinalEndUrl,
                            headers = mapOf("User-Agent" to activeUa, "Referer" to safeNextTarget)
                        )
                    }

                    // অথবা পেজের ভেতরে Hubcloud লিংক থাকে
                    val finalHubMatch = hubRegex.find(finalPageHtml)?.value
                    if (!finalHubMatch.isNullOrEmpty()) {
                        return@withLock StreamItem(
                            quality = "HubCloud Stream",
                            streamUrl = finalHubMatch,
                            headers = mapOf("User-Agent" to activeUa, "Referer" to safeFinalEndUrl)
                        )
                    }
                }

                // সর্বশেষ ফলব্যাক হিসেবে POST রেসপন্স পেজের ভেতর HubCloud খুঁজবে
                val fallbackHub = hubRegex.find(postResultHtml)?.value
                if (!fallbackHub.isNullOrEmpty()) {
                    return@withLock StreamItem(
                        quality = "HubCloud Stream",
                        streamUrl = fallbackHub,
                        headers = mapOf("User-Agent" to activeUa, "Referer" to safePostResponseUrl)
                    )
                }

                null
            } catch (_: Throwable) {
                null
            }
        }
    }
}
