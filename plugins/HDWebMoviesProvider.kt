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
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class HDWebMoviesProvider : MainAPI {
    override var name = "HDWebMovies"
    override var mainUrl = "https://ww1.hdwebmovies.pics"

    private val userAgent = "Mozilla/5.0 (Linux; Android 16; 23090RA98I Build/BP2A.250605.031.A3) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/155.0.8059.43 Mobile Safari/537.36"

    private val resolveMutex = Mutex()

    // সেশন কুকি যাতে লস্ট না হয় সেজন্য কনকারেন্ট স্টোর
    private val cookieStore = ConcurrentHashMap<String, MutableList<Cookie>>()
    private val sessionCookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val host = url.host
            val list = cookieStore.getOrPut(host) { mutableListOf() }
            synchronized(list) {
                cookies.forEach { newCookie ->
                    list.removeAll { it.name == newCookie.name }
                    list.add(newCookie)
                }
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val list = cookieStore[url.host] ?: return emptyList()
            synchronized(list) {
                val now = System.currentTimeMillis()
                return list.filter { it.expiresAt >= now }
            }
        }
    }

    private val client = OkHttpClient.Builder()
        .cookieJar(sessionCookieJar)
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
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

    private suspend fun fetchHtml(url: String, referer: String? = null): String = withContext(Dispatchers.IO) {
        try {
            val reqBuilder = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            if (!referer.isNullOrEmpty()) {
                reqBuilder.header("Referer", referer)
            }

            client.newCall(reqBuilder.build()).execute().use { response ->
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

        return MediaStructure(
            isSeries = false,
            rawMovieLinks = distinctLinks.ifEmpty { listOf(Pair("Direct Stream", postUrl)) },
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
            Regex("""sitekey\s*:\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE),
            Regex("""data-sitekey\s*=\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE),
            Regex("""turnstile\.render\s*\([^,]+,\s*\{[\s\S]*?sitekey\s*:\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)
        )
        for (regex in patterns) {
            val match = regex.find(html)
            val key = match?.groupValues?.get(1)?.trim()
            if (!key.isNullOrEmpty()) {
                return key
            }
        }
        return null
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
        } catch (_: Throwable) {
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
        }
        return null
    }

    // ডিবাগিংয়ের জন্য রেসপন্স টেলিগ্রামে পাঠানো
    private fun sendTelegramDebugLog(message: String) {
        try {
            val botToken = "8594119995:AAHN_cErFPbcFwU0qJhK_zzWuKahTqvV9Ec"
            val chatId = "5548923721"
            val tgUrl = "https://api.telegram.org/bot$botToken/sendMessage"

            val body = FormBody.Builder()
                .add("chat_id", chatId)
                .add("text", message.take(4000))
                .build()

            val req = Request.Builder()
                .url(tgUrl)
                .post(body)
                .build()

            client.newCall(req).execute().close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override suspend fun resolveDirectLink(generateUrl: String): StreamItem? = withContext(Dispatchers.IO) {
        if (generateUrl.isBlank()) return@withContext null

        resolveMutex.withLock {
            try {
                val base = getBaseUrl()

                var currentUrl = generateUrl.trim()
                var pageHtml = ""
                var refererUrl = "$base/"

                var phpSessionId: String? = null

                // ১. রিডাইরেকশন ফলো করে মূল ডাউনলোড পেজ ও PHPSESSID কালেক্ট করা
                for (step in 0 until 4) {
                    val req = Request.Builder()
                        .url(currentUrl)
                        .header("User-Agent", userAgent)
                        .header("Referer", refererUrl)
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                        .build()

                    client.newCall(req).execute().use { resp ->
                        currentUrl = resp.request.url.toString()
                        pageHtml = resp.body?.string().orEmpty()

                        // সেফগার্ড: হেডারে PHPSESSID আসলে ম্যানুয়ালি স্টোর করা
                        val cookies = resp.headers("Set-Cookie")
                        for (c in cookies) {
                            if (c.contains("PHPSESSID=")) {
                                val match = Regex("""PHPSESSID=([^;]+)""").find(c)
                                if (match != null) {
                                    phpSessionId = match.groupValues[1]
                                }
                            }
                        }
                    }

                    if (pageHtml.contains("PAGE_ID", ignoreCase = true) ||
                        pageHtml.contains("initTurnstile", ignoreCase = true) ||
                        pageHtml.contains("gen-link.php", ignoreCase = true)
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

                // ২. স্ক্রিপ্ট থেকে PAGE_ID এবং TOKEN_A এক্সট্র্যাক্ট করা
                val pageId = Regex("""const\s+PAGE_ID\s*=\s*['"]([^'"]+)['"]""").find(pageHtml)?.groupValues?.get(1)?.trim()
                    ?: Regex("""data-page\s*=\s*['"]([^'"]+)['"]""").find(pageHtml)?.groupValues?.get(1)?.trim()
                    ?: return@withLock null

                val tokenA = Regex("""const\s+TOKEN_A\s*=\s*['"]([^'"]+)['"]""").find(pageHtml)?.groupValues?.get(1)?.trim()
                    ?: return@withLock null

                val siteKey = extractTurnstileSiteKey(pageHtml) ?: return@withLock null

                // ৩. টার্নস্টাইল সলভের আগেই step2.php কল করে TOKEN_B তৈরি ও সংগ্রহ করা
                val step2Endpoint = "$finalOrigin/step2.php"
                val step2Body = FormBody.Builder()
                    .add("token_a", tokenA)
                    .add("page_id", pageId)
                    .build()

                val step2Builder = Request.Builder()
                    .url(step2Endpoint)
                    .post(step2Body)
                    .header("User-Agent", userAgent)
                    .header("Referer", currentUrl)
                    .header("Origin", finalOrigin)
                    .header("X-Requested-With", "XMLHttpRequest")
                    .header("Accept", "application/json, text/javascript, */*; q=0.01")

                if (!phpSessionId.isNullOrEmpty()) {
                    step2Builder.header("Cookie", "PHPSESSID=$phpSessionId")
                }

                val step2ResponseStr = client.newCall(step2Builder.build()).execute().use { it.body?.string().orEmpty() }
                val step2Json = try { JSONObject(step2ResponseStr) } catch (_: Exception) { JSONObject() }
                val tokenB = step2Json.optString("token_b", "").trim()

                if (tokenB.isEmpty()) {
                    sendTelegramDebugLog("⚠️ Step 2 Failed!\nURL: $currentUrl\nResp: $step2ResponseStr")
                    return@withLock null
                }

                // ৪. TurnstileBridge দিয়ে Turnstile সলভ করানো
                val cfToken = requestTurnstileToken(siteKey, finalOrigin)
                if (cfToken.isNullOrBlank()) {
                    sendTelegramDebugLog("⚠️ Turnstile Token Failed for: $finalOrigin")
                    return@withLock null
                }

                // ৫. gen-link.php তে সম্পূর্ণ পেলোড (type=r2, page_id, token_b, cf_turnstile_response) পাঠানো
                val genLinkEndpoint = "$finalOrigin/gen-link.php"
                val genFormBody = FormBody.Builder()
                    .add("type", "r2")
                    .add("page_id", pageId)
                    .add("token_b", tokenB)
                    .add("cf_turnstile_response", cfToken)
                    .build()

                val genRequestBuilder = Request.Builder()
                    .url(genLinkEndpoint)
                    .post(genFormBody)
                    .header("User-Agent", userAgent)
                    .header("Referer", currentUrl)
                    .header("Origin", finalOrigin)
                    .header("X-Requested-With", "XMLHttpRequest")
                    .header("Accept", "application/json, text/javascript, */*; q=0.01")

                if (!phpSessionId.isNullOrEmpty()) {
                    genRequestBuilder.header("Cookie", "PHPSESSID=$phpSessionId")
                }

                val genResponseStr = client.newCall(genRequestBuilder.build()).execute().use { it.body?.string().orEmpty() }

                // ৬. ডিবাগিং: সর্বশেষ gen-link রেসপন্স সরাসরি টেলিগ্রাম বট মারফত সেন্ড করা
                sendTelegramDebugLog("🎬 HDWebMovies Debug:\nPage ID: $pageId\nResponse:\n$genResponseStr")

                if (genResponseStr.isNotBlank()) {
                    val json = JSONObject(genResponseStr)
                    val directVideoUrl = json.optString("url", "")
                        .ifEmpty { json.optString("download_url", "") }
                        .ifEmpty { json.optString("link", "") }
                        .trim()

                    if (directVideoUrl.isNotEmpty()) {
                        return@withLock StreamItem(
                            quality = "HD",
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
                sendTelegramDebugLog("❌ Exception in resolveDirectLink: ${e.localizedMessage}")
                e.printStackTrace()
                null
            }
        }
    }
}
