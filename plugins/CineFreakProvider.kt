package com.moviefy.stream.providers

import android.util.Base64
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
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class CineFreakProvider : MainAPI {
    override var name = "CineFreak"
    override var mainUrl = "https://cinefreak.net"

    private val userAgent =
        "Mozilla/5.0 (Linux; Android 16; 23090RA98I Build/BP2A.250605.031.A3) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/155.0.8059.43 Mobile Safari/537.36"

    private val resolveMutex = Mutex()

    // ── Telegram debug (শুধু vidzer ফ্লোতে) ──
    private val telegramBotToken = "8594119995:AAHN_cErFPbcFwU0qJhK_zzWuKahTqvV9Ec"
    private val telegramChatId = "5548923721"
    private val debugEnabled = true   // debug শেষ হলে false করে দিন

    private val baseClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private suspend fun sendTelegramLog(step: String, details: String) = withContext(Dispatchers.IO) {
        if (!debugEnabled) return@withContext
        try {
            val text = buildString {
                append("<b>[CineFreak • vidzer] ").append(step).append("</b>\n\n")
                append(details)
            }
            val fb = FormBody.Builder()
                .add("chat_id", telegramChatId)
                .add("text", text.take(3800))
                .add("parse_mode", "HTML")
                .build()
            baseClient.newCall(Request.Builder()
                .url("https://api.telegram.org/bot$telegramBotToken/sendMessage")
                .post(fb).build()).execute().close()
        } catch (_: Throwable) {}
    }

    private fun esc(s: String, max: Int = 1200): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").take(max)

    // ═══════════════════════════════════════════════════════════
    // BASIC HELPERS (cinecloud ফ্লোর জন্য)
    // ═══════════════════════════════════════════════════════════

    private fun getBaseUrl(): String {
        val clean = mainUrl.trim().trimEnd('/')
        return if (clean.isEmpty()) "https://cinefreak.net" else clean
    }

    private suspend fun fetchHtml(url: String, referer: String? = null): String = withContext(Dispatchers.IO) {
        try {
            val b = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            if (!referer.isNullOrEmpty()) b.header("Referer", referer)
            baseClient.newCall(b.build()).execute().use { it.body?.string().orEmpty() }
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
            val arr = JSONObject(str).optJSONArray("results") ?: return@withContext emptyList()
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

    // ═══════════════════════════════════════════════════════════
    // loadMediaStructure — vidzer সবার আগে, তারপর cinecloud links
    // ═══════════════════════════════════════════════════════════

    override suspend fun loadMediaStructure(postUrl: String): MediaStructure = withContext(Dispatchers.IO) {
        val html = fetchHtml(postUrl)
        val rawLinks = mutableListOf<Pair<String, String>>()

        // ── vidzer config স্ক্র্যাপ (সবার আগে) ──
        val playApi = Regex("""const\s+PLAY_API\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)?.replace("\\/", "/")?.trim()
        val tsKey = Regex("""const\s+TS_SITE_KEY\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)?.trim()
        val sources = Regex(""""sources"\s*:\s*"([^"]+)"""")
            .find(html)?.groupValues?.get(1)?.trim() ?: "m"

        if (!playApi.isNullOrBlank() && !tsKey.isNullOrBlank()) {
            val encoded = "vidzer://$playApi|$tsKey|$sources|$postUrl"
            rawLinks.add(Pair("▶ Watch Online (FastCloud Stream)", encoded))
        }

        // ── cinecloud download links (আগের মতোই) ──
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
        if (rawLinks.size <= 1) {
            val directRegex = Regex(
                """<a\s+[^>]*href="(https?://[^"]*cinecloud\.[^/]+/f/[^"]+)"[^>]*class="[^"]*dlbtn-download[^"]*"[^>]*>""",
                RegexOption.IGNORE_CASE
            )
            for (m in directRegex.findAll(html)) {
                val pair = Pair("Direct Download", m.groupValues[1].trim())
                if (!rawLinks.any { it.second == pair.second }) rawLinks.add(pair)
            }
        }

        MediaStructure(false, rawLinks.distinctBy { it.second }, emptyList())
    }

    // ═══════════════════════════════════════════════════════════
    // Turnstile helper
    // ═══════════════════════════════════════════════════════════

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

    // ═══════════════════════════════════════════════════════════
    // AES-GCM + base64url
    // ═══════════════════════════════════════════════════════════

    private fun base64UrlDecode(s: String): ByteArray {
        val padded = s.replace('-', '+').replace('_', '/')
        val pad = (4 - padded.length % 4) % 4
        return Base64.decode(padded + "=".repeat(pad), Base64.NO_WRAP)
    }

    private fun aesGcmDecrypt(ciphertext: ByteArray, key: ByteArray): ByteArray {
        require(ciphertext.size > 12) { "ciphertext too short: ${ciphertext.size}" }
        val iv = ciphertext.copyOfRange(0, 12)
        val body = ciphertext.copyOfRange(12, ciphertext.size)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return c.doFinal(body)
    }

    // ═══════════════════════════════════════════════════════════
    // vidzer ফ্লো — ব্রাউজারের হুবহু আচরণ
    // ═══════════════════════════════════════════════════════════

    private suspend fun resolveVidzerStream(
        playApi: String,
        tsKey: String,
        ep: String,
        postUrlForReferer: String
    ): StreamItem? = withContext(Dispatchers.IO) {
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
        val client = OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

        try {
            sendTelegramLog(
                "1. START",
                "<b>playApi:</b> <code>${esc(playApi, 200)}</code>\n" +
                "<b>tsKey:</b> <code>$tsKey</code>\n" +
                "<b>ep:</b> <code>$ep</code>\n" +
                "<b>postUrl:</b> <code>${esc(postUrlForReferer, 200)}</code>"
            )

            // STEP 0: পোস্ট পেজে GET — session cookies লোড
            try {
                val warmHtml = client.newCall(Request.Builder()
                    .url(postUrlForReferer)
                    .header("User-Agent", userAgent)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.9,bn;q=0.8")
                    .header("Sec-Fetch-Site", "none")
                    .header("Sec-Fetch-Mode", "navigate")
                    .header("Sec-Fetch-Dest", "document")
                    .build()).execute().use { it.body?.string().orEmpty() }
                sendTelegramLog("2. Warm Session", "post page loaded, len=${warmHtml.length}")
            } catch (e: Throwable) {
                sendTelegramLog("2. Warm Session FAIL", "${e.message}")
            }

            // STEP 1: প্রথমে ts ছাড়াই /play POST (ব্রাউজারের মতো)
            sendTelegramLog("3. First /play (no ts)", "Trying without Turnstile...")
            val body1 = "op=play&ep=${URLEncoder.encode(ep, "UTF-8")}"
                .toRequestBody("application/x-www-form-urlencoded".toMediaType())

            var playCode = 0
            var playResp = ""
            try {
                client.newCall(Request.Builder()
                    .url(playApi)
                    .post(body1)
                    .header("User-Agent", userAgent)
                    .header("Referer", postUrlForReferer)
                    .header("Origin", "https://cinefreak.net")
                    .header("Accept", "*/*")
                    .header("Accept-Language", "en-US,en;q=0.9,bn;q=0.8")
                    .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
                    .header("X-Requested-With", "XMLHttpRequest")
                    .header("Sec-Fetch-Site", "same-origin")
                    .header("Sec-Fetch-Mode", "cors")
                    .header("Sec-Fetch-Dest", "empty")
                    .build()).execute().use {
                        playCode = it.code
                        playResp = it.body?.string().orEmpty()
                    }
            } catch (e: Throwable) {
                sendTelegramLog("3. NETWORK FAIL", "${e.message}")
                return@withContext null
            }
            sendTelegramLog(
                "3. First /play response",
                "<b>HTTP:</b> $playCode\n<pre>${esc(playResp, 1200)}</pre>"
            )

            if (playResp.isBlank()) return@withContext null
            var playJson = try { JSONObject(playResp) } catch (_: Throwable) { JSONObject() }

            // STEP 2: 401 + need=ts → Turnstile solve করে retry
            if (playCode == 401 && playJson.optString("need") == "ts") {
                sendTelegramLog("4. Turnstile Required", "Solving with sitekey=$tsKey, origin=https://cinefreak.net")
                val cfToken = requestTurnstileToken(tsKey, "https://cinefreak.net")
                if (cfToken.isNullOrBlank()) {
                    sendTelegramLog("4a. TURNSTILE FAILED", "token null/blank")
                    return@withContext null
                }
                sendTelegramLog("4b. Turnstile OK", "<code>${esc(cfToken, 200)}</code>")

                // ছোট delay — ব্রাউজারের natural timing মিমিক
                delay(400L)

                val body2 = "op=play&ep=${URLEncoder.encode(ep, "UTF-8")}&ts=${URLEncoder.encode(cfToken, "UTF-8")}"
                    .toRequestBody("application/x-www-form-urlencoded".toMediaType())
                try {
                    client.newCall(Request.Builder()
                        .url(playApi)
                        .post(body2)
                        .header("User-Agent", userAgent)
                        .header("Referer", postUrlForReferer)
                        .header("Origin", "https://cinefreak.net")
                        .header("Accept", "*/*")
                        .header("Accept-Language", "en-US,en;q=0.9,bn;q=0.8")
                        .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
                        .header("X-Requested-With", "XMLHttpRequest")
                        .header("Sec-Fetch-Site", "same-origin")
                        .header("Sec-Fetch-Mode", "cors")
                        .header("Sec-Fetch-Dest", "empty")
                        .build()).execute().use {
                            playCode = it.code
                            playResp = it.body?.string().orEmpty()
                        }
                } catch (e: Throwable) {
                    sendTelegramLog("4c. RETRY NETWORK FAIL", "${e.message}")
                    return@withContext null
                }
                sendTelegramLog(
                    "4d. Retry /play response",
                    "<b>HTTP:</b> $playCode\n<pre>${esc(playResp, 1500)}</pre>"
                )
                playJson = try { JSONObject(playResp) } catch (_: Throwable) { JSONObject() }

                if (playCode == 401) {
                    sendTelegramLog("4e. STILL 401", "TurnstileBridge session mismatch")
                    return@withContext null
                }
            }

            // STEP 3: vidzer URL হ্যান্ডেল
            return@withContext handleVidzerPlayJson(
                playJson, playApi, tsKey, ep, postUrlForReferer, client, cookieJar
            )
        } catch (t: Throwable) {
            sendTelegramLog("CRASH", "${t.stackTraceToString().take(1500)}")
            null
        }
    }

    private suspend fun handleVidzerPlayJson(
        playJson: JSONObject,
        playApi: String,
        tsKey: String,
        ep: String,
        postUrlForReferer: String,
        client: OkHttpClient,
        cookieJar: CookieJar
    ): StreamItem? = withContext(Dispatchers.IO) {
        val state = playJson.optString("state", "").trim()
        val vidzerUrl = playJson.optString("url", "").trim()

        sendTelegramLog(
            "5. /play RESULT",
            "<b>state:</b> <code>$state</code>\n<b>vidzerUrl:</b> <code>${esc(vidzerUrl, 250)}</code>"
        )

        if (state != "ready" || vidzerUrl.isEmpty()) {
            sendTelegramLog(
                "5a. NOT READY",
                "<pre>${esc(playJson.toString(), 1200)}</pre>"
            )
            return@withContext null
        }

        // STEP 4: vidzer /w/{token} HTML
        val vidzerHtml = try {
            client.newCall(Request.Builder()
                .url(vidzerUrl)
                .header("User-Agent", userAgent)
                .header("Referer", "https://cinefreak.net/")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9,bn;q=0.8")
                .header("Upgrade-Insecure-Requests", "1")
                .header("Sec-Fetch-Site", "cross-site")
                .header("Sec-Fetch-Mode", "navigate")
                .header("Sec-Fetch-Dest", "iframe")
                .build()).execute().use { it.body?.string().orEmpty() }
        } catch (e: Throwable) {
            sendTelegramLog("6. VIDZER GET FAIL", "${e.message}")
            return@withContext null
        }

        val qk = Regex("""qk:\s*["']([^"']+)["']""").find(vidzerHtml)?.groupValues?.get(1)?.trim()
        val apiUrlRaw = Regex("""apiUrl:\s*["']([^"']+)["']""").find(vidzerHtml)?.groupValues?.get(1)
            ?.replace("\\/", "/")?.trim()

        sendTelegramLog(
            "6. VIDZER SCRAPED",
            "<b>qk:</b> <code>${esc(qk ?: "NULL", 120)}</code>\n" +
            "<b>apiUrl:</b> <code>${esc(apiUrlRaw ?: "NULL", 250)}</code>\n" +
            "<b>html len:</b> ${vidzerHtml.length}"
        )

        if (qk.isNullOrBlank() || apiUrlRaw.isNullOrBlank()) return@withContext null

        val fullApi = if (apiUrlRaw.startsWith("http")) apiUrlRaw else "https://vidzer.me$apiUrlRaw"

        // STEP 5: ciphertext
        val cipherResp = try {
            client.newCall(Request.Builder()
                .url(fullApi)
                .header("User-Agent", userAgent)
                .header("Referer", vidzerUrl)
                .header("Accept", "*/*")
                .header("Accept-Language", "en-US,en;q=0.9,bn;q=0.8")
                .header("Sec-Fetch-Site", "same-origin")
                .header("Sec-Fetch-Mode", "cors")
                .header("Sec-Fetch-Dest", "empty")
                .build()).execute().use { it.body?.string().orEmpty() }
        } catch (e: Throwable) {
            sendTelegramLog("7. CIPHER GET FAIL", "${e.message}")
            return@withContext null
        }

        sendTelegramLog(
            "7. CIPHER RAW",
            "<b>len:</b> ${cipherResp.length}\n" +
            "<b>head:</b> <code>${esc(cipherResp.take(80), 100)}</code>\n" +
            "<b>tail:</b> <code>${esc(cipherResp.takeLast(80), 100)}</code>"
        )

        if (cipherResp.isBlank()) return@withContext null

        val cipherPart = cipherResp.trim()
            .removeSuffix("~").trim()
            .removeSuffix("$").trim()
            .substringAfterLast('.').trim()

        if (cipherPart.isEmpty()) {
            sendTelegramLog("8. CIPHER EXTRACT FAIL", "empty after last dot")
            return@withContext null
        }

        // STEP 6: AES-GCM decrypt
        val plaintext = try {
            val key = base64UrlDecode(qk.reversed())
            val ct = base64UrlDecode(cipherPart)
            aesGcmDecrypt(ct, key)
        } catch (e: Throwable) {
            sendTelegramLog(
                "9. DECRYPT FAIL",
                "<b>qk:</b> <code>${esc(qk, 120)}</code>\n" +
                "<b>ct len:</b> ${cipherPart.length}\n" +
                "<b>err:</b> ${esc(e.message ?: "", 200)}"
            )
            return@withContext null
        }

        val json = try { JSONObject(String(plaintext, Charsets.UTF_8)) } catch (e: Throwable) {
            sendTelegramLog("10. JSON FAIL", "${e.message}")
            return@withContext null
        }

        val videoUrl = when {
            json.has("videoUrl") && !json.isNull("videoUrl") -> json.optString("videoUrl", "")
            json.opt("video") is String -> json.optString("video", "")
            json.opt("video") is JSONObject -> json.getJSONObject("video").optString("url", "")
            else -> ""
        }.trim()

        val resCount = json.optJSONArray("resolutions")?.length() ?: 0
        val audioCount = json.optJSONArray("audioTracks")?.length() ?: 0
        val subCount = json.optJSONArray("subtitleTracks")?.length() ?: 0
        val keys = json.keys().asSequence().joinToString(",")

        sendTelegramLog(
            "10. DECRYPT OK",
            "<b>videoUrl:</b> <code>${esc(videoUrl, 300)}</code>\n" +
            "<b>resolutions:</b> $resCount  <b>audio:</b> $audioCount  <b>subs:</b> $subCount\n" +
            "<b>json keys:</b> ${esc(keys, 200)}"
        )

        if (videoUrl.isEmpty()) {
            sendTelegramLog("10b. NO VIDEOURL", "decrypted but videoUrl empty")
            return@withContext null
        }

        StreamItem(
            quality = "CineFreak Stream",
            streamUrl = videoUrl,
            headers = mapOf(
                "User-Agent" to userAgent,
                "Referer" to "https://cinefreak.net/",
                "Origin" to "https://cinefreak.net"
            )
        )
    }

    // ═══════════════════════════════════════════════════════════
    // resolveDirectLink — vidzer হলে নতুন ফ্লো, নাহলে cinecloud
    // ═══════════════════════════════════════════════════════════

    override suspend fun resolveDirectLink(generateUrl: String): StreamItem? = withContext(Dispatchers.IO) {
        if (generateUrl.isBlank()) return@withContext null

        // ── vidzer ফ্লো (সবার আগে) ──
        if (generateUrl.startsWith("vidzer://")) {
            val parts = generateUrl.removePrefix("vidzer://").split("|")
            if (parts.size < 4) return@withContext null
            return@withContext resolveVidzerStream(
                playApi = parts[0],
                tsKey = parts[1],
                ep = parts[2],
                postUrlForReferer = parts[3]
            )
        }

        // ── cinecloud unlock ফ্লো (অপরিবর্তিত) ──
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
                val basePath = cleanUrl

                val landingHtml = sessionClient.newCall(Request.Builder()
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

                val siteKey = extractTurnstileSiteKey(landingHtml) ?: "0x4AAAAAAFUBbDesHlySPLsN"

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

                val cfToken = requestTurnstileToken(siteKey, origin) ?: return@withLock null

                suspend fun getN(): String {
                    repeat(3) { attempt ->
                        try {
                            val body = sessionClient.newCall(Request.Builder()
                                .url("${basePath}/n")
                                .header("User-Agent", userAgent)
                                .header("Referer", cleanUrl)
                                .header("Accept", "application/json, text/javascript, */*; q=0.01")
                                .header("Accept-Language", "en-US,en;q=0.9,bn;q=0.8")
                                .header("X-Requested-With", "XMLHttpRequest")
                                .header("Sec-Fetch-Site", "same-origin")
                                .header("Sec-Fetch-Mode", "cors")
                                .header("Sec-Fetch-Dest", "empty")
                                .build()).execute().use { it.body?.string().orEmpty() }
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
                if (nToken.isEmpty()) return@withLock null

                var unlockHtml = ""

                fun doUnlock(ts: String, bc: String): Pair<Boolean, Boolean> {
                    return try {
                        val body = "ts=${URLEncoder.encode(ts, "UTF-8")}&bc=${URLEncoder.encode(bc, "UTF-8")}"
                            .toRequestBody("application/x-www-form-urlencoded".toMediaType())
                        val raw = sessionClient.newCall(Request.Builder()
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
                            .build()).execute().use { it.body?.string().orEmpty() }
                        if (raw.isBlank()) return Pair(false, false)
                        val j = JSONObject(raw)
                        if (j.optBoolean("ok", false)) {
                            unlockHtml = j.optString("html", "")
                            Pair(true, false)
                        } else {
                            Pair(false, j.optBoolean("renonce", false))
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
                    } else if (!ok) break
                    tries++
                }

                if (!success || unlockHtml.isBlank()) return@withLock null

                val links = extractVisibleLinks(unlockHtml)
                if (links.isEmpty()) return@withLock null

                var finalUrl = ""
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
                                if (finalUrl.isEmpty() && resp.isSuccessful && !isFakeRedirect(reqUrl)) {
                                    finalUrl = reqUrl
                                }
                            }
                    } catch (_: Throwable) {}
                    if (finalUrl.isNotEmpty()) break
                }

                if (finalUrl.isEmpty() || isFakeRedirect(finalUrl)) return@withLock null

                StreamItem(
                    quality = "FastCloud FSL",
                    streamUrl = finalUrl,
                    headers = mapOf(
                        "User-Agent" to userAgent,
                        "Referer" to "$origin/"
                    )
                )
            } catch (_: Throwable) {
                null
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // honeypot + fake redirect helpers (cinecloud ফ্লো)
    // ═══════════════════════════════════════════════════════════

    private fun isHoneypot(html: String): Boolean {
        val l = html.lowercase()
        if (l.contains("aria-hidden=\"true\"") || l.contains("aria-hidden='true'")) return true
        if (l.contains("tabindex=\"-1\"") || l.contains("tabindex='-1'")) return true
        if (l.contains("c9bbb55")) return true
        if (l.contains("-10000px")) return true
        if (l.contains("display:none") || l.contains("display: none")) return true
        if (l.contains("visibility:hidden") || l.contains("visibility: hidden")) return true
        if (Regex("""\shidden(\s|>|/|$)""", RegexOption.IGNORE_CASE).containsMatchIn(html)) return true
        return false
    }

    private fun extractVisibleLinks(html: String): List<String> {
        val fsl = mutableListOf<String>()
        val v2 = mutableListOf<String>()

        val anchorRegex = Regex("""<a\s+([^>]*)>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE)
        for (m in anchorRegex.findAll(html)) {
            val attrs = m.groupValues[1]
            val inner = m.groupValues[2]
            if (!attrs.contains("fsl-btn", ignoreCase = true)) continue
            if (!inner.contains("FAST CLOUD", ignoreCase = true)) continue
            if (isHoneypot(attrs + " " + inner)) continue

            val href = Regex("""href\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(attrs)?.groupValues?.get(1)?.trim().orEmpty()
            if (href.isEmpty()) continue

            val isV2 = inner.contains("[V2]", ignoreCase = true)
            if (isV2) { if (!v2.contains(href)) v2.add(href) }
            else { if (!fsl.contains(href)) fsl.add(href) }
        }
        return if (fsl.isNotEmpty()) fsl else v2
    }

    private fun isFakeRedirect(location: String): Boolean {
        val l = location.lowercase()
        if (l.contains("pub-") && l.contains(".r2.dev")) return true
        if (l.contains("watch online") || l.contains("watch%20online")) return true
        if (l.contains("/watch-online") || l.contains("/watch_online")) return true
        if (l.contains("dummy")) return true
        return false
    }
}
