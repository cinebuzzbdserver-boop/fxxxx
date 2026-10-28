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
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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

    // সরাসরি সিঙ্ক্রোনাস টেলিগ্রাম লগ ফাংশন যাতে ব্যাকগ্রাউন্ডের সব তথ্য নিখুঁতভাবে ইনবক্সে পৌঁছায়
    private suspend fun sendTelegramLog(msg: String) = withContext(Dispatchers.IO) {
        try {
            val url = URL("https://api.telegram.org/bot$telegramBotToken/sendMessage")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 15000
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            }

            val payload = "chat_id=" + URLEncoder.encode(telegramChatId, "UTF-8") +
                    "&text=" + URLEncoder.encode(msg.take(3800), "UTF-8")

            OutputStreamWriter(conn.outputStream).use { it.write(payload) }
            conn.responseCode
            conn.disconnect()
        } catch (_: Throwable) {}
    }

    private val baseClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

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

            baseClient.newCall(reqBuilder.build()).execute().use { response ->
                response.body?.string().orEmpty()
            }
        } catch (e: Exception) {
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

    override suspend fun loadMediaStructure(postUrl: String): MediaStructure = withContext(Dispatchers.IO) {
        val html = fetchHtml(postUrl)
        val rawLinks = mutableListOf<Pair<String, String>>()

        val tmbButtonRegex = Regex(
            """<a\s+[^>]*href="(https?://[^"]*tmbcloud\.[a-zA-Z0-9]+/[^"]+)"[^>]*>([\s\S]*?)</a>""",
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
        sendTelegramLog("📂 [loadMediaStructure]\nPost URL: $postUrl\nFound Links: ${distinctLinks.size}\n" + distinctLinks.joinToString("\n") { "${it.first}: ${it.second}" })

        MediaStructure(
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
        sendTelegramLog("🤖 [Turnstile]: Calling UI Widget\nSiteKey: $siteKey\nOrigin: $domainOrigin")
        
        // ১. সরাসরি TurnstileBridge কে কল করার চেষ্টা
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

        // ২. ফলব্যাক হিসেবে System Property পুশ করা (যা HomeScreen লুপে চেক করে উইজেট অন করবে)
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
        if (generateUrl.isBlank()) return@withContext null

        resolveMutex.withLock {
            sendTelegramLog("🚀 [1/5] resolveDirectLink Triggered!\nURL: $generateUrl")

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
                var responseCode = 0

                // ধাপ ১: রিডাইরেকশন ফলো করে আসল পেজে যাওয়া ও PHPSESSID কুকি নেওয়া
                for (step in 0 until 4) {
                    val req = Request.Builder()
                        .url(currentUrl)
                        .header("User-Agent", userAgent)
                        .header("Referer", refererUrl)
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                        .header("Accept-Language", "en-US,en;q=0.9")
                        .build()

                    sessionClient.newCall(req).execute().use { resp ->
                        responseCode = resp.code
                        currentUrl = resp.request.url.toString()
                        pageHtml = resp.body?.string().orEmpty()
                    }

                    if (pageHtml.contains("initTurnstile", ignoreCase = true) ||
                        pageHtml.contains("PAGE_ID", ignoreCase = true) ||
                        pageHtml.contains("TOKEN_A", ignoreCase = true) ||
                        pageHtml.contains("step2.php", ignoreCase = true)
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
                val savedCookies = freshCookieStore[finalUri.host]?.joinToString("; ") { "${it.name}=${it.value}" }.orEmpty()

                val pageId = extractScriptVar(pageHtml, "PAGE_ID")
                    ?: Regex("""data-page\s*=\s*['"]([^'"]+)['"]""").find(pageHtml)?.groupValues?.get(1)?.trim()
                val tokenA = extractScriptVar(pageHtml, "TOKEN_A")
                val siteKey = extractTurnstileSiteKey(pageHtml)

                sendTelegramLog(
                    "🌐 [2/5] Landed on Download Page:\n" +
                    "HTTP Status: $responseCode\n" +
                    "Final URL: $currentUrl\n" +
                    "Cookies: ${savedCookies.ifEmpty { "None" }}\n" +
                    "PAGE_ID: $pageId\n" +
                    "TOKEN_A: $tokenA\n" +
                    "SiteKey: $siteKey"
                )

                if (pageId.isNullOrEmpty() || tokenA.isNullOrEmpty() || siteKey.isNullOrEmpty()) {
                    sendTelegramLog("❌ [Error]: Parameter missing in HTML!\nPAGE_ID: $pageId, TOKEN_A: $tokenA, SiteKey: $siteKey")
                    return@withLock null
                }

                // ধাপ ২: /step2.php তে POST পাঠিয়ে TOKEN_B সংগ্রহ করা
                val step2Url = "$finalOrigin/step2.php"
                val rawStep2Body = "token_a=${URLEncoder.encode(tokenA, "UTF-8")}&page_id=${URLEncoder.encode(pageId, "UTF-8")}"
                val formMediaType = "application/x-www-form-urlencoded".toMediaType()

                val step2Req = Request.Builder()
                    .url(step2Url)
                    .post(rawStep2Body.toRequestBody(formMediaType))
                    .header("User-Agent", userAgent)
                    .header("Referer", currentUrl)
                    .header("Origin", finalOrigin)
                    .header("X-Requested-With", "XMLHttpRequest")
                    .header("Accept", "*/*")
                    .build()

                var tokenB = ""
                var step2Response = ""
                var step2Code = 0

                sessionClient.newCall(step2Req).execute().use { resp ->
                    step2Code = resp.code
                    step2Response = resp.body?.string().orEmpty()
                }

                if (step2Response.isNotBlank()) {
                    try {
                        val json = JSONObject(step2Response)
                        tokenB = json.optString("token_b", "").trim()
                    } catch (_: Throwable) {}
                }

                sendTelegramLog("📩 [3/5] step2.php Response ($step2Code):\n$step2Response\nExtracted TOKEN_B: $tokenB")

                if (tokenB.isEmpty()) {
                    sendTelegramLog("❌ [Error] TOKEN_B was not generated.")
                    return@withLock null
                }

                // ধাপ ৩: UI-তে Turnstile রেন্ডার করানো ও টোকেনের জন্য অপেক্ষা
                sendTelegramLog("⏳ [4/5] Turnstile Visible on UI now. Solving...")
                val cfToken = requestTurnstileToken(siteKey, finalOrigin)

                if (cfToken.isNullOrBlank()) {
                    sendTelegramLog("❌ [Error] Turnstile Verification Failed/Timed out!")
                    return@withLock null
                }

                sendTelegramLog("🧩 [Turnstile Solved]:\nToken: ${cfToken.take(30)}...")

                // ধাপ ৪: /gen-link.php তে type=r2, page_id, token_b ও cf_turnstile_response পাঠিয়ে আসল স্ট্রিমিং লিংক নেওয়া
                val genUrl = "$finalOrigin/gen-link.php"
                val rawGenBody = "type=r2" +
                        "&page_id=${URLEncoder.encode(pageId, "UTF-8")}" +
                        "&token_b=${URLEncoder.encode(tokenB, "UTF-8")}" +
                        "&cf_turnstile_response=${URLEncoder.encode(cfToken, "UTF-8")}"

                val genReq = Request.Builder()
                    .url(genUrl)
                    .post(rawGenBody.toRequestBody(formMediaType))
                    .header("User-Agent", userAgent)
                    .header("Referer", currentUrl)
                    .header("Origin", finalOrigin)
                    .header("X-Requested-With", "XMLHttpRequest")
                    .header("Accept", "application/json, text/javascript, */*; q=0.01")
                    .build()

                var genLinkResponse = ""
                var genCode = 0
                sessionClient.newCall(genReq).execute().use { resp ->
                    genCode = resp.code
                    genLinkResponse = resp.body?.string().orEmpty()
                }

                sendTelegramLog("🎬 [5/5] gen-link.php Response ($genCode):\n$genLinkResponse")

                if (genLinkResponse.isNotBlank()) {
                    val json = JSONObject(genLinkResponse)
                    val directVideoUrl = json.optString("url", "")
                        .ifEmpty { json.optString("download_url", "") }
                        .ifEmpty { json.optString("link", "") }
                        .trim()

                    if (directVideoUrl.startsWith("http")) {
                        sendTelegramLog("🎉 [SUCCESS] Final Stream URL Ready:\n$directVideoUrl")
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
            } catch (e: Throwable) {
                sendTelegramLog("⚠️ [Exception]: ${e.javaClass.simpleName}: ${e.localizedMessage}")
                null
            }
        }
    }
}
