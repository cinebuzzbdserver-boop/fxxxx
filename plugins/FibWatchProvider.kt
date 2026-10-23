package com.moviefy.stream.providers

import com.moviefy.stream.ui.screens.EpisodeItem
import com.moviefy.stream.ui.screens.MainAPI
import com.moviefy.stream.ui.screens.MediaStructure
import com.moviefy.stream.ui.screens.MovieItem
import com.moviefy.stream.ui.screens.SeasonItem
import com.moviefy.stream.ui.screens.StreamItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class FibWatchProvider : MainAPI {
    override var name = "FibWatch"
    override var mainUrl = "https://fibwatch.art"

    private val userAgent = "Mozilla/5.0 (Linux; Android 16; 23090RA98I Build/BP2A.250605.031.A3) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/153.0.8010.57 Mobile Safari/537.36"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private suspend fun fetchHtml(url: String): String = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .header("Referer", "$mainUrl/")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("sec-ch-ua", "\"Chromium\";v=\"153\", \"Google Chrome\";v=\"153\", \"Not-A.Brand\";v=\"99\"")
                .header("sec-ch-ua-mobile", "?1")
                .header("sec-ch-ua-platform", "\"Android\"")
                .header("Upgrade-Insecure-Requests", "1")
                .header("Sec-Fetch-Site", "none")
                .header("Sec-Fetch-Mode", "navigate")
                .header("Sec-Fetch-User", "?1")
                .header("Sec-Fetch-Dest", "document")
                .build()

            httpClient.newCall(req).execute().use { resp ->
                resp.body?.string() ?: ""
            }
        } catch (_: Exception) {
            ""
        }
    }

    private suspend fun fetchJson(url: String, refererUrl: String): String = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .header("Referer", refererUrl)
                .header("Accept", "application/json, text/javascript, */*; q=0.01")
                .header("X-Requested-With", "XMLHttpRequest")
                .build()

            httpClient.newCall(req).execute().use { resp ->
                resp.body?.string()?.trim() ?: ""
            }
        } catch (_: Exception) {
            ""
        }
    }

    override suspend fun getMainPage(page: Int): List<MovieItem> {
        val list = mutableListOf<MovieItem>()
        try {
            val targetUrl = if (page <= 1) "$mainUrl/videos/latest" else "$mainUrl/videos/latest?page_id=$page"
            val html = fetchHtml(targetUrl)
            if (html.isEmpty()) return emptyList()

            val doc = Jsoup.parse(html)
            // অরিজিনাল সিলেক্টর লজিক
            val elements = doc.select(".video-wrapper")

            for (element in elements) {
                val anchor = element.select(".video-thumb a").first() ?: continue
                val rawUrl = anchor.attr("href").takeIf { it.isNotEmpty() } ?: continue
                val imgEl = anchor.select("img").first()
                val image = imgEl?.attr("src")?.trim() ?: ""
                val titleEl = element.select("p.hptag").first() ?: element.select("h4").first()
                val rawTitle = titleEl?.attr("title")?.takeIf { it.isNotEmpty() } ?: titleEl?.text() ?: "Unknown Title"

                val postUrl = if (rawUrl.startsWith("/")) "$mainUrl$rawUrl" else if (!rawUrl.startsWith("http")) "$mainUrl/$rawUrl" else rawUrl

                list.add(
                    MovieItem(
                        title = rawTitle.trim(),
                        image = image,
                        url = postUrl,
                        isPoster = false,
                        referer = "$mainUrl/"
                    )
                )
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
            val targetUrl = "$mainUrl/search?keyword=$encodedQuery&page_id=$page"
            val html = fetchHtml(targetUrl)
            if (html.isEmpty()) return emptyList()

            val doc = Jsoup.parse(html)
            val elements = doc.select(".video-wrapper")

            for (element in elements) {
                val anchor = element.select(".video-thumb a").first() ?: continue
                val rawUrl = anchor.attr("href").takeIf { it.isNotEmpty() } ?: continue
                val imgEl = anchor.select("img").first()
                val image = imgEl?.attr("src")?.trim() ?: ""
                val titleEl = element.select("p.hptag").first() ?: element.select("h4").first()
                val rawTitle = titleEl?.attr("title")?.takeIf { it.isNotEmpty() } ?: titleEl?.text() ?: "Unknown Title"

                val postUrl = if (rawUrl.startsWith("/")) "$mainUrl$rawUrl" else if (!rawUrl.startsWith("http")) "$mainUrl/$rawUrl" else rawUrl

                list.add(
                    MovieItem(
                        title = rawTitle.trim(),
                        image = image,
                        url = postUrl,
                        isPoster = false,
                        referer = "$mainUrl/"
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    override suspend fun loadMediaStructure(postUrl: String): MediaStructure {
        try {
            val html = fetchHtml(postUrl)
            val doc = Jsoup.parse(html)
            val videoId = doc.selectFirst("#see-more-res, [data-vid]")?.attr("data-vid")?.trim() ?: ""
            val currentRes = doc.selectFirst(".available-res .res-btn.selected")?.text()?.trim() ?: "HD"

            if (videoId.isNotEmpty()) {
                val epApiUrl = "$mainUrl/ajax/episodes.php?video_id=$videoId"
                val epJson = fetchJson(epApiUrl, postUrl)

                if (epJson.contains("\"is_current\"")) {
                    val seasonMap = mutableMapOf<String, MutableList<EpisodeItem>>()
                    val epRegex = Regex("""\{"display":"(.*?)","url":"(.*?)","is_current":(true|false)\}""")

                    for (match in epRegex.findAll(epJson)) {
                        val display = match.groupValues[1].replace("\\/", "/").trim()
                        val relUrl = match.groupValues[2].replace("\\/", "/").trim()

                        if (relUrl.isNotEmpty()) {
                            val epFullUrl = if (relUrl.startsWith("/")) "$mainUrl$relUrl" else if (!relUrl.startsWith("http")) "$mainUrl/$relUrl" else relUrl
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
                val resJson = fetchJson(resApiUrl, postUrl)
                val resRegex = Regex("""\{"res":"(.*?)","url":"(.*?)","selected":(true|false)\}""")

                for (match in resRegex.findAll(resJson)) {
                    val res = match.groupValues[1].trim()
                    val relUrl = match.groupValues[2].replace("\\/", "/").trim()
                    val isSelected = match.groupValues[3].toBoolean()

                    if (!isSelected && relUrl.isNotEmpty() && !res.equals("See More", ignoreCase = true)) {
                        val fullUrl = if (relUrl.startsWith("/")) "$mainUrl$relUrl" else if (!relUrl.startsWith("http")) "$mainUrl/$relUrl" else relUrl
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
            val html = fetchHtml(generateUrl)
            val videoUrlRegex = Regex("""var\s+VIDEO_URL\s*=\s*["']([^"']+)["']""")
            val directStreamUrl = videoUrlRegex.find(html)?.groupValues?.get(1)?.trim() ?: ""

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
