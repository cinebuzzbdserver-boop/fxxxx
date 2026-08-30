package com.flixora.providers

import com.flixora.plugin.MainAPI
import com.flixora.plugin.MediaStructure
import com.flixora.plugin.MovieItem
import com.flixora.plugin.StreamItem
import org.jsoup.Connection
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.util.Calendar

class PikaHDProvider : MainAPI() {
    override var name = "PikaHD"
    override var mainUrl = "https://new.pikahd.co"

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    private fun getBrowserHeaders(referer: String = mainUrl): Map<String, String> {
        return mapOf(
            "User-Agent" to userAgent,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9",
            "Referer" to referer
        )
    }

    private fun decodeUnicode(input: String): String {
        val unicodePattern = Regex("""\\u([0-9a-fA-F]{4})""")
        val text = unicodePattern.replace(input) { matchResult ->
            val charCode = matchResult.groupValues[1].toInt(16)
            charCode.toChar().toString()
        }
        return text.replace("\\/", "/").replace("\\\"", "\"").replace("\\n", "\n").replace("\\r", "\r").replace("\\t", "\t")
    }

    override suspend fun getMainPage(page: Int): List<MovieItem> {
        val list = mutableListOf<MovieItem>()
        try {
            val targetUrl = if (page <= 1) mainUrl else "$mainUrl/?page=$page"

            val response = Jsoup.connect(targetUrl)
                .headers(getBrowserHeaders(mainUrl))
                .ignoreContentType(true)
                .timeout(20000)
                .execute()

            val html = response.body()
            val pattern = Regex("""__sveltekit_[a-zA-Z0-9]+\.resolve\(\s*(\{.+?\})\s*\)""", RegexOption.DOT_MATCHES_ALL)
            val match = pattern.find(html)

            if (match != null) {
                val rawJson = decodeUnicode(match.groupValues[1])
                val itemRegex = Regex("""post_title\s*:\s*"([^"]+)",\s*slug\s*:\s*"([^"]+)",\s*thumbnail_image\s*:\s*"([^"]+)"""")
                val itemMatches = itemRegex.findAll(rawJson)

                for (m in itemMatches) {
                    val title = m.groupValues[1].trim()
                    val slug = m.groupValues[2].trim()
                    val image = m.groupValues[3].trim()

                    if (title.isNotEmpty() && slug.isNotEmpty()) {
                        val fullUrl = if (slug.startsWith("http")) slug else "$mainUrl/$slug"
                        list.add(MovieItem(title = title, image = image, url = fullUrl, imageSize = "2:3"))
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    override suspend fun search(query: String): List<MovieItem> {
        return search(query, 1)
    }

    override suspend fun search(query: String, page: Int): List<MovieItem> {
        val list = mutableListOf<MovieItem>()
        try {
            val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8")
            val searchApiUrl = "$mainUrl/__data.json?q=$encodedQuery&page=$page"

            val response = Jsoup.connect(searchApiUrl)
                .headers(getBrowserHeaders(mainUrl))
                .header("Accept", "application/json, text/plain, */*")
                .header("x-sveltekit-invalidated", "01")
                .ignoreContentType(true)
                .timeout(20000)
                .execute()

            val rawData = decodeUnicode(response.body().trim())
            val chunkLines = rawData.lines().filter { it.contains(""""type":"chunk"""") || it.contains(""""post_title"""") }
            val targetData = if (chunkLines.isNotEmpty()) chunkLines.joinToString("\n") else rawData

            val searchPattern = Regex("""\[\d+(?:,\d+)*\],"(?:[^"\\]|\\.)*?","\d+","publish","(.*?)","(.*?)","(https?://[^"]+)"""")
            val matches = searchPattern.findAll(targetData)

            for (m in matches) {
                val title = m.groupValues[1].trim()
                val slug = m.groupValues[2].trim()
                val image = m.groupValues[3].trim()

                if (title.isNotEmpty() && slug.isNotEmpty()) {
                    val fullUrl = if (slug.startsWith("http")) slug else "$mainUrl/$slug"
                    list.add(MovieItem(title = title, image = image, url = fullUrl, imageSize = "2:3"))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    override suspend fun loadLinks(postUrl: String): List<StreamItem> {
        return emptyList()
    }

    override suspend fun loadMediaStructure(postUrl: String): MediaStructure {
        val rawMovieLinks = mutableListOf<Pair<String, String>>()
        try {
            val response = Jsoup.connect(postUrl)
                .headers(getBrowserHeaders(mainUrl))
                .ignoreContentType(true)
                .timeout(20000)
                .execute()

            val html = response.body()
            var postContent = ""

            val pattern = Regex("""__sveltekit_[a-zA-Z0-9]+\.resolve\(\s*(\{.+?\})\s*\)""", RegexOption.DOT_MATCHES_ALL)
            val match = pattern.find(html)

            if (match != null) {
                val jsonStr = decodeUnicode(match.groupValues[1])
                val contentRegex = Regex(""""post_content"\s*:\s*"((?:[^"\\]|\\.)*)"""", RegexOption.DOT_MATCHES_ALL)
                val cMatch = contentRegex.find(jsonStr)
                if (cMatch != null) {
                    postContent = cMatch.groupValues[1]
                }
            }

            val targetHtml = if (postContent.isNotEmpty()) postContent else html
            val contentDoc = Jsoup.parse(targetHtml)

            val blocks = contentDoc.select("h3, p, h4, div, tr")

            for (block in blocks) {
                val text = block.text().trim()
                val links = block.select("a[href*=/file/]")
                if (links.isEmpty()) continue

                val epMatch = Regex("""(E\d+|Episode\s*\d+)""", RegexOption.IGNORE_CASE).find(text)
                val epPrefix = if (epMatch != null) "${epMatch.value.uppercase()} - " else ""

                for (link in links) {
                    var quality = link.text().trim()
                    if (quality.isEmpty() || quality == "||") {
                        quality = "HD Quality"
                    }
                    val label = if (epPrefix.isNotEmpty() && !quality.startsWith("E", ignoreCase = true)) {
                        "$epPrefix$quality"
                    } else {
                        quality
                    }

                    val fileUrl = link.attr("href").trim()
                    if (fileUrl.isNotEmpty() && rawMovieLinks.none { it.second == fileUrl }) {
                        val fullUrl = if (fileUrl.startsWith("http")) fileUrl else "$mainUrl$fileUrl"
                        rawMovieLinks.add(Pair(label, fullUrl))
                    }
                }
            }

            // Fallback: যদি ব্লকের ভেতর না পায়, ডকুমেন্টের সব /file/ লিঙ্ক কালেক্ট করা
            if (rawMovieLinks.isEmpty()) {
                val allDirectLinks = contentDoc.select("a[href*=/file/]")
                for (link in allDirectLinks) {
                    val quality = link.text().trim().ifEmpty { "Download" }
                    val fileUrl = link.attr("href").trim()
                    if (fileUrl.isNotEmpty() && rawMovieLinks.none { it.second == fileUrl }) {
                        val fullUrl = if (fileUrl.startsWith("http")) fileUrl else "$mainUrl$fileUrl"
                        rawMovieLinks.add(Pair(quality, fullUrl))
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return MediaStructure(isSeries = false, rawMovieLinks = rawMovieLinks, seasons = emptyList())
    }

    override suspend fun resolveDirectLink(generateUrl: String): String? {
        try {
            val sessionCookies = mutableMapOf<String, String>()

            val fileRegex = Regex("""https?://([^/]+)/file/([a-zA-Z0-9_]+)""")
            val fileMatch = fileRegex.find(generateUrl) ?: return null

            val domain = fileMatch.groupValues[1]
            val slug = fileMatch.groupValues[2]

            val apiUrl = "https://$domain/api/touchme/$slug?c=hubdrive_res"
            val apiRes = Jsoup.connect(apiUrl)
                .userAgent(userAgent)
                .header("Accept", "*/*")
                .header("Referer", generateUrl)
                .header("Origin", "https://$domain")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Content-Type", "application/json")
                .requestBody("{}")
                .ignoreContentType(true)
                .ignoreHttpErrors(true)
                .method(Connection.Method.POST)
                .timeout(20000)
                .execute()

            sessionCookies.putAll(apiRes.cookies())

            val apiBody = apiRes.body()
            val linkIdRegex = Regex(""""linkId"\s*:\s*"(.*?)"""")
            val linkIdMatch = linkIdRegex.find(apiBody) ?: return null
            val hubdriveUrl = decodeUnicode(linkIdMatch.groupValues[1]).trim()

            if (hubdriveUrl.isEmpty() || !hubdriveUrl.startsWith("http")) return null

            val driveRes = Jsoup.connect(hubdriveUrl)
                .headers(getBrowserHeaders("https://$domain/"))
                .cookies(sessionCookies)
                .followRedirects(true)
                .ignoreHttpErrors(true)
                .timeout(20000)
                .execute()

            sessionCookies.putAll(driveRes.cookies())
            val driveDoc = driveRes.parse()
            val driveHtml = driveDoc.html()

            var generateLink = driveDoc.selectFirst("a#download, a.btn-primary")?.attr("href")?.trim() ?: ""

            if (generateLink.isEmpty() || !generateLink.startsWith("http")) {
                val scriptRegex = Regex("""(?:var\s+url\s*=\s*|href\s*=\s*)['"](https?://[^'"]*hubcloud\.php[^'"]*)['"]""")
                val scriptMatch = scriptRegex.find(driveHtml)
                if (scriptMatch != null) {
                    generateLink = decodeUnicode(scriptMatch.groupValues[1]).trim()
                }
            }

            if (generateLink.isEmpty() || !generateLink.startsWith("http")) return null

            val dlPageRes = Jsoup.connect(generateLink)
                .headers(getBrowserHeaders(hubdriveUrl))
                .cookies(sessionCookies)
                .followRedirects(true)
                .ignoreHttpErrors(true)
                .timeout(20000)
                .execute()

            sessionCookies.putAll(dlPageRes.cookies())
            val dlPageDoc = dlPageRes.parse()
            val dlPageHtml = dlPageDoc.html()

            var directUrl = dlPageDoc.selectFirst("a#fsl, a.btn-success")?.attr("href")?.trim() ?: ""

            if (directUrl.isEmpty() || !directUrl.startsWith("http")) {
                val fslRegex = Regex("""href=["'](https?://[^"']+)["'][^>]*id=["']fsl["']""")
                val fslMatch = fslRegex.find(dlPageHtml)
                if (fslMatch != null) {
                    directUrl = decodeUnicode(fslMatch.groupValues[1]).trim()
                }
            }

            if (directUrl.isNotEmpty() && directUrl.startsWith("http")) {
                if (!directUrl.contains("r2.cloudflarestorage.com")) {
                    val minutes = Calendar.getInstance().get(Calendar.MINUTE)
                    directUrl = "${directUrl}1$minutes"
                }
                return directUrl
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }
}
