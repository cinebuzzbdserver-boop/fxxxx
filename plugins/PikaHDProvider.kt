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

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

    private fun getBrowserHeaders(referer: String = mainUrl): Map<String, String> {
        return mapOf(
            "User-Agent" to userAgent,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9",
            "Referer" to referer,
            "Upgrade-Insecure-Requests" to "1"
        )
    }

    private fun cleanJsString(input: String): String {
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
                .timeout(25000)
                .execute()

            val html = response.body()
            val pattern = Regex("""__sveltekit_[a-zA-Z0-9]+\.resolve\(\s*(\{.+?\})\s*\)""", RegexOption.DOT_MATCHES_ALL)
            val match = pattern.find(html)

            if (match != null) {
                val rawJson = cleanJsString(match.groupValues[1])
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
                .timeout(25000)
                .execute()

            val rawData = cleanJsString(response.body().trim())
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
                .timeout(25000)
                .execute()

            val html = response.body()
            var decodedHtml = html

            // ১. পাইথন লজিকের মতো resolve(...) ব্লক থেকে post_content উদ্ধার করা
            val resolveMatch = Regex("""__sveltekit_[a-zA-Z0-9]+\.resolve\(\s*(\{.+?\})\s*\)""", RegexOption.DOT_MATCHES_ALL).find(html)
            if (resolveMatch != null) {
                val rawObj = resolveMatch.groupValues[1]
                val contentMatch = Regex(""""post_content"\s*:\s*"((?:[^"\\]|\\.)*)"""", RegexOption.DOT_MATCHES_ALL).find(rawObj)
                if (contentMatch != null) {
                    decodedHtml = cleanJsString(contentMatch.groupValues[1])
                }
            }

            // ২. ডিরেক্ট Regex দিয়ে সব a href ডাউনলোড লিংক পার্স করা
            val linkRegex = Regex("""<a[^>]+href=["'](https?://links\.kmhd\.[a-z]+/file/[^"']+)["'][^>]*>(.*?)</a>""", RegexOption.IGNORE_CASE)
            val matches = linkRegex.findAll(decodedHtml)

            for (m in matches) {
                val url = m.groupValues[1].trim()
                var quality = Jsoup.parse(m.groupValues[2]).text().trim()

                if (quality.isEmpty() || quality == "||") {
                    quality = "HD Download"
                }

                if (url.isNotEmpty() && rawMovieLinks.none { it.second == url }) {
                    rawMovieLinks.add(Pair(quality, url))
                }
            }

            // ৩. ফলব্যাক: যদি Regex মিস করে তাহলে Jsoup দিয়ে ধরা
            if (rawMovieLinks.isEmpty()) {
                val parsedDoc = Jsoup.parse(decodedHtml)
                val links = parsedDoc.select("a[href*=/file/]")
                for (link in links) {
                    val href = link.attr("href").trim()
                    var label = link.text().trim()
                    if (label.isEmpty() || label == "||") label = "Download"
                    if (href.isNotEmpty() && rawMovieLinks.none { it.second == href }) {
                        rawMovieLinks.add(Pair(label, href))
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

            // ১. kmhd ফাইল লিঙ্ক থেকে ডোমেইন এবং স্ল্যাগ বের করা
            val fileRegex = Regex("""https?://(links\.kmhd\.[a-z]+)/file/([a-zA-Z0-9_]+)""")
            val fileMatch = fileRegex.find(generateUrl) ?: return null

            val domain = fileMatch.groupValues[1]
            val slug = fileMatch.groupValues[2]

            // ২. POST রিকোয়েস্ট পাঠিয়ে linkId বের করা
            val apiUrl = "https://$domain/api/touchme/$slug?c=hubdrive_res"
            val apiRes = Jsoup.connect(apiUrl)
                .userAgent(userAgent)
                .header("Accept", "application/json, text/plain, */*")
                .header("Referer", generateUrl)
                .header("Origin", "https://$domain")
                .header("X-Requested-With", "XMLHttpRequest")
                .ignoreContentType(true)
                .ignoreHttpErrors(true)
                .method(Connection.Method.POST)
                .timeout(25000)
                .execute()

            sessionCookies.putAll(apiRes.cookies())

            val apiBody = apiRes.body()
            val linkIdMatch = Regex(""""linkId"\s*:\s*"(.*?)"""").find(apiBody) ?: return null
            val hubdriveUrl = cleanJsString(linkIdMatch.groupValues[1]).trim()

            if (hubdriveUrl.isEmpty() || !hubdriveUrl.startsWith("http")) return null

            // ৩. Hubcloud লিংকে GET রিকোয়েস্ট পাঠিয়ে ডাউনলোড পেজের লিঙ্ক নেওয়া
            val driveRes = Jsoup.connect(hubdriveUrl)
                .headers(getBrowserHeaders("https://$domain/"))
                .cookies(sessionCookies)
                .followRedirects(true)
                .ignoreHttpErrors(true)
                .timeout(25000)
                .execute()

            sessionCookies.putAll(driveRes.cookies())
            val driveHtml = driveRes.body()

            var generateLink = ""
            val btnMatch = Regex("""<a[^>]+id=["']download["'][^>]+href=["']([^"']+)["']""").find(driveHtml)
            if (btnMatch != null) {
                generateLink = btnMatch.groupValues[1]
            }

            if (generateLink.isEmpty() || !generateLink.startsWith("http")) {
                val scriptMatch = Regex("""(?:var\s+url\s*=\s*|href\s*=\s*)['"](https?://[^'"]*hubcloud\.php[^'"]*)['"]""").find(driveHtml)
                if (scriptMatch != null) {
                    generateLink = cleanJsString(scriptMatch.groupValues[1]).trim()
                }
            }

            if (generateLink.isEmpty() || !generateLink.startsWith("http")) return null

            // ৪. GamerXYT / Hubcloud.php পেজে GET পাঠিয়ে ফাইনাল FSL স্ট্রিমিং লিঙ্ক নেওয়া
            val dlPageRes = Jsoup.connect(generateLink)
                .headers(getBrowserHeaders(hubdriveUrl))
                .cookies(sessionCookies)
                .followRedirects(true)
                .ignoreHttpErrors(true)
                .timeout(25000)
                .execute()

            sessionCookies.putAll(dlPageRes.cookies())
            val dlPageHtml = dlPageRes.body()

            var directUrl = ""
            val fslMatch = Regex("""<a[^>]+href=["'](https?://[^"']+)["'][^>]*id=["']fsl["']""").find(dlPageHtml)
                ?: Regex("""<a[^>]+id=["']fsl["'][^>]+href=["'](https?://[^"']+)["']""").find(dlPageHtml)

            if (fslMatch != null) {
                directUrl = cleanJsString(fslMatch.groupValues[1]).trim()
            }

            // ৫. ফিনিশিং: মিনিট টাইমস্ট্যাম্প যোগ করা
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
