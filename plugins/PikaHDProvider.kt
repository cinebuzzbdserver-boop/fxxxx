package com.flixora.providers

import com.flixora.plugin.EpisodeItem
import com.flixora.plugin.MainAPI
import com.flixora.plugin.MediaStructure
import com.flixora.plugin.MovieItem
import com.flixora.plugin.SeasonItem
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
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9",
            "Referer" to referer,
            "Upgrade-Insecure-Requests" to "1",
            "Connection" to "keep-alive"
        )
    }

    private fun decodeUnicode(input: String): String {
        var text = input
        val unicodePattern = Regex("""\\u([0-9a-fA-F]{4})""")
        text = unicodePattern.replace(text) { matchResult ->
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
                .followRedirects(true)
                .ignoreHttpErrors(true)
                .timeout(25000)
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
                .ignoreHttpErrors(true)
                .timeout(25000)
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

    // সরাসরি StreamItem লিস্ট রিটার্ন করার সাপোর্ট (যদি অ্যাপ loadLinks মেথড ব্যবহার করে)
    override suspend fun loadLinks(postUrl: String): List<StreamItem> {
        val streamList = mutableListOf<StreamItem>()
        try {
            val structure = loadMediaStructure(postUrl)
            
            // ১. যদি সিরিজ হয়:
            if (structure.isSeries && structure.seasons.isNotEmpty()) {
                for (season in structure.seasons) {
                    for (ep in season.episodes) {
                        for ((quality, link) in ep.rawGenerateLinks) {
                            val directUrl = resolveDirectLink(link)
                            if (!directUrl.isNullOrEmpty()) {
                                streamList.add(
                                    StreamItem(
                                        name = "${season.name} - ${ep.name} [$quality]",
                                        url = directUrl
                                    )
                                )
                            }
                        }
                    }
                }
            } 
            // ২. যদি সিঙ্গেল মুভি হয়:
            else if (structure.rawMovieLinks.isNotEmpty()) {
                for ((quality, link) in structure.rawMovieLinks) {
                    val directUrl = resolveDirectLink(link)
                    if (!directUrl.isNullOrEmpty()) {
                        streamList.add(
                            StreamItem(
                                name = quality,
                                url = directUrl
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return streamList
    }

    override suspend fun loadMediaStructure(postUrl: String): MediaStructure {
        try {
            val response = Jsoup.connect(postUrl)
                .headers(getBrowserHeaders(mainUrl))
                .followRedirects(true)
                .ignoreHttpErrors(true)
                .timeout(25000)
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

            val linkBlocks = contentDoc.select("h3, p, h4, div, tr")
            val seasonMap = mutableMapOf<String, MutableList<EpisodeItem>>()
            val rawMovieLinks = mutableListOf<Pair<String, String>>()
            var isSeries = false

            for (block in linkBlocks) {
                val text = block.text().trim()
                val links = block.select("a[href*=/file/]")
                if (links.isEmpty()) continue

                val epMatch = Regex("""(E\d+|Episode\s*\d+)""", RegexOption.IGNORE_CASE).find(text)
                if (epMatch != null) {
                    isSeries = true
                    val epNumMatch = Regex("""\d+""").find(epMatch.value)?.value ?: "1"
                    val epName = "Episode $epNumMatch"
                    val rawLinks = mutableListOf<Pair<String, String>>()

                    for (link in links) {
                        var quality = link.text().trim()
                        if (quality.isEmpty() || quality.equals("||", ignoreCase = true)) {
                            quality = "HD Quality"
                        }
                        val fileUrl = link.attr("href").trim()
                        if (fileUrl.isNotEmpty() && !rawLinks.any { it.second == fileUrl }) {
                            rawLinks.add(Pair(quality, fileUrl))
                        }
                    }

                    if (rawLinks.isNotEmpty()) {
                        val epList = seasonMap.getOrPut("Season 1") { mutableListOf() }
                        val existing = epList.find { it.name.equals(epName, ignoreCase = true) }
                        if (existing == null) {
                            epList.add(EpisodeItem(name = epName, rawGenerateLinks = rawLinks))
                        } else {
                            // লিংক মার্জ করা
                            for (rLink in rawLinks) {
                                if (!existing.rawGenerateLinks.any { it.second == rLink.second }) {
                                    (existing.rawGenerateLinks as? MutableList)?.add(rLink)
                                }
                            }
                        }
                    }
                }
            }

            if (isSeries && seasonMap.isNotEmpty()) {
                val seasonsList = seasonMap.map { (sName, epList) -> SeasonItem(name = sName, episodes = epList) }
                return MediaStructure(isSeries = true, rawMovieLinks = emptyList(), seasons = seasonsList)
            }

            val allDirectLinks = contentDoc.select("a[href*=/file/]")
            for (link in allDirectLinks) {
                val quality = link.text().trim().ifEmpty { "Download" }
                val fileUrl = link.attr("href").trim()
                if (fileUrl.isNotEmpty() && !rawMovieLinks.any { it.second == fileUrl }) {
                    rawMovieLinks.add(Pair(quality, fileUrl))
                }
            }

            return MediaStructure(isSeries = false, rawMovieLinks = rawMovieLinks, seasons = emptyList())
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return MediaStructure(isSeries = false, rawMovieLinks = emptyList(), seasons = emptyList())
    }

    override suspend fun resolveDirectLink(generateUrl: String): String? {
        try {
            val sessionCookies = mutableMapOf<String, String>()

            val fileRegex = Regex("""https?://([^/]+)/file/([a-zA-Z0-9_]+)""")
            val fileMatch = fileRegex.find(generateUrl) ?: return null

            val domain = fileMatch.groupValues[1]
            val slug = fileMatch.groupValues[2]

            // ১. API এন্ডপয়েন্টে POST রিকোয়েস্ট
            val apiUrl = "https://$domain/api/touchme/$slug?c=hubdrive_res"
            val apiRes = Jsoup.connect(apiUrl)
                .userAgent(userAgent)
                .header("Accept", "*/*")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Referer", generateUrl)
                .header("Origin", "https://$domain")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Content-Type", "application/json")
                .requestBody("{}")
                .ignoreContentType(true)
                .ignoreHttpErrors(true)
                .method(Connection.Method.POST)
                .timeout(25000)
                .execute()

            sessionCookies.putAll(apiRes.cookies())

            val apiBody = apiRes.body()
            val linkIdRegex = Regex(""""linkId"\s*:\s*"(.*?)"""")
            val linkIdMatch = linkIdRegex.find(apiBody) ?: return null
            val hubdriveUrl = decodeUnicode(linkIdMatch.groupValues[1]).trim()

            if (hubdriveUrl.isEmpty() || !hubdriveUrl.startsWith("http")) return null

            // ২. Hubcloud / Drive পেজে GET রিকোয়েস্ট
            val driveRes = Jsoup.connect(hubdriveUrl)
                .headers(getBrowserHeaders("https://$domain/"))
                .cookies(sessionCookies)
                .followRedirects(true)
                .ignoreHttpErrors(true)
                .timeout(25000)
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

            // ৩. Resolver পেজে (hubcloud.php) GET রিকোয়েস্ট
            val dlPageRes = Jsoup.connect(generateLink)
                .headers(getBrowserHeaders(hubdriveUrl))
                .cookies(sessionCookies)
                .followRedirects(true)
                .ignoreHttpErrors(true)
                .timeout(25000)
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

            // ৪. Cloudflare R2 বা FSL লিঙ্ক হ্যান্ডলিং
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
