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
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class CineFreakProvider : MainAPI {
    override var name = "CineFreak"
    override var mainUrl = "https://cinefreak.net"

    private val userAgent = "Mozilla/5.0 (Linux; Android 16; 23090RA98I Build/BP2A.250605.031.A3) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/155.0.8059.43 Mobile Safari/537.36"

    private val resolveMutex = Mutex()

    // টেলিগ্রাম ক্রেডেনশিয়ালস
    private val telegramBotToken = "8594119995:AAHN_cErFPbcFwU0qJhK_zzWuKahTqvV9Ec"
    private val telegramChatId = "5548923721"

    private val baseClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private suspend fun sendTelegramLog(stepTitle: String, details: String) = withContext(Dispatchers.IO) {
        try {
            val messageText = buildString {
                append("<b>[CineCloud Debug] $stepTitle</b>\n\n")
                append(details)
            }
            val formBody = FormBody.Builder()
                .add("chat_id", telegramChatId)
                .add("text", messageText)
                .add("parse_mode", "HTML")
                .build()
            val req = Request.Builder()
                .url("https://api.telegram.org/bot$telegramBotToken/sendMessage")
                .post(formBody)
                .build()
            baseClient.newCall(req).execute().close()
        } catch (_: Throwable) {}
    }

    private fun getBaseUrl(): String {
        val clean = mainUrl.trim().trimEnd('/')
        return if (clean.isEmpty()) "https://cinefreak.net" else clean
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
            .replace("&#038;", "&")
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

    private fun parseMainPageItems(html: String, base: String): List<MovieItem> {
        val list = mutableListOf<MovieItem>()
        val cardRegex = Regex("""<a\s+[^>]*href="([^"]+)"[^>]*class="[^"]*movie-card[^"]*"[^>]*>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE)
        val titleRegex = Regex("""<h3[^>]*class="[^"]*movie-card-title[^"]*"[^>]*>([\s\S]*?)</h3>""", RegexOption.IGNORE_CASE)
        val imgRegex = Regex("""<img[^>]+src="([^"]+)"""", RegexOption.IGNORE_CASE)

        for (match in cardRegex.findAll(html)) {
            val rawUrl = match.groupValues[1].trim()
            val innerCard = match.groupValues[2]

            val rawTitle = titleRegex.find(innerCard)?.groupValues?.get(1)?.replace(Regex("<[^>]*>"), "")?.trim().orEmpty()
            val rawImg = imgRegex.find(innerCard)?.groupValues?.get(1)?.trim().orEmpty()

            if (rawUrl.isNotEmpty() && rawTitle.isNotEmpty()) {
                list.add(
                    MovieItem(
                        title = decodeHtmlEntities(rawTitle),
                        image = fixUrl(rawImg, base),
                        url = fixUrl(rawUrl, base),
                        isPoster = true,
                        referer = null
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
            "$base/page/$page/"
        }
        val html = fetchHtml(targetUrl)
        return parseMainPageItems(html, base)
    }

    override suspend fun search(query: String, page: Int): List<MovieItem> = withContext(Dispatchers.IO) {
        val base = getBaseUrl()
        val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8")
        val pgNum = if (page < 1) 1 else page
        val searchApiUrl = "$base/search-api.php?q=$encodedQuery&pg=$pgNum"

        try {
            val req = Request.Builder()
                .url(searchApiUrl)
                .header("User-Agent", userAgent)
                .header("Referer", "$base/")
                .header("Accept", "application/json, text/javascript, */*; q=0.01")
                .header("X-Requested-With", "XMLHttpRequest")
                .build()

            val jsonStr = baseClient.newCall(req).execute().use { it.body?.string().orEmpty() }
            if (jsonStr.isBlank()) return@withContext emptyList()

            val root = JSONObject(jsonStr)
            val results = root.optJSONArray("results") ?: return@withContext emptyList()
            val list = mutableListOf<MovieItem>()

            for (i in 0 until results.length()) {
                val item = results.optJSONObject(i) ?: continue
                val rawTitle = item.optString("t", "").trim()
                val path = item.optString("l", "").trim()
                val img = item.optString("i", "").trim()

                if (rawTitle.isNotEmpty() && path.isNotEmpty()) {
                    val fullUrl = if (path.startsWith("http")) path else "$base/$path/"
                    list.add(
                        MovieItem(
                            title = decodeHtmlEntities(rawTitle),
                            image = fixUrl(img, base),
                            url = fullUrl,
                            isPoster = true,
                            referer = null
                        )
                    )
                }
            }
            list.distinctBy { it.url }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    override suspend fun loadMediaStructure(postUrl: String): MediaStructure = withContext(Dispatchers.IO) {
        val html = fetchHtml(postUrl)
        val rawLinks = mutableListOf<Pair<String, String>>()

        val sectionRegex = Regex(
            """<h4\s+class="movie-title"[^>]*>([\s\S]*?)</h4>[\s\S]*?<div\s+class="dlbtn-container"[^>]*>([\s\S]*?)</div>""",
            RegexOption.IGNORE_CASE
        )
        val downloadBtnRegex = Regex(
            """<a\s+[^>]*href="([^"]+)"[^>]*class="[^"]*dlbtn-download[^"]*"[^>]*>""",
            RegexOption.IGNORE_CASE
        )

        for (match in sectionRegex.findAll(html)) {
            val rawTitle = match.groupValues[1].replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim()
            val containerHtml = match.groupValues[2]

            val dlMatch = downloadBtnRegex.find(containerHtml)
            if (dlMatch != null) {
                val downloadUrl = dlMatch.groupValues[1].trim()
                if (downloadUrl.startsWith("http")) {
                    val label = decodeHtmlEntities(rawTitle).ifEmpty { "HD Quality" }
                    rawLinks.add(Pair(label, downloadUrl))
                }
            }
        }

        if (rawLinks.isEmpty()) {
            val directDlRegex = Regex(
                """<a\s+[^>]*href="(https?://[^"]*cinecloud\.[^/]+/f/[^"]+)"[^>]*class="[^"]*dlbtn-download[^"]*"[^>]*>""",
                RegexOption.IGNORE_CASE
            )
            for (match in directDlRegex.findAll(html)) {
                rawLinks.add(Pair("Direct Download", match.groupValues[1].trim()))
            }
        }

        MediaStructure(
            isSeries = false,
            rawMovieLinks = rawLinks.distinctBy { it.second },
            seasons = emptyList()
        )
    }

    private fun extractTurnstileSiteKey(html: String): String? {
        val patterns = listOf(
            Regex("""data-sitekey\s*=\s*['"](0x[a-zA-Z0-9_-]+)['"]""", RegexOption.IGNORE_CASE),
            Regex("""sitekey\s*:\s*['"](0x[a-zA-Z0-9_-]+)['"]""", RegexOption.IGNORE_CASE),
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
        if (generateUrl.isBlank()) return@withContext null

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
                val cleanUrl = generateUrl.trim()
                val targetUri = URI(cleanUrl)
                val domainOrigin = "${targetUri.scheme}://${targetUri.host}"

                sendTelegramLog(
                    "1. Target Received",
                    "<b>URL:</b> <code>$cleanUrl</code>\n<b>Domain:</b> <code>$domainOrigin</code>"
                )

                // ধাপ ১: গেটওয়ে পেজ ফেচ করে সাইটকি নেওয়া (আপনার অরিজিনাল কোড অনুযায়ী)
                val initReq = Request.Builder()
                    .url(cleanUrl)
                    .header("User-Agent", userAgent)
                    .header("Referer", getBaseUrl() + "/")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .build()

                var landingHtml = ""
                sessionClient.newCall(initReq).execute().use { resp ->
                    landingHtml = resp.body?.string().orEmpty()
                }

                val siteKey = extractTurnstileSiteKey(landingHtml) ?: "0x4AAAAAAFUBbDesHlySPLsN"

                // ধাপ ১.৫: ব্রাউজারের মতো ব্যাকগ্রাউন্ডে /view-ping কল করে Redis সেশন অ্যাক্টিভেট করা
                try {
                    val viewPingUrl = if (cleanUrl.endsWith("/")) "${cleanUrl}view-ping" else "$cleanUrl/view-ping"
                    val pingReq = Request.Builder()
                        .url(viewPingUrl)
                        .header("User-Agent", userAgent)
                        .header("Referer", cleanUrl)
                        .header("Accept", "*/*")
                        .build()
                    sessionClient.newCall(pingReq).execute().close()
                } catch (_: Throwable) {}

                // ধাপ ২: /n কল করে n (bc) টোকেন সংগ্রহ (আপনার অরিজিনাল ভেরিফিকেশন লজিক)
                val nUrl = if (cleanUrl.endsWith("/")) "${cleanUrl}n" else "$cleanUrl/n"
                val nReq = Request.Builder()
                    .url(nUrl)
                    .header("User-Agent", userAgent)
                    .header("Referer", cleanUrl)
                    .header("Accept", "application/json, text/javascript, */*; q=0.01")
                    .header("X-Requested-With", "XMLHttpRequest")
                    .build()

                var nToken = ""
                sessionClient.newCall(nReq).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (body.isNotBlank()) {
                        try {
                            val json = JSONObject(body)
                            nToken = json.optString("n", "").trim()
                        } catch (_: Throwable) {}
                    }
                }

                if (nToken.isEmpty()) {
                    sendTelegramLog("FAILED", "Could not extract 'n' token.")
                    return@withLock null
                }

                // ধাপ ৩: টার্নস্টাইল সলভ
                val cfToken = requestTurnstileToken(siteKey, domainOrigin)
                if (cfToken.isNullOrBlank()) {
                    sendTelegramLog("FAILED", "Turnstile token was null or blank.")
                    return@withLock null
                }

                // ধাপ ৪: /unlock এন্ডপয়েন্টে রিকোয়েস্ট পাঠিয়ে বাটন ও cf_pass সংগ্রহ (অরিজিনাল লজিক)
                val unlockUrl = if (cleanUrl.endsWith("/")) "${cleanUrl}unlock" else "$cleanUrl/unlock"
                val formMediaType = "application/x-www-form-urlencoded".toMediaType()
                val unlockBody = "ts=${URLEncoder.encode(cfToken, "UTF-8")}&bc=${URLEncoder.encode(nToken, "UTF-8")}"

                val unlockReq = Request.Builder()
                    .url(unlockUrl)
                    .post(unlockBody.toRequestBody(formMediaType))
                    .header("User-Agent", userAgent)
                    .header("Referer", cleanUrl)
                    .header("Origin", domainOrigin)
                    .header("X-Requested-With", "XMLHttpRequest")
                    .header("Accept", "application/json, text/javascript, */*; q=0.01")
                    .build()

                var unlockHtml = ""
                var cfPassValue = ""
                var rawUnlockResp = ""

                sessionClient.newCall(unlockReq).execute().use { resp ->
                    for (header in resp.headers("Set-Cookie")) {
                        val matcher = Regex("""cf_pass=([^;]+)""").find(header)
                        if (matcher != null) {
                            cfPassValue = matcher.groupValues[1].trim()
                            break
                        }
                    }

                    rawUnlockResp = resp.body?.string().orEmpty()
                    if (rawUnlockResp.isNotBlank()) {
                        try {
                            val json = JSONObject(rawUnlockResp)
                            if (json.optBoolean("ok", false)) {
                                unlockHtml = json.optString("html", "")
                            }
                            if (cfPassValue.isEmpty() && json.has("cf_pass")) {
                                cfPassValue = json.optString("cf_pass", "").trim()
                            }
                        } catch (_: Throwable) {}
                    }
                }

                if (cfPassValue.isEmpty()) {
                    val currentHost = URI(cleanUrl).host
                    val storedCookies = freshCookieStore[currentHost] ?: emptyList()
                    cfPassValue = storedCookies.firstOrNull { it.name == "cf_pass" }?.value.orEmpty()
                }

                sendTelegramLog(
                    "5. Unlock Original Response",
                    "<b>cf_pass:</b> <code>$cfPassValue</code>\n<b>Raw Response:</b>\n<pre>${rawUnlockResp.take(2500)}</pre>"
                )

                if (unlockHtml.isBlank()) return@withLock null

                // ধাপ ৫: বাটনগুলো পার্স করা এবং ফেক হানিপট (aria-hidden="true") বাদ দেওয়া
                val cleanedHtml = unlockHtml.replace(
                    Regex("""<div[^>]*aria-hidden\s*=\s*['"]true['"][^>]*>[\s\S]*?</div>""", RegexOption.IGNORE_CASE),
                    ""
                )

                // দৃশ্যমান FAST CLOUD [FSL] এবং FAST CLOUD [V2] উভয় বাটনই নেওয়া
                val fastCloudRegex = Regex(
                    """<a\s+[^>]*href="([^"]+)"[^>]*class="[^"]*fsl-btn[^"]*"[^>]*>[\s\S]*?FAST CLOUD[\s\S]*?</a>""",
                    RegexOption.IGNORE_CASE
                )

                val candidateList = mutableListOf<String>()
                for (m in fastCloudRegex.findAll(cleanedHtml)) {
                    val p = m.groupValues[1].trim()
                    if (p.isNotEmpty() && !candidateList.contains(p)) {
                        candidateList.add(p)
                    }
                }

                // ব্যাকআপ: যদি ক্লিন করা অংশে না পাওয়া যায় তবে পুরো HTML থেকে নেওয়া
                for (m in fastCloudRegex.findAll(unlockHtml)) {
                    val p = m.groupValues[1].trim()
                    if (p.isNotEmpty() && !candidateList.contains(p)) {
                        candidateList.add(p)
                    }
                }

                sendTelegramLog(
                    "6. Parsed Candidates",
                    "<b>Total Found:</b> ${candidateList.size}\n<b>List:</b>\n<code>${candidateList.joinToString("\n")}</code>"
                )

                if (candidateList.isEmpty()) return@withLock null

                // ধাপ ৬: ব্রাউজারের জাভাস্ক্রিপ্টের ৫,৫০০ মি.সে. (৫.৫ সেকেন্ড) টাইম-লক অপেক্ষা করা
                // এই ডিলে না দিলে সার্ভার সাথে সাথে বট ধরে ফেলে এবং ফেক r2.dev লিংক দেয়
                sendTelegramLog("7. Browser Timer Simulation", "Waiting 5,600ms to match client JS setTimeout(5500)...")
                delay(5600L)

                // ধাপ ৭: রিডাইরেকশন টেস্ট করে আসল ভিডিও লিঙ্ক বের করা
                val noRedirectClient = sessionClient.newBuilder()
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .build()

                val cookieHeaderBuilder = StringBuilder()
                if (cfPassValue.isNotEmpty()) {
                    cookieHeaderBuilder.append("cf_pass=$cfPassValue; ")
                }

                val testHttpUrl = fixUrl(candidateList.first(), domainOrigin).toHttpUrlOrNull()
                if (testHttpUrl != null) {
                    val jarCookies = scopedCookieJar.loadForRequest(testHttpUrl)
                    for (c in jarCookies) {
                        if (c.name != "cf_pass") {
                            cookieHeaderBuilder.append("${c.name}=${c.value}; ")
                        }
                    }
                }
                val resolvedCookieStr = cookieHeaderBuilder.toString().trimEnd(';', ' ')

                var resolvedStreamUrl = ""
                val redirectLogs = StringBuilder()

                for (path in candidateList) {
                    val fullPath = fixUrl(path, domainOrigin)
                    val directReqBuilder = Request.Builder()
                        .url(fullPath)
                        .header("User-Agent", userAgent)
                        .header("Referer", cleanUrl)
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")

                    if (resolvedCookieStr.isNotBlank()) {
                        directReqBuilder.header("Cookie", resolvedCookieStr)
                    }

                    try {
                        noRedirectClient.newCall(directReqBuilder.build()).execute().use { resp ->
                            val statusCode = resp.code
                            val location = resp.header("Location").orEmpty().trim()
                            redirectLogs.append("<b>Path:</b> <code>$path</code>\n<b>Code:</b> $statusCode\n<b>Loc:</b> <code>$location</code>\n---\n")

                            if (statusCode in 300..399 && location.isNotEmpty()) {
                                val resolvedLoc = fixUrl(location, domainOrigin)
                                val isFakeLink = resolvedLoc.contains("pub-10da0f41c8ea4953ac9ba4a3ec208325.r2.dev", ignoreCase = true) ||
                                        resolvedLoc.contains("Watch%20Online", ignoreCase = true) ||
                                        resolvedLoc.contains("Watch Online", ignoreCase = true)

                                if (!isFakeLink && resolvedStreamUrl.isEmpty()) {
                                    resolvedStreamUrl = resolvedLoc
                                }
                            }
                        }
                    } catch (e: Throwable) {
                        redirectLogs.append("<b>Path:</b> <code>$path</code>\n<b>Error:</b> ${e.message}\n---\n")
                    }
                }

                sendTelegramLog(
                    "8. Final Resolution",
                    "<b>Redirect Verifications:</b>\n$redirectLogs\n<b>Resolved URL:</b> <code>$resolvedStreamUrl</code>\n<b>Cookies:</b> <code>$resolvedCookieStr</code>"
                )

                if (resolvedStreamUrl.isEmpty()) return@withLock null

                val finalHeaders = mutableMapOf(
                    "User-Agent" to userAgent,
                    "Referer" to "$domainOrigin/"
                )
                if (resolvedCookieStr.isNotBlank()) {
                    finalHeaders["Cookie"] = resolvedCookieStr
                }

                StreamItem(
                    quality = "FastCloud FSL",
                    streamUrl = resolvedStreamUrl,
                    headers = finalHeaders
                )
            } catch (t: Throwable) {
                sendTelegramLog("CRASH ERROR", "Exception: <code>${t.message}</code>")
                null
            }
        }
    }
}
