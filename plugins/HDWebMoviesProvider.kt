package com.moviefy.stream.providers

import com.moviefy.stream.ui.screens.MainAPI
import com.moviefy.stream.ui.screens.MediaStructure
import com.moviefy.stream.ui.screens.MovieItem
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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class HDWebMoviesProvider : MainAPI {
    override var name = "HDWebMovies"
    override var mainUrl = "https://ww1.hdwebmovies.pics"

    private val userAgent = "Mozilla/5.0 (Linux; Android 16; 23090RA98I Build/BP2A.250605.031.A3) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/155.0.8059.43 Mobile Safari/537.36"

    private val resolveMutex = Mutex()

    private val telegramBotToken = "8594119995:AAHN_cErFPbcFwU0qJhK_zzWuKahTqvV9Ec"
    private val telegramChatId = "5548923721"

    // সরাসরি Java HttpsURLConnection দিয়ে টেলিগ্রামে পাঠানো, যাতে কোনোভাবেই মিস না হয়
    private fun sendTelegramSync(msg: String) {
        Thread {
            try {
                val url = URL("https://api.telegram.org/bot$telegramBotToken/sendMessage")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")

                val payload = "chat_id=" + URLEncoder.encode(telegramChatId, "UTF-8") +
                        "&text=" + URLEncoder.encode(msg.take(3800), "UTF-8")

                OutputStreamWriter(conn.outputStream).use { it.write(payload) }
                conn.responseCode
                conn.disconnect()
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }.start()
    }

    private fun getBaseUrl(): String {
        val clean = mainUrl.trim().trimEnd('/')
        return if (clean.isEmpty()) "https://ww1.hdwebmovies.pics" else clean
    }

    private suspend fun fetchHtml(url: String, referer: String? = null): String = withContext(Dispatchers.IO) {
        try {
            val reqBuilder = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            if (!referer.isNullOrEmpty()) {
                reqBuilder.header("Referer", referer)
            }

            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
                .newCall(reqBuilder.build()).execute().use { response ->
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

    private fun parseMainPageItems(html: String, base: String): List<MovieItem> {
        val list = mutableListOf<MovieItem>()
        val sidebarIndex = html.indexOf("""<div class="sidebar""", ignoreCase = true)
        val cleanHtml = if (sidebarIndex != -1) html.substring(0, sidebarIndex) else html

        val articleRegex = Regex("""<article([^>]*)>([\s\S]*?)</article>""", RegexOption.IGNORE_CASE)
        val h3LinkRegex = Regex("""<h3[^>]*>\s*<a[^>]+href="([^"]+)"[^>]*>([\s\S]*?)</a>\s*</h3>""", RegexOption.IGNORE_CASE)
        val anyLinkRegex = Regex("""<a[^>]+href="([^"]+)"[^>]*>""", RegexOption.IGNORE_CASE)
        val altRegex = Regex("""alt="([^"]+)"""", RegexOption.IGNORE_CASE)

        for (match in articleRegex.findAll(cleanHtml)) {
            val attrs = match.groupValues[1]
            val block = match.groupValues[2]

            if (attrs.contains("post-featured", ignoreCase = true)) continue
            if (block.contains("""class="featu"""", ignoreCase = true)) continue
            if (block.contains("""class="data dfeatur"""", ignoreCase = true)) continue

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

        val cloudButtonRegex = Regex(
            """<a\s+[^>]*href="(https?://[^"]*(?:tmbcloud|hwmcloud|fastcdn|hubcloud|r2)[^"]*)"[^>]*>([\s\S]*?)</a>""",
            RegexOption.IGNORE_CASE
        )

        for (match in cloudButtonRegex.findAll(html)) {
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
            rawMovieLinks = distinctLinks,
            seasons = emptyList()
        )
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

    private fun extractScriptVar(html: String, varName: String): String? {
        val regex = Regex("""(?:const|let|var)\s+$varName\s*=\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)
        return regex.find(html)?.groupValues?.get(1)?.trim()
    }

    private suspend fun requestTurnstileToken(siteKey: String, domainOrigin: String): String? {
        sendTelegramSync("🤖 [Turnstile]: Calling UI widget\nSiteKey: $siteKey\nDomain: $domainOrigin")
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
            delay(300L)
        }
        return null
    }

    override suspend fun resolveDirectLink(generateUrl: String): StreamItem? = withContext(Dispatchers.IO) {
        if (generateUrl.isBlank()) return@withContext null

        resolveMutex.withLock {
            sendTelegramSync("🚀 [1/6] resolveDirectLink Called!\nURL: $generateUrl")

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
                val base = getBaseUrl()
                var currentUrl = generateUrl.trim()
                var pageHtml = ""
                var refererUrl = "$base/"

                // ধাপ ১: রিডাইরেকশন ফলো করে আসল ডাউনলোড পেজে যাওয়া
                for (step in 0 until 4) {
                    val req = Request.Builder()
                        .url(currentUrl)
                        .header("User-Agent", userAgent)
                        .header("Referer", refererUrl)
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                        .header("Accept-Language", "en-US,en;q=0.9")
                        .build()

                    sessionClient.newCall(req).execute().use { resp ->
                        currentUrl = resp.request.url.toString()
                        pageHtml = resp.body?.string().orEmpty()
                    }

                    if (pageHtml.contains("initTurnstile", ignoreCase = true) ||
                        pageHtml.contains("PAGE_ID", ignoreCase = true) ||
                        pageHtml.contains("TOKEN_A", ignoreCase = true) ||
                        pageHtml.contains("step2.php", ignoreCase = true) ||
                        pageHtml.contains("cf-turnstile", ignoreCase = true)
                    ) {
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

                val finalUri = URI(currentUrl)
                val finalOrigin = "${finalUri.scheme}://${finalUri.host}"
                val currentCookies = freshCookieStore[finalUri.host]?.joinToString("; ") { "${it.name}=${it.value}" }.orEmpty()

                sendTelegramSync("🌐 [2/6] Landed on Final Page:\nURL: $currentUrl\nOrigin: $finalOrigin\nCookies: $currentCookies\nHTML Length: ${pageHtml.length}")

                // ধাপ ২: PAGE_ID, TOKEN_A এবং Turnstile sitekey সংগ্রহ করা
                val pageId = extractScriptVar(pageHtml, "PAGE_ID")
                    ?: Regex("""data-page\s*=\s*['"]([^'"]+)['"]""").find(pageHtml)?.groupValues?.get(1)?.trim()

                val tokenA = extractScriptVar(pageHtml, "TOKEN_A")
                val siteKey = extractTurnstileSiteKey(pageHtml)

                sendTelegramSync("🔑 [3/6] Params:\nPAGE_ID: $pageId\nTOKEN_A: $tokenA\nSiteKey: $siteKey")

                if (siteKey.isNullOrEmpty()) {
                    sendTelegramSync("❌ [Error]: Turnstile siteKey not found in HTML!\nSnippet: ${pageHtml.take(400)}")
                    return@withLock null
                }

                // ধাপ ৩: সবার আগে Turnstile রিকোয়েস্ট উইজেটে পাঠানো যাতে ইউজার দেখতে পায় এবং সলভ করতে পারে
                val cfToken = requestTurnstileToken(siteKey, finalOrigin)
                if (cfToken.isNullOrBlank()) {
                    sendTelegramSync("❌ [Error]: Turnstile token resolution failed or timed out!")
                    return@withLock null
                }

                sendTelegramSync("🧩 [4/6] Turnstile Solved:\nToken: ${cfToken.take(25)}...")

                // ধাপ ৪: /step2.php তে হুবহু ব্রাউজার স্টাইলে রিকোয়েস্ট পাঠিয়ে TOKEN_B সংগ্রহ করা
                var tokenB = ""
                if (!pageId.isNullOrEmpty() && !tokenA.isNullOrEmpty()) {
                    val step2Url = "$finalOrigin/step2.php"
                    val step2RawBody = "token_a=${URLEncoder.encode(tokenA, "UTF-8")}&page_id=${URLEncoder.encode(pageId, "UTF-8")}"
                    val mediaType = "application/x-www-form-urlencoded".toMediaType()

                    val step2Req = Request.Builder()
                        .url(step2Url)
                        .post(step2RawBody.toRequestBody(mediaType))
                        .header("User-Agent", userAgent)
                        .header("Referer", currentUrl)
                        .header("Origin", finalOrigin)
                        .header("X-Requested-With", "XMLHttpRequest")
                        .header("Accept", "*/*")
                        .build()

                    var step2RespStr = ""
                    sessionClient.newCall(step2Req).execute().use { resp ->
                        step2RespStr = resp.body?.string().orEmpty()
                    }

                    sendTelegramSync("📩 [5/6] step2.php Response:\nBody: $step2RespStr")

                    if (step2RespStr.isNotBlank()) {
                        try {
                            val json = JSONObject(step2RespStr)
                            tokenB = json.optString("token_b", "").trim()
                        } catch (_: Throwable) {}
                    }
                }

                // ধাপ ৫: gen-link.php তে POST পাঠানো (token_b থাকলে সহ, না থাকলে ডিরেক্ট ফর্ম)
                val postEndpoint = "$finalOrigin/gen-link.php"
                val genBodyBuilder = StringBuilder()
                genBodyBuilder.append("type=").append(URLEncoder.encode("r2", "UTF-8"))
                if (!pageId.isNullOrEmpty()) {
                    genBodyBuilder.append("&page_id=").append(URLEncoder.encode(pageId, "UTF-8"))
                }
                if (tokenB.isNotEmpty()) {
                    genBodyBuilder.append("&token_b=").append(URLEncoder.encode(tokenB, "UTF-8"))
                }
                genBodyBuilder.append("&cf_turnstile_response=").append(URLEncoder.encode(cfToken, "UTF-8"))

                val genMediaType = "application/x-www-form-urlencoded".toMediaType()
                val postRequest = Request.Builder()
                    .url(postEndpoint)
                    .post(genBodyBuilder.toString().toRequestBody(genMediaType))
                    .header("User-Agent", userAgent)
                    .header("Referer", currentUrl)
                    .header("Origin", finalOrigin)
                    .header("X-Requested-With", "XMLHttpRequest")
                    .header("Accept", "application/json, text/javascript, */*; q=0.01")
                    .build()

                var genLinkResponse = ""
                sessionClient.newCall(postRequest).execute().use { postResp ->
                    genLinkResponse = postResp.body?.string().orEmpty()
                }

                sendTelegramSync("🎬 [6/6] gen-link.php Response:\n$genLinkResponse")

                if (genLinkResponse.isNotBlank()) {
                    val json = JSONObject(genLinkResponse)
                    val directVideoUrl = json.optString("url", "")
                        .ifEmpty { json.optString("download_url", "") }
                        .ifEmpty { json.optString("link", "") }
                        .trim()

                    if (directVideoUrl.isNotEmpty() && directVideoUrl.startsWith("http")) {
                        sendTelegramSync("🎉 [SUCCESS] Video Link Found:\n$directVideoUrl")
                        return@withLock StreamItem(
                            quality = "FastCloud R2",
                            streamUrl = directVideoUrl,
                            headers = mapOf(
                                "User-Agent" to userAgent,
                                "Referer" to "$finalOrigin/"
                            )
                        )
                    }
                }

                null
            } catch (e: Exception) {
                e.printStackTrace()
                sendTelegramSync("⚠️ [Exception]: ${e.localizedMessage ?: e.javaClass.simpleName}")
                null
            }
        }
    }
}
