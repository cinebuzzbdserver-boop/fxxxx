package com.flixora.providers

import com.flixora.plugin.EpisodeItem
import com.flixora.plugin.MainAPI
import com.flixora.plugin.MediaStructure
import com.flixora.plugin.MovieItem
import com.flixora.plugin.SeasonItem
import com.flixora.plugin.StreamItem
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder

class FibWatchProvider : MainAPI() {
    override var name = "FibWatch"
    override var mainUrl = "https://fibwatch.art"

    private val proxyPrefix = "https://wandering-glitter-0f39.blmbd.workers.dev/"
    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    private fun getBrowserDoc(targetUrl: String): Document {
        // সাইটের 403 বট প্রোটেকশন বাইপাস করার জন্য সরাসরি প্রক্সি ওয়ার্কারের মাধ্যমে পেজ ফেচ
        val fetchUrl = if (targetUrl.startsWith(proxyPrefix)) targetUrl else "$proxyPrefix$targetUrl"
        
        return Jsoup.connect(fetchUrl)
            .userAgent(userAgent)
            .referrer("$mainUrl/")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Sec-Ch-Ua", "\"Not/A)Brand\";v=\"8\", \"Chromium\";v=\"126\", \"Google Chrome\";v=\"126\"")
            .header("Sec-Ch-Ua-Mobile", "?0")
            .header("Sec-Ch-Ua-Platform", "\"Windows\"")
            .header("Sec-Fetch-Dest", "document")
            .header("Sec-Fetch-Mode", "navigate")
            .header("Sec-Fetch-Site", "cross-site")
            .ignoreContentType(true)
            .ignoreHttpErrors(true)
            .timeout(20000)
            .get()
    }

    private fun getBrowserJson(targetUrl: String): String {
        val fetchUrl = if (targetUrl.startsWith(proxyPrefix)) targetUrl else "$proxyPrefix$targetUrl"
        
        return Jsoup.connect(fetchUrl)
            .userAgent(userAgent)
            .referrer("$mainUrl/")
            .header("Accept", "application/json, text/javascript, */*; q=0.01")
            .header("X-Requested-With", "XMLHttpRequest")
            .ignoreContentType(true)
            .ignoreHttpErrors(true)
            .timeout(20000)
            .execute()
            .body()
            .trim()
    }

    private fun wrapProxy(url: String): String {
        val trimmed = url.trim()
        return if (trimmed.isNotEmpty() && !trimmed.startsWith(proxyPrefix)) {
            "$proxyPrefix$trimmed"
        } else {
            trimmed
        }
    }

    override suspend fun getMainPage(page: Int): List<MovieItem> {
        val list = mutableListOf<MovieItem>()
        try {
            val targetUrl = if (page <= 1) {
                "$mainUrl/videos/latest"
            } else {
                "$mainUrl/videos/latest?page_id=$page"
            }

            val doc = getBrowserDoc(targetUrl)
            val cards = doc.select(".video-latest-list")

            for (card in cards) {
                val thumbAnchor = card.selectFirst(".video-thumb a")
                val titleAnchor = card.selectFirst(".video-title a")

                val rawUrl = thumbAnchor?.attr("href")?.trim() ?: titleAnchor?.attr("href")?.trim() ?: ""
                val imgElement = card.selectFirst(".video-thumb img")
                val image = imgElement?.attr("src")?.trim() ?: ""
                val title = card.selectFirst(".video-title p.hptag")?.text()?.trim()
                    ?: titleAnchor?.text()?.trim()
                    ?: ""

                if (title.isNotEmpty() && rawUrl.isNotEmpty()) {
                    val fullUrl = if (rawUrl.startsWith("http")) rawUrl else "$mainUrl$rawUrl"
                    list.add(
                        MovieItem(
                            title = title,
                            image = wrapProxy(image),
                            url = fullUrl,
                            imageSize = "16:9"
                        )
                    )
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
            val targetUrl = if (page <= 1) {
                "$mainUrl/search?keyword=$encodedQuery"
            } else {
                "$mainUrl/search?keyword=$encodedQuery&page_id=$page"
            }

            val doc = getBrowserDoc(targetUrl)
            val cards = doc.select(".video-latest-list")

            for (card in cards) {
                val thumbAnchor = card.selectFirst(".video-thumb a")
                val titleAnchor = card.selectFirst(".video-title a")

                val rawUrl = thumbAnchor?.attr("href")?.trim() ?: titleAnchor?.attr("href")?.trim() ?: ""
                val imgElement = card.selectFirst(".video-thumb img")
                val image = imgElement?.attr("src")?.trim() ?: ""
                val title = card.selectFirst(".video-title p.hptag")?.text()?.trim()
                    ?: titleAnchor?.text()?.trim()
                    ?: ""

                if (title.isNotEmpty() && rawUrl.isNotEmpty()) {
                    val fullUrl = if (rawUrl.startsWith("http")) rawUrl else "$mainUrl$rawUrl"
                    list.add(
                        MovieItem(
                            title = title,
                            image = wrapProxy(image),
                            url = fullUrl,
                            imageSize = "16:9"
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
            val doc = getBrowserDoc(postUrl)

            val videoId = doc.selectFirst("#see-more-res")?.attr("data-vid")?.trim()
                ?: doc.selectFirst("[data-vid]")?.attr("data-vid")?.trim()
                ?: ""

            val currentRes = doc.selectFirst(".available-res .res-btn.selected")?.text()?.trim()?.ifEmpty { null } ?: "Default"

            // ১. সিরিজ চেক ও পার্সিং
            if (videoId.isNotEmpty()) {
                val epApiUrl = "$mainUrl/ajax/episodes.php?video_id=$videoId"
                val epJson = getBrowserJson(epApiUrl)

                val epRegex = Regex("""\{"display":"(.*?)","url":"(.*?)","is_current":(true|false)\}""")
                val epMatches = epRegex.findAll(epJson).toList()

                if (epMatches.isNotEmpty()) {
                    val seasonMap = mutableMapOf<String, MutableList<EpisodeItem>>()

                    for (match in epMatches) {
                        val display = match.groupValues[1].replace("\\/", "/").trim()
                        val relUrl = match.groupValues[2].replace("\\/", "/").trim()

                        if (relUrl.isNotEmpty()) {
                            val epFullUrl = if (relUrl.startsWith("http")) relUrl else "$mainUrl$relUrl"

                            val seasonMatch = Regex("""S(\d+)""", RegexOption.IGNORE_CASE).find(display)
                            val seasonName = if (seasonMatch != null) {
                                "Season " + seasonMatch.groupValues[1].toInt()
                            } else {
                                "Season 1"
                            }

                            val rawLinks = mutableListOf(Pair(currentRes, epFullUrl))
                            val epList = seasonMap.getOrPut(seasonName) { mutableListOf() }
                            epList.add(EpisodeItem(name = display, rawGenerateLinks = rawLinks))
                        }
                    }

                    val seasonsList = seasonMap.map { (sName, epList) -> SeasonItem(name = sName, episodes = epList) }
                    if (seasonsList.isNotEmpty()) {
                        return MediaStructure(isSeries = true, rawMovieLinks = emptyList(), seasons = seasonsList)
                    }
                }
            }

            // ২. মুভি কোয়ালিটি লিংক পার্সিং
            val rawMovieLinks = mutableListOf<Pair<String, String>>()
            rawMovieLinks.add(Pair(currentRes, postUrl))

            if (videoId.isNotEmpty()) {
                val resApiUrl = "$mainUrl/ajax/resolution_switcher.php?video_id=$videoId"
                val resJson = getBrowserJson(resApiUrl)

                val popupBlock = resJson.substringAfter("\"popup\":", "").substringBefore("]")
                val resRegex = Regex("""\{"res":"(.*?)","url":"(.*?)","selected":(true|false)\}""")
                val resMatches = resRegex.findAll(popupBlock)

                for (match in resMatches) {
                    val res = match.groupValues[1].trim()
                    val relUrl = match.groupValues[2].replace("\\/", "/").trim()
                    val isSelected = match.groupValues[3].toBoolean()

                    if (!isSelected && relUrl.isNotEmpty() && !res.equals("See More", ignoreCase = true)) {
                        val fullUrl = if (relUrl.startsWith("http")) relUrl else "$mainUrl$relUrl"
                        rawMovieLinks.add(Pair(res, fullUrl))
                    }
                }
            }

            return MediaStructure(isSeries = false, rawMovieLinks = rawMovieLinks, seasons = emptyList())
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return MediaStructure(isSeries = false, rawMovieLinks = listOf(Pair("Default", postUrl)), seasons = emptyList())
    }

    override suspend fun resolveDirectLink(generateUrl: String): String? {
        try {
            val doc = getBrowserDoc(generateUrl)
            val htmlContent = doc.html()

            val videoUrlRegex = Regex("""var\s+VIDEO_URL\s*=\s*["']([^"']+)["']""")
            val match = videoUrlRegex.find(htmlContent)
            val directStreamUrl = match?.groupValues?.get(1)?.trim() ?: ""

            if (directStreamUrl.isNotEmpty()) {
                val cleanedUrl = directStreamUrl.replace("\\/", "/")
                return wrapProxy(cleanedUrl)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }
}
