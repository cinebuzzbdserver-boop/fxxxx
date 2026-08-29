package com.flixora.providers

import com.flixora.plugin.EpisodeItem
import com.flixora.plugin.MainAPI
import com.flixora.plugin.MediaStructure
import com.flixora.plugin.MovieItem
import com.flixora.plugin.SeasonItem
import com.flixora.plugin.StreamItem
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.util.Calendar

class PikaHDProvider : MainAPI() {
    override var name = "PikaHD"
    override var mainUrl = "https://new.pikahd.co"

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

    private fun getBaseHeaders(referer: String = mainUrl): Map<String, String> {
        return mapOf(
            "User-Agent" to userAgent,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9",
            "Referer" to referer,
            "Connection" to "keep-alive"
        )
    }

    override suspend fun getMainPage(page: Int): List<MovieItem> {
        val list = mutableListOf<MovieItem>()
        try {
            val targetUrl = if (page <= 1) {
                mainUrl
            } else {
                "$mainUrl/?page=$page"
            }

            val doc = Jsoup.connect(targetUrl)
                .headers(getBaseHeaders(mainUrl))
                .timeout(25000)
                .get()

            val html = doc.html()
            val pattern = Regex("""__sveltekit_[a-zA-Z0-9]+\.resolve\(\s*(\{.*?\})\s*\)""", RegexOption.DOT_MATCHES_ALL)
            val match = pattern.find(html)

            if (match != null) {
                val rawJson = match.groupValues[1]
                val itemRegex = Regex("""post_title:\s*"([^"]+)",\s*slug:\s*"([^"]+)",\s*thumbnail_image:\s*"([^"]+)"""")
                val itemMatches = itemRegex.findAll(rawJson)

                for (m in itemMatches) {
                    var title = m.groupValues[1].replace("\\/", "/").replace("\\\"", "\"").trim()
                    val slug = m.groupValues[2].replace("\\/", "/").trim()
                    val image = m.groupValues[3].replace("\\/", "/").trim()

                    if (title.isNotEmpty() && slug.isNotEmpty()) {
                        val fullUrl = if (slug.startsWith("http")) slug else "$mainUrl/$slug"
                        list.add(
                            MovieItem(
                                title = title,
                                image = image,
                                url = fullUrl,
                                imageSize = "2:3"
                            )
                        )
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
                .headers(getBaseHeaders(mainUrl))
                .header("Accept", "application/json, text/plain, */*")
                .header("x-sveltekit-invalidated", "01")
                .ignoreContentType(true)
                .timeout(25000)
                .execute()

            val rawData = response.body().trim()
            val chunkLines = rawData.lines().filter { it.contains(""""type":"chunk"""") || it.contains(""""post_title"""") }
            val targetData = if (chunkLines.isNotEmpty()) chunkLines.joinToString("\n") else rawData

            val searchPattern = Regex("""\[\d+(?:,\d+)*\],"(?:[^"\\]|\\.)*?","\d+","publish","(.*?)","(.*?)","(https?://[^"]+)"""")
            val matches = searchPattern.findAll(targetData)

            for (m in matches) {
                var title = m.groupValues[1].replace("\\/", "/").replace("\\\"", "\"").trim()
                val slug = m.groupValues[2].replace("\\/", "/").trim()
                val image = m.groupValues[3].replace("\\/", "/").trim()

                if (title.isNotEmpty() && slug.isNotEmpty()) {
                    val fullUrl = if (slug.startsWith("http")) slug else "$mainUrl/$slug"
                    list.add(
                        MovieItem(
                            title = title,
                            image = image,
                            url = fullUrl,
                            imageSize = "2:3"
                        )
                    )
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
        try {
            val doc = Jsoup.connect(postUrl)
                .headers(getBaseHeaders(mainUrl))
                .timeout(25000)
                .get()

            val html = doc.html()
            var postContent = ""

            val pattern = Regex("""__sveltekit_[a-zA-Z0-9]+\.resolve\(\s*(\{.*?\})\s*\)""", RegexOption.DOT_MATCHES_ALL)
            val match = pattern.find(html)

            if (match != null) {
                val jsonStr = match.groupValues[1]
                val contentRegex = Regex("""post_content\s*:\s*"(.*)"""", RegexOption.DOT_MATCHES_ALL)
                val cMatch = contentRegex.find(jsonStr)
                if (cMatch != null) {
                    postContent = cMatch.groupValues[1]
                        .replace("\\n", "\n")
                        .replace("\\r", "\r")
                        .replace("\\t", "\t")
                        .replace("\\\"", "\"")
                        .replace("\\/", "/")
                }
            }

            val contentDoc = if (postContent.isNotEmpty()) {
                Jsoup.parse(postContent)
            } else {
                doc
            }

            val episodeBlocks = contentDoc.select("h3, p")
            val seasonMap = mutableMapOf<String, MutableList<EpisodeItem>>()
            val rawMovieLinks = mutableListOf<Pair<String, String>>()

            var hasEpisodes = false
            for (block in episodeBlocks) {
                val text = block.text().trim()
                val epMatch = Regex("""(E\d+|Episode\s*\d+)""", RegexOption.IGNORE_CASE).find(text)

                if (epMatch != null) {
                    hasEpisodes = true
                    val epTitle = epMatch.groupValues[1].uppercase()
                    val links = block.select("a[href*=/file/]")
                    val rawLinks = mutableListOf<Pair<String, String>>()

                    for (link in links) {
                        val quality = link.text().trim()
                        val fileUrl = link.attr("href").trim()
                        if (fileUrl.isNotEmpty()) {
                            rawLinks.add(Pair(quality, fileUrl))
                        }
                    }

                    if (rawLinks.isNotEmpty()) {
                        val epList = seasonMap.getOrPut("Season 1") { mutableListOf() }
                        epList.add(EpisodeItem(name = epTitle, rawGenerateLinks = rawLinks))
                    }
                }
            }

            if (hasEpisodes && seasonMap.isNotEmpty()) {
                val seasonsList = seasonMap.map { (sName, epList) -> SeasonItem(name = sName, episodes = epList) }
                return MediaStructure(isSeries = true, rawMovieLinks = emptyList(), seasons = seasonsList)
            }

            val allFileLinks = contentDoc.select("a[href*=/file/]")
            for (link in allFileLinks) {
                val quality = link.text().trim().ifEmpty { "Download" }
                val fileUrl = link.attr("href").trim()
                if (fileUrl.isNotEmpty()) {
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
            val fileRegex = Regex("""https?://([^/]+)/file/([a-zA-Z0-9_]+)""")
            val fileMatch = fileRegex.find(generateUrl) ?: return null

            val domain = fileMatch.groupValues[1]
            val slug = fileMatch.groupValues[2]

            val apiUrl = "https://$domain/api/touchme/$slug?c=hubdrive_res"
            val apiResponse = Jsoup.connect(apiUrl)
                .headers(getBaseHeaders("https://$domain/file/$slug"))
                .header("Origin", "https://$domain")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Content-Type", "application/json")
                .requestBody("{}")
                .ignoreContentType(true)
                .method(org.jsoup.Connection.Method.POST)
                .timeout(25000)
                .execute()

            val apiBody = apiResponse.body()
            val linkIdRegex = Regex(""""linkId"\s*:\s*"(.*?)"""")
            val linkIdMatch = linkIdRegex.find(apiBody) ?: return null
            val hubdriveUrl = linkIdMatch.groupValues[1].replace("\\/", "/").trim()

            if (hubdriveUrl.isEmpty()) return null

            val driveDoc = Jsoup.connect(hubdriveUrl)
                .headers(getBaseHeaders("https://$domain/"))
                .timeout(25000)
                .get()

            val downloadBtn = driveDoc.selectFirst("a#download, a.btn-primary")
            var generateLink = downloadBtn?.attr("href")?.trim() ?: ""

            if (generateLink.isEmpty()) {
                val hrefRegex = Regex("""href=["'](https?://[^"']*hubcloud\.php[^"']*)["']""")
                val m = hrefRegex.find(driveDoc.html())
                if (m != null) {
                    generateLink = m.groupValues[1]
                }
            }

            if (generateLink.isEmpty()) return null

            val dlPageDoc = Jsoup.connect(generateLink)
                .headers(getBaseHeaders(hubdriveUrl))
                .timeout(25000)
                .get()

            val fslBtn = dlPageDoc.selectFirst("a#fsl, a.btn-success")
            var directUrl = fslBtn?.attr("href")?.trim() ?: ""

            if (directUrl.isNotEmpty()) {
                if (!directUrl.contains("r2.cloudflarestorage.com")) {
                    val minutes = Calendar.getInstance().get(Calendar.MINUTE)
                    directUrl = "${directUrl}1$minutes"
                }
                return directUrl
            }

            val fallbackRegex = Regex("""href=["'](https?://[^"']+)["'][^>]*id=["']fsl["']""")
            val fbMatch = fallbackRegex.find(dlPageDoc.html())
            if (fbMatch != null) {
                var extracted = fbMatch.groupValues[1]
                if (!extracted.contains("r2.cloudflarestorage.com")) {
                    val minutes = Calendar.getInstance().get(Calendar.MINUTE)
                    extracted = "${extracted}1$minutes"
                }
                return extracted
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }
}
