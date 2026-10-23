package com.moviefy.stream.providers

import com.moviefy.stream.ui.screens.EpisodeItem
import com.moviefy.stream.ui.screens.MainAPI
import com.moviefy.stream.ui.screens.MediaStructure
import com.moviefy.stream.ui.screens.MovieItem
import com.moviefy.stream.ui.screens.SeasonItem
import com.moviefy.stream.ui.screens.StreamItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class FibWatchProvider : MainAPI {
    override var name = "FibWatch"
    override var mainUrl = "https://fibwatch.art"

    private val userAgent = "Mozilla/5.0 (Linux; Android 16; 23090RA98I Build/BP2A.250605.031.A3) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/153.0.8010.57 Mobile Safari/537.36"

    private val cookieStore = HashMap<String, List<Cookie>>()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .cookieJar(object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                cookieStore[url.host] = cookies
            }
            override fun loadForRequest(url: HttpUrl): List<Cookie> {
                return cookieStore[url.host] ?: ArrayList()
            }
        })
        .build()

    private fun fetchHtmlSync(targetUrl: String, referer: String = "$mainUrl/"): String {
        return try {
            val request = Request.Builder()
                .url(targetUrl)
                .header("User-Agent", userAgent)
                .header("Referer", referer)
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

            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: ""
            response.close()
            body
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
    }

    private fun fetchAjaxSync(targetUrl: String, referer: String): String {
        return try {
            val request = Request.Builder()
                .url(targetUrl)
                .header("User-Agent", userAgent)
                .header("Referer", referer)
                .header("Accept", "application/json, text/javascript, */*; q=0.01")
                .header("X-Requested-With", "XMLHttpRequest")
                .build()

            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: ""
            response.close()
            body.trim()
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
    }

    private fun parseMoviesList(html: String): List<MovieItem> {
        if (html.isEmpty()) return emptyList()
        val doc = Jsoup.parse(html)
        val elements = doc.select(".video-wrapper, .video-latest-list")
        val items = mutableListOf<MovieItem>()

        for (element in elements) {
            val anchor = element.select(".video-thumb a").first() ?: continue
            val rawUrl = anchor.attr("href").trim().takeIf { it.isNotEmpty() } ?: continue
            val imgEl = element.select("img").first()
            val imageUrl = imgEl?.attr("src")?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("data:") }
                ?: imgEl?.attr("data-src")?.trim() ?: ""
            val titleEl = element.select("p.hptag").first() ?: element.select("h4").first()
            val rawTitle = titleEl?.attr("title")?.trim()?.takeIf { it.isNotEmpty() }
                ?: titleEl?.text()?.trim()?.takeIf { it.isNotEmpty() }
                ?: "Unknown Title"

            val fullPostUrl = when {
                rawUrl.startsWith("http") -> rawUrl
                rawUrl.startsWith("/") -> "${mainUrl.trimEnd('/')}$rawUrl"
                else -> "${mainUrl.trimEnd('/')}/$rawUrl"
            }

            val fullImageUrl = when {
                imageUrl.startsWith("http") -> imageUrl
                imageUrl.startsWith("/") -> "${mainUrl.trimEnd('/')}$imageUrl"
                else -> imageUrl
            }

            items.add(
                MovieItem(
                    title = rawTitle,
                    image = fullImageUrl,
                    url = fullPostUrl,
                    isPoster = false,
                    referer = "$mainUrl/"
                )
            )
        }
        return items.distinctBy { it.url }
    }

    fun fetchMainPageJson(page: Int): String {
        val base = mainUrl.trimEnd('/')
        val targetUrl = if (page <= 1) "$base/videos/latest" else "$base/videos/latest?page_id=$page"
        val items = parseMoviesList(fetchHtmlSync(targetUrl))
        val arr = JSONArray()
        for (item in items) {
            arr.put(JSONObject().apply {
                put("title", item.title)
                put("image", item.image)
                put("url", item.url)
                put("isPoster", item.isPoster)
                put("referer", item.referer ?: "$base/")
            })
        }
        return arr.toString()
    }

    fun fetchSearchJson(query: String, page: Int): String {
        val base = mainUrl.trimEnd('/')
        val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8")
        val targetUrl = "$base/search?keyword=$encodedQuery&page_id=$page"
        val items = parseMoviesList(fetchHtmlSync(targetUrl))
        val arr = JSONArray()
        for (item in items) {
            arr.put(JSONObject().apply {
                put("title", item.title)
                put("image", item.image)
                put("url", item.url)
                put("isPoster", item.isPoster)
                put("referer", item.referer ?: "$base/")
            })
        }
        return arr.toString()
    }

    override suspend fun getMainPage(page: Int): List<MovieItem> = withContext(Dispatchers.IO) {
        val base = mainUrl.trimEnd('/')
        val targetUrl = if (page <= 1) "$base/videos/latest" else "$base/videos/latest?page_id=$page"
        parseMoviesList(fetchHtmlSync(targetUrl))
    }

    override suspend fun search(query: String, page: Int): List<MovieItem> = withContext(Dispatchers.IO) {
        val base = mainUrl.trimEnd('/')
        val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8")
        val targetUrl = "$base/search?keyword=$encodedQuery&page_id=$page"
        parseMoviesList(fetchHtmlSync(targetUrl))
    }

    override suspend fun loadMediaStructure(postUrl: String): MediaStructure = withContext(Dispatchers.IO) {
        val base = mainUrl.trimEnd('/')
        try {
            val html = fetchHtmlSync(postUrl)
            val doc = Jsoup.parse(html)
            val videoId = doc.select("#see-more-res, [data-vid]").first()?.attr("data-vid")?.trim() ?: ""
            val currentRes = doc.select(".available-res .res-btn.selected").first()?.text()?.trim()?.ifEmpty { "HD" } ?: "HD"

            if (videoId.isNotEmpty()) {
                val epApiUrl = "$base/ajax/episodes.php?video_id=$videoId"
                val epJson = fetchAjaxSync(epApiUrl, postUrl)

                if (epJson.contains("\"is_current\"")) {
                    val seasonMap = linkedMapOf<String, MutableList<EpisodeItem>>()
                    val epRegex = Regex("""\{"display":"(.*?)","url":"(.*?)","is_current":(true|false)\}""")

                    for (match in epRegex.findAll(epJson)) {
                        val display = match.groupValues[1].replace("\\/", "/").trim()
                        val relUrl = match.groupValues[2].replace("\\/", "/").trim()

                        if (relUrl.isNotEmpty()) {
                            val epFullUrl = when {
                                relUrl.startsWith("http") -> relUrl
                                relUrl.startsWith("/") -> "$base$relUrl"
                                else -> "$base/$relUrl"
                            }
                            val seasonMatch = Regex("""S(\d+)""", RegexOption.IGNORE_CASE).find(display)
                            val seasonName = if (seasonMatch != null) "Season ${seasonMatch.groupValues[1].toInt()}" else "Season 1"

                            val rawLinks = listOf(Pair(currentRes, epFullUrl))
                            seasonMap.getOrPut(seasonName) { mutableListOf() }.add(EpisodeItem(name = display, rawGenerateLinks = rawLinks))
                        }
                    }
                    val seasonsList = seasonMap.map { SeasonItem(name = it.key, episodes = it.value) }
                    if (seasonsList.isNotEmpty()) {
                        return@withContext MediaStructure(isSeries = true, rawMovieLinks = emptyList(), seasons = seasonsList)
                    }
                }
            }

            val rawMovieLinks = mutableListOf<Pair<String, String>>()

            if (videoId.isNotEmpty()) {
                try {
                    val ajaxUrl = "$base/ajax/resolution_switcher.php?video_id=$videoId"
                    val resJson = fetchAjaxSync(ajaxUrl, postUrl)
                    val popupArray = JSONObject(resJson).optJSONArray("popup")

                    if (popupArray != null) {
                        for (i in 0 until popupArray.length()) {
                            val popItem = popupArray.getJSONObject(i)
                            val resText = popItem.optString("res", "HD").trim()
                            val isSelected = popItem.optBoolean("selected", false)
                            val popUrl = popItem.optString("url", "").replace("\\/", "/").trim()

                            if (resText.equals("See More", ignoreCase = true)) continue

                            if (isSelected) {
                                rawMovieLinks.add(Pair(resText, postUrl))
                            } else if (popUrl.isNotEmpty()) {
                                val fullPopUrl = when {
                                    popUrl.startsWith("http") -> popUrl
                                    popUrl.startsWith("/") -> "$base$popUrl"
                                    else -> "$base/$popUrl"
                                }
                                rawMovieLinks.add(Pair(resText, fullPopUrl))
                            }
                        }
                    }
                } catch (_: Exception) {}
            }

            if (rawMovieLinks.isEmpty()) {
                rawMovieLinks.add(Pair(currentRes, postUrl))
            }

            return@withContext MediaStructure(isSeries = false, rawMovieLinks = rawMovieLinks.distinctBy { it.second }, seasons = emptyList())
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext MediaStructure(isSeries = false, rawMovieLinks = listOf(Pair("HD", postUrl)), seasons = emptyList())
    }

    override suspend fun resolveDirectLink(generateUrl: String): StreamItem? = withContext(Dispatchers.IO) {
        val base = mainUrl.trimEnd('/')
        try {
            val html = fetchHtmlSync(generateUrl)
            val videoUrlRegex = Regex("""var\s+VIDEO_URL\s*=\s*['"]([^'"]+)['"]""")
            val directStreamUrl = videoUrlRegex.find(html)?.groupValues?.get(1)?.trim() ?: ""

            if (directStreamUrl.isNotEmpty()) {
                val cleanedUrl = directStreamUrl.replace("\\/", "/")
                return@withContext StreamItem(
                    quality = "HD",
                    streamUrl = cleanedUrl,
                    headers = mapOf("Referer" to "$base/", "User-Agent" to userAgent)
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext null
    }
}
