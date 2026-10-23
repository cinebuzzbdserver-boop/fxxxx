package com.moviefy.stream.providers

import com.moviefy.stream.ui.screens.EpisodeItem
import com.moviefy.stream.ui.screens.MainAPI
import com.moviefy.stream.ui.screens.MediaStructure
import com.moviefy.stream.ui.screens.MovieItem
import com.moviefy.stream.ui.screens.SeasonItem
import com.moviefy.stream.ui.screens.StreamItem
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder

class FibWatchProvider : MainAPI {
    override var name = "FibWatch2"
    override var mainUrl = "https://fibwatch.art"

    private val userAgent = "Mozilla/5.0 (Linux; Android 16; 23090RA98I Build/BP2A.250605.031.A3) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/153.0.8010.57 Mobile Safari/537.36"

    private fun getBrowserDoc(targetUrl: String): Document {
        return Jsoup.connect(targetUrl)
            .userAgent(userAgent)
            .referrer("$mainUrl/")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .ignoreContentType(true)
            .ignoreHttpErrors(true)
            .timeout(20000)
            .get()
    }

    private fun getBrowserJson(targetUrl: String): String {
        return Jsoup.connect(targetUrl)
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

    override suspend fun getMainPage(page: Int): List<MovieItem> {
        val list = mutableListOf<MovieItem>()
        try {
            val targetUrl = if (page <= 1) "$mainUrl/videos/latest" else "$mainUrl/videos/latest?page_id=$page"
            val doc = getBrowserDoc(targetUrl)
            val cards = doc.select(".video-latest-list, .video-wrapper")

            for (card in cards) {
                val rawUrl = card.selectFirst(".video-thumb > a")?.attr("href")?.trim() ?: continue
                val imgElement = card.selectFirst("img")
                val image = imgElement?.attr("src")?.trim() ?: ""
                val title = card.selectFirst("p.hptag, h4")?.attr("title")?.trim()?.ifEmpty { null }
                    ?: card.selectFirst("p.hptag, h4")?.text()?.trim() ?: "Unknown"

                if (rawUrl.isNotEmpty()) {
                    val fullUrl = if (rawUrl.startsWith("http")) rawUrl else "$mainUrl$rawUrl"
                    list.add(
                        MovieItem(
                            title = title,
                            image = image,
                            url = fullUrl,
                            isPoster = false,
                            referer = "$mainUrl/"
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    override suspend fun search(query: String, page: Int): List<MovieItem> {
        val list = mutableListOf<MovieItem>()
        try {
            val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8")
            val targetUrl = if (page <= 1) "$mainUrl/search?keyword=$encodedQuery" else "$mainUrl/search?keyword=$encodedQuery&page_id=$page"
            val doc = getBrowserDoc(targetUrl)
            val cards = doc.select(".video-latest-list, .video-wrapper")

            for (card in cards) {
                val rawUrl = card.selectFirst(".video-thumb > a")?.attr("href")?.trim() ?: continue
                val image = card.selectFirst("img")?.attr("src")?.trim() ?: ""
                val title = card.selectFirst("p.hptag, h4")?.attr("title")?.trim()?.ifEmpty { null }
                    ?: card.selectFirst("p.hptag, h4")?.text()?.trim() ?: "Unknown"

                if (rawUrl.isNotEmpty()) {
                    val fullUrl = if (rawUrl.startsWith("http")) rawUrl else "$mainUrl$rawUrl"
                    list.add(
                        MovieItem(
                            title = title,
                            image = image,
                            url = fullUrl,
                            isPoster = false,
                            referer = "$mainUrl/"
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    override suspend fun loadMediaStructure(postUrl: String): MediaStructure {
        try {
            val doc = getBrowserDoc(postUrl)
            val videoId = doc.selectFirst("#see-more-res, [data-vid]")?.attr("data-vid")?.trim() ?: ""
            val currentRes = doc.selectFirst(".available-res .res-btn.selected")?.text()?.trim() ?: "HD"

            if (videoId.isNotEmpty()) {
                val epApiUrl = "$mainUrl/ajax/episodes.php?video_id=$videoId"
                val epJson = getBrowserJson(epApiUrl)

                if (epJson.contains("\"is_current\"")) {
                    val seasonMap = mutableMapOf<String, MutableList<EpisodeItem>>()
                    val epRegex = Regex("""\{"display":"(.*?)","url":"(.*?)","is_current":(true|false)\}""")
                    
                    for (match in epRegex.findAll(epJson)) {
                        val display = match.groupValues[1].replace("\\/", "/").trim()
                        val relUrl = match.groupValues[2].replace("\\/", "/").trim()
                        
                        if (relUrl.isNotEmpty()) {
                            val epFullUrl = if (relUrl.startsWith("http")) relUrl else "$mainUrl$relUrl"
                            val seasonMatch = Regex("""S(\d+)""", RegexOption.IGNORE_CASE).find(display)
                            val seasonName = if (seasonMatch != null) "Season ${seasonMatch.groupValues[1].toInt()}" else "Season 1"
                            
                            val rawLinks = mutableListOf(Pair(currentRes, epFullUrl))
                            seasonMap.getOrPut(seasonName) { mutableListOf() }.add(EpisodeItem(name = display, rawGenerateLinks = rawLinks))
                        }
                    }
                    val seasonsList = seasonMap.map { SeasonItem(name = it.key, episodes = it.value) }
                    if (seasonsList.isNotEmpty()) {
                        return MediaStructure(isSeries = true, rawMovieLinks = emptyList(), seasons = seasonsList)
                    }
                }
            }

            val rawMovieLinks = mutableListOf<Pair<String, String>>()
            rawMovieLinks.add(Pair(currentRes, postUrl))

            if (videoId.isNotEmpty()) {
                val resApiUrl = "$mainUrl/ajax/resolution_switcher.php?video_id=$videoId"
                val resJson = getBrowserJson(resApiUrl)
                val resRegex = Regex("""\{"res":"(.*?)","url":"(.*?)","selected":(true|false)\}""")
                
                for (match in resRegex.findAll(resJson)) {
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
        return MediaStructure(isSeries = false, rawMovieLinks = listOf(Pair("HD", postUrl)), seasons = emptyList())
    }

    override suspend fun resolveDirectLink(generateUrl: String): StreamItem? {
        try {
            val doc = getBrowserDoc(generateUrl)
            val htmlContent = doc.html()
            val videoUrlRegex = Regex("""var\s+VIDEO_URL\s*=\s*["']([^"']+)["']""")
            val directStreamUrl = videoUrlRegex.find(htmlContent)?.groupValues?.get(1)?.trim() ?: ""

            if (directStreamUrl.isNotEmpty()) {
                val cleanedUrl = directStreamUrl.replace("\\/", "/")
                return StreamItem(
                    quality = "HD",
                    streamUrl = cleanedUrl,
                    headers = mapOf("Referer" to "$mainUrl/", "User-Agent" to userAgent)
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }
}
