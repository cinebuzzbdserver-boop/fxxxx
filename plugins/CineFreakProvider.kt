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
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class CineFreakProvider : MainAPI {
    override var name = "CineFreak"
    override var mainUrl = "https://cinefreak.net"

    private val userAgent =
        "Mozilla/5.0 (Linux; Android 16; 23090RA98I Build/BP2A.250605.031.A3) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/155.0.8059.43 Mobile Safari/537.36"

    private val resolveMutex = Mutex()

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
            if (!referer.isNullOrEmpty()) reqBuilder.header("Referer", referer)
            baseClient.newCall(reqBuilder.build()).execute().use { r -> r.body?.string().orEmpty() }
        } catch (_: Exception) { "" }
    }

    private fun decodeHtmlEntities(text: String): String = text.trim()
        .replace("&#8211;", "–").replace("&#8212;", "—")
        .replace("&#8217;", "'").replace("&#039;", "'")
        .replace("&quot;", "\"").replace("&amp;", "&")
        .replace("&#038;", "&").replace("&nbsp;", " ")

    private fun fixUrl(url: String, base: String): String {
        val t = url.trim()
        if (t.isEmpty()) return ""
        val r = when {
            t.startsWith("http") -> t
            t.startsWith("//") -> "https:$t"
            t.startsWith("/") -> "$base$t"
            else -> "$base/$t"
        }
        return if (r.contains("image.tmdb.org/t/p/"))
            r.replace(Regex("/t/p/[a-zA-Z0-9]+(?:_[a-zA-Z0-9]+)?/"), "/t/p/original/")
        else r
    }

    private fun parseMainPageItems(html: String, base: String): List<MovieItem> {
        val list = mutableListOf<MovieItem>()
        val cardRegex = Regex("""<a\s+[^>]*href="([^"]+)"[^>]*class="[^"]*movie-card[^"]*"[^>]*>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE)
        val titleRegex = Regex("""<h3[^>]*class="[^"]*movie-card-title[^"]*"[^>]*>([\s\S]*?)</h3>""", RegexOption.IGNORE_CASE)
        val imgRegex = Regex("""<img[^>]+src="([^"]+)"""", RegexOption.IGNORE_CASE)
        for (match in cardRegex.findAll(html)) {
            val rawUrl = match.groupValues[1].trim()
            val inner = match.groupValues[2]
            val title = titleRegex.find(inner)?.groupValues?.get(1)?.replace(Regex("<[^>]*>"), "")?.trim().orEmpty()
            val img = imgRegex.find(inner)?.groupValues?.get(1)?.trim().orEmpty()
            if (rawUrl.isNotEmpty() && title.isNotEmpty()) {
                list.add(MovieItem(
                    title = decodeHtmlEntities(title),
                    image = fixUrl(img, base),
                    url = fixUrl(rawUrl, base),
                    isPoster = true, referer = null
                ))
            }
        }
        return list.distinctBy { it.url }
    }

    override suspend fun getMainPage(page: Int): List<MovieItem> {
        val base = getBaseUrl()
        val url = if (page <= 1) "$base/" else "$base/page/$page/"
        return parseMainPageItems(fetchHtml(url), base)
    }

    override suspend fun search(query: String, page: Int): List<MovieItem> = withContext(Dispatchers.IO) {
        val base = getBaseUrl()
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val pg = if (page < 1) 1 else page
        try {
            val req = Request.Builder()
                .url("$base/search-api.php?q=$q&pg=$pg")
                .header("User-Agent", userAgent)
                .header("Referer", "$base/")
                .header("Accept", "application/json, text/javascript, */*; q=0.01")
                .header("X-Requested-With", "XMLHttpRequest")
                .build()
            val str = baseClient.newCall(req).execute().use { it.body?.string().orEmpty() }
            if (str.isBlank()) return@withContext emptyList()
            val root = JSONObject(str)
            val arr = root.optJSONArray("results") ?: return@withContext emptyList()
            val list = mutableListOf<MovieItem>()
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val t = item.optString("t", "").trim()
                val p = item.optString("l", "").trim()
                val im = item.optString("i", "").trim()
                if (t.isNotEmpty() && p.isNotEmpty()) {
                    val full = if (p.startsWith("http")) p else "$base/$p/"
                    list.add(MovieItem(
                        title = decodeHtmlEntities(t),
                        image = fixUrl(im, base),
                        url = full, isPoster = true, referer = null
                    ))
                }
            }
            list.distinctBy { it.url }
        } catch (_: Throwable) { emptyList() }
    }

    override suspend fun loadMediaStructure(postUrl: String): MediaStructure = withContext(Dispatchers.IO) {
        val html = fetchHtml(postUrl)
        val rawLinks = mutableListOf<Pair<String, String>>()
        val sectionRegex = Regex(
            """<h4\s+class="movie-title"[^>]*>([\s\S]*?)</h4>[\s\S]*?<div\s+class="dlbtn-container"[^>]*>([\s\S]*?)</div>""",
            RegexOption.IGNORE_CASE
        )
        val btnRegex = Regex(
            """<a\s+[^>]*href="([^"]+)"[^>]*class="[^"]*dlbtn-download[^"]*"[^>]*>""",
            RegexOption.IGNORE_CASE
        )
        for (m in sectionRegex.findAll(html)) {
            val title = m.groupValues[1].replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim()
            val container = m.groupValues[2]
            btnRegex.find(container)?.let { d ->
                val u = d.groupValues[1].trim()
                if (u.startsWith("http")) rawLinks.add(Pair(decodeHtmlEntities(title).ifEmpty { "HD Quality" }, u))
            }
        }
        if (rawLinks.isEmpty()) {
            val directRegex = Regex(
                """<a\s+[^>]*href="(https?://[^"]*cinecloud\.[^/]+/f/[^"]+)"[^>]*class="[^"]*dlbtn-download[^"]*"[^>]*>""",
                RegexOption.IGNORE_CASE
            )
            for (m in directRegex.findAll(html)) rawLinks.add(Pair("Direct Download", m.groupValues[1].trim()))
        }
        MediaStructure(false, rawLinks.distinctBy { it.second }, emptyList())
    }

    private fun extractTurnstileSiteKey(html: String): String? {
        val patterns = listOf(
            Regex("""data-sitekey\s*=\s*['"](0x[a-zA-Z0-9_-]+)['"]""", RegexOption.IGNORE_CASE),
            Regex("""sitekey\s*:\s*['"](0x[a-zA-Z0-9_-]+)['"]""", RegexOption.IGNORE_CASE),
            Regex("""['"](0x4[a-zA-Z0-9_-]{10,})['"]""")
        )
        for (p in patterns) p.find(html)?.groupValues?.get(1)?.trim()?.let { if (it.isNotEmpty()) return it }
        return null
    }

    private suspend fun requestTurnstileToken(siteKey: String, origin: String): String? {
        try {
            val t = TurnstileBridge.resolveToken(siteKey, origin, 120_000L)
            if (!t.isNullOrBlank()) return t.trim()
        } catch (_: Throwable) {}
        System.setProperty("moviefy.turnstile.token", "")
        System.setProperty("moviefy.turnstile.siteKey", siteKey)
        System.setProperty("moviefy.turnstile.domainUrl", origin)
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < 120_000L) {
            val s = System.getProperty("moviefy.turnstile.token")?.trim().orEmpty()
            if (s.isNotEmpty()) { System.setProperty("moviefy.turnstile.token", ""); return s }
            delay(250L)
        }
        return null
    }

    /** Honeypot ডিটেকশন: ৫টি সিগনেচার */
    private fun isHoneypot(html: String): Boolean {
        val l = html.lowercase()
        if (l.contains("aria-hidden=\"true\"") || l.contains("aria-hidden='true'")) return true
        if (l.contains("tabindex=\"-1\"") || l.contains("tabindex='-1'")) return true
        if (l.contains("c9bbb55")) return true
        if (l.contains("-10000px")) return true
        if (l.contains("display:none") || l.contains("display: none")) return true
        if (l.contains("visibility:hidden") || l.contains("visibility: hidden")) return true
        if (l.matches(Regex(""".*\bhidden\b.*"""))) {
            // শুধু hidden attribute (attr হিসেবে)
            if (Regex("""\shidden(\s|>|/|$)""", RegexOption.IGNORE_CASE).containsMatchIn(html)) return true
        }
        return false
    }

    /** unlock HTML থেকে visible FAST CLOUD লিংক */
    private fun extractVisibleLinks(html: String): List<String> {
        val result = mutableListOf<String>()
        // পুরো HTML-এ সব <a ...fsl-btn...>FAST CLOUD...</a> স্ক্যান
        val anchorRegex = Regex("""<a\s+([^>]*)>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE)
        for (m in anchorRegex.findAll(html)) {
            val attrs = m.groupValues[1]
            val inner = m.groupValues[2]
            if (!attrs.contains("fsl-btn", ignoreCase = true)) continue
            if (!inner.contains("FAST CLOUD", ignoreCase = true)) continue
            if (isHoneypot(attrs + " " + inner)) continue
            Regex("""href\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(attrs)?.groupValues?.get(1)?.trim()?.let { h ->
                    if (h.isNotEmpty() && !result.contains(h)) result.add(h)
                }
        }
        return result
    }

    private fun isFakeRedirect(location: String): Boolean {
        val l = location.lowercase()
        if (l.contains("pub-") && l.contains(".r2.dev")) return true
        if (l.contains("watch online") || l.contains("watch%20online")) return true
        if (l.contains("/watch-online") || l.contains("/watch_online")) return true
        if (l.contains("dummy")) return true
        return false
    }

    override suspend fun resolveDirectLink(generateUrl: String): StreamItem? = withContext(Dispatchers.IO) {
        if (generateUrl.isBlank()) return@withContext null

        resolveMutex.withLock {
            val cookieStore = ConcurrentHashMap<String, MutableList<Cookie>>()
            val cookieJar = object : CookieJar {
                override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                    val list = cookieStore.getOrPut(url.host) { mutableListOf() }
                    synchronized(list) {
                        for (c in cookies) { list.removeAll { it.name == c.name }; list.add(c) }
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

            val sessionClient = OkHttpClient.Builder()
                .cookieJar(cookieJar)
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()

            try {
                val cleanUrl = generateUrl.trim()
                val uri = URI(cleanUrl)
                val origin = "${uri.scheme}://${uri.host}"
                val basePath = if (cleanUrl.endsWith("/")) cleanUrl else cleanUrl

                sendTelegramLog("1. Target", "<b>URL:</b> <code>$cleanUrl</code>")

                // ── STEP 1: Pre-solve পেজ GET (Turnstile widget সহ) ──
                val landingHtml = try {
                    sessionClient.newCall(Request.Builder()
                        .url(cleanUrl)
                        .header("User-Agent", userAgent)
                        .header("Referer", "$origin/")
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                        .header("Accept-Language", "en-US,en;q=0.9,bn;q=0.8")
                        .header("Upgrade-Insecure-Requests", "1")
                        .header("Sec-Fetch-Site", "same-origin")
                        .header("Sec-Fetch-Mode", "navigate")
                        .header("Sec-Fetch-Dest", "document")
                        .header("Sec-Fetch-User", "?1")
                        .build()).execute().use { it.body?.string().orEmpty() }
                } catch (e: Throwable) {
                    sendTelegramLog("STEP1 FAIL", "${e.message}"); return@withLock null
                }

                val siteKey = extractTurnstileSiteKey(landingHtml) ?: "0x4AAAAAAFUBbDesHlySPLsN"

                // ── STEP 2: view-ping (fire-and-forget) ──
                try {
                    sessionClient.newCall(Request.Builder()
                        .url("${basePath}/view-ping")
                        .header("User-Agent", userAgent)
                        .header("Referer", cleanUrl)
                        .header("Accept", "*/*")
                        .header("Accept-Language", "en-US,en;q=0.9,bn;q=0.8")
                        .header("Sec-Fetch-Site", "same-origin")
                        .header("Sec-Fetch-Mode", "no-cors")
                        .header("Sec-Fetch-Dest", "empty")
                        .build()).execute().close()
                } catch (_: Throwable) {}

                // ── STEP 3: Turnstile solve ──
                val cfToken = requestTurnstileToken(siteKey, origin)
                if (cfToken.isNullOrBlank()) {
                    sendTelegramLog("STEP3 FAIL", "Turnstile token null"); return@withLock null
                }
                sendTelegramLog("3. Turnstile", "Token OK (${cfToken.take(20)}...)")

                // ── STEP 4: /n (retry 600ms × 2) ──
                suspend fun getN(): String {
                    repeat(3) { attempt ->
                        try {
                            val req = Request.Builder()
                                .url("${basePath}/n")
                                .header("User-Agent", userAgent)
                                .header("Referer", cleanUrl)
                                .header("Accept", "application/json, text/javascript, */*; q=0.01")
                                .header("Accept-Language", "en-US,en;q=0.9,bn;q=0.8")
                                .header("X-Requested-With", "XMLHttpRequest")
                                .header("Sec-Fetch-Site", "same-origin")
                                .header("Sec-Fetch-Mode", "cors")
                                .header("Sec-Fetch-Dest", "empty")
                                .build()
                            val body = sessionClient.newCall(req).execute().use { it.body?.string().orEmpty() }
                            if (body.isNotBlank()) {
                                val n = JSONObject(body).optString("n", "").trim()
                                if (n.isNotEmpty()) return n
                            }
                        } catch (_: Throwable) {}
                        if (attempt < 2) delay(600L)
                    }
                    return ""
                }

                var nToken = getN()
                if (nToken.isEmpty()) {
                    sendTelegramLog("STEP4 FAIL", "'n' token empty"); return@withLock null
                }
                sendTelegramLog("4. n Token", "<code>${nToken.take(30)}...</code>")

                // ── STEP 5: /unlock POST (exactly browser style) ──
                var unlockHtml = ""
                var rawResp = ""
                var renonce = false

                fun doUnlock(ts: String, bc: String): Pair<Boolean, Boolean> {
                    return try {
                        val body = "ts=${URLEncoder.encode(ts, "UTF-8")}&bc=${URLEncoder.encode(bc, "UTF-8")}"
                            .toRequestBody("application/x-www-form-urlencoded".toMediaType())
                        val resp = sessionClient.newCall(Request.Builder()
                            .url("${basePath}/unlock")
                            .post(body)
                            .header("User-Agent", userAgent)
                            .header("Referer", cleanUrl)
                            .header("Origin", origin)
                            .header("Accept", "*/*")
                            .header("Accept-Language", "en-US,en;q=0.9,bn;q=0.8")
                            .header("X-Requested-With", "XMLHttpRequest")
                            .header("Sec-Fetch-Site", "same-origin")
                            .header("Sec-Fetch-Mode", "cors")
                            .header("Sec-Fetch-Dest", "empty")
                            .build()).execute()
                        rawResp = resp.body?.string().orEmpty()
                        if (rawResp.isBlank()) return Pair(false, false)
                        val j = JSONObject(rawResp)
                        if (j.optBoolean("ok", false)) {
                            unlockHtml = j.optString("html", "")
                            Pair(true, false)
                        } else {
                            renonce = j.optBoolean("renonce", false)
                            Pair(false, renonce)
                        }
                    } catch (_: Throwable) { Pair(false, false) }
                }

                var success = false
                var tries = 0
                while (tries < 3 && !success) {
                    val (ok, needRenonce) = doUnlock(cfToken, nToken)
                    success = ok
                    if (!ok && needRenonce) {
                        delay(800L)
                        nToken = getN()
                        if (nToken.isEmpty()) break
                    } else if (!ok) {
                        break
                    }
                    tries++
                }

                sendTelegramLog("5. Unlock Response", "<b>ok:</b> $success\n<b>Raw:</b>\n<pre>${rawResp.take(2000)}</pre>")
                if (!success || unlockHtml.isBlank()) return@withLock null

                // ── STEP 6: visible honeypot-free লিংক বের করা ──
                val links = extractVisibleLinks(unlockHtml)
                sendTelegramLog("6. Visible Links", "<b>Count:</b> ${links.size}\n<code>${links.joinToString("\n")}</code>")
                if (links.isEmpty()) return@withLock null

                // ── STEP 7: 5.5s wait (JS setTimeout(5500)) ──
                sendTelegramLog("7. Timer Simulation", "Waiting 5,600ms (matches JS)...")
                delay(5600L)

                // ── STEP 8: GET /f/xxx follow redirects (a.click() ব্রাউজার GET) ──
                var finalUrl = ""
                val logs = StringBuilder()
                for (href in links) {
                    val full = fixUrl(href, origin)
                    try {
                        sessionClient.newCall(Request.Builder()
                            .url(full)
                            .header("User-Agent", userAgent)
                            .header("Referer", cleanUrl)
                            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                            .header("Accept-Language", "en-US,en;q=0.9,bn;q=0.8")
                            .header("Upgrade-Insecure-Requests", "1")
                            .header("Sec-Fetch-Site", "same-origin")
                            .header("Sec-Fetch-Mode", "navigate")
                            .header("Sec-Fetch-Dest", "document")
                            .header("Sec-Fetch-User", "?1")
                            .build()).execute().use { resp ->
                                val reqUrl = resp.request.url.toString()
                                val code = resp.code
                                logs.append("<b>→</b> <code>$href</code>\n<b>  final:</b> <code>$reqUrl</code>\n<b>  code:</b> $code\n")
                                if (!isFakeRedirect(reqUrl) && finalUrl.isEmpty() && resp.isSuccessful) {
                                    finalUrl = reqUrl
                                }
                            }
                    } catch (e: Throwable) {
                        logs.append("<b>→</b> <code>$href</code> ERROR: ${e.message}\n")
                    }
                }

                sendTelegramLog("8. Final", "$logs\n<b>URL:</b> <code>$finalUrl</code>")

                if (finalUrl.isEmpty()) return@withLock null
                if (isFakeRedirect(finalUrl)) {
                    sendTelegramLog("8. FAKE REJECTED", "<code>$finalUrl</code>")
                    return@withLock null
                }

                StreamItem(
                    quality = "FastCloud FSL",
                    streamUrl = finalUrl,
                    headers = mapOf(
                        "User-Agent" to userAgent,
                        "Referer" to "$origin/"
                    )
                )
            } catch (t: Throwable) {
                sendTelegramLog("CRASH", "${t.message}")
                null
            }
        }
    }
}
