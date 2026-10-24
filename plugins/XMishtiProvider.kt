package com.moviefy.stream.providers

import com.moviefy.stream.ui.screens.MainAPI
import com.moviefy.stream.ui.screens.MediaStructure
import com.moviefy.stream.ui.screens.MovieItem
import com.moviefy.stream.ui.screens.StreamItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder

class XMishtiProvider : MainAPI {
    override var name = "XMishti"
    override var mainUrl = "https://www.xmishti.com"
    private val cdnUrl = "https://cdn.xmishti.com"

    private val userAgent = "Mozilla/5.0 (Linux; Android 16; 23090RA98I Build/BP2A.250605.031.A3) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/153.0.8010.57 Mobile Safari/537.36"

    private var sessionCookies: MutableMap<String, String> = mutableMapOf()

    private suspend fun getBrowserDoc(targetUrl: String, refererUrl: String = "$mainUrl/"): Document = withContext(Dispatchers.IO) {
        val resp = Jsoup.connect(targetUrl)
            .userAgent(userAgent)
            .referrer(refererUrl)
            .header("Accept", "*/*")
            .header("Accept-Language", "en-US,en;q=0.9,bn;q=0.8")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("sec-ch-ua", "\"Chromium\";v=\"153\", \"Not A(Brand\";v=\"24\", \"Google Chrome\";v=\"153\"")
            .header("sec-ch-ua-mobile", "?1")
            .header("sec-ch-ua-platform", "\"Android\"")
            .header("Sec-Fetch-Dest", "empty")
            .header("Sec-Fetch-Mode", "cors")
            .header("Sec-Fetch-Site", "same-origin")
            .cookies(sessionCookies)
            .ignoreContentType(true)
            .ignoreHttpErrors(true)
            .timeout(25000)
            .execute()

        sessionCookies.putAll(resp.cookies())
        val htmlBody = resp.body()

        // ফ্র্যাগমেন্ট বা ফুল HTML উভয়ের জন্যই সেফ পার্সিং
        Jsoup.parseBodyFragment(htmlBody, mainUrl)
    }

    override suspend fun getMainPage(page: Int): List<MovieItem> {
        val list = mutableListOf<MovieItem>()
        try {
            // পেজ ১ হলে from=1, পরবর্তীগুলো পেজ অনুযায়ী
            val targetUrl = "$mainUrl/?mode=async&function=get_block&block_id=list_videos_most_recent_videos&sort_by=post_date&from=$page&_=${System.currentTimeMillis()}"
            val doc = getBrowserDoc(targetUrl, refererUrl = "$mainUrl/")
            
            // তোমার দেওয়া HTML অনুসারে .thumb অথবা ডিরেক্ট ভিডিও কনটেইনার টার্গেট
            val elements = doc.select(".thumbs .thumb, .thumb, div[class*='thumb']")

            for (el in elements) {
                // বিজ্ঞাপন বাদ দেওয়া
                if (el.selectFirst("ins, [class*='qualtiy']:contains(AD), .title:contains(KamaReels)") != null) {
                    continue
                }

                val anchor = el.selectFirst("a[href*='/video/']") ?: el.selectFirst("a") ?: continue
                val rawUrl = anchor.attr("href").trim()
                if (rawUrl.isEmpty() || rawUrl == "#") continue

                val imgEl = el.selectFirst("img")
                var image = imgEl?.attr("data-webp")?.trim().orEmpty()
                if (image.isEmpty()) {
                    image = imgEl?.attr("src")?.trim().orEmpty()
                }
                if (image.startsWith("//")) {
                    image = "https:$image"
                }

                val title = el.selectFirst(".title")?.text()?.trim()
                    ?: anchor.attr("title").trim().ifEmpty { null }
                    ?: imgEl?.attr("alt")?.trim()
                    ?: "Unknown Title"

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
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    override suspend fun search(query: String, page: Int): List<MovieItem> {
        val list = mutableListOf<MovieItem>()
        try {
            val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8")
            val targetUrl = "$mainUrl/search/$encodedQuery/?mode=async&function=get_block&block_id=list_videos_videos_list_search_result&q=$encodedQuery&category_ids=&sort_by=&from_videos=$page&_=${System.currentTimeMillis()}"
            
            val doc = getBrowserDoc(targetUrl, refererUrl = "$mainUrl/search/$encodedQuery/")
            val elements = doc.select(".thumbs .thumb, .thumb, div[class*='thumb']")

            for (el in elements) {
                if (el.selectFirst("ins, [class*='qualtiy']:contains(AD), .title:contains(KamaReels)") != null) {
                    continue
                }

                val anchor = el.selectFirst("a[href*='/video/']") ?: el.selectFirst("a") ?: continue
                val rawUrl = anchor.attr("href").trim()
                if (rawUrl.isEmpty() || rawUrl == "#") continue

                val imgEl = el.selectFirst("img")
                var image = imgEl?.attr("data-webp")?.trim().orEmpty()
                if (image.isEmpty()) {
                    image = imgEl?.attr("src")?.trim().orEmpty()
                }
                if (image.startsWith("//")) {
                    image = "https:$image"
                }

                val title = el.selectFirst(".title")?.text()?.trim()
                    ?: anchor.attr("title").trim().ifEmpty { null }
                    ?: imgEl?.attr("alt")?.trim()
                    ?: "Unknown Title"

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
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    override suspend fun loadMediaStructure(postUrl: String): MediaStructure {
        val rawMovieLinks = mutableListOf<Pair<String, String>>()
        try {
            rawMovieLinks.add(Pair("Direct Video", postUrl))
            return MediaStructure(
                isSeries = false,
                rawMovieLinks = rawMovieLinks,
                seasons = emptyList()
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return MediaStructure(isSeries = false, rawMovieLinks = listOf(Pair("Direct Video", postUrl)), seasons = emptyList())
    }

    override suspend fun resolveDirectLink(generateUrl: String): StreamItem? {
        try {
            val doc = getBrowserDoc(generateUrl, refererUrl = "$mainUrl/")
            val html = doc.html()

            var directStreamUrl = ""

            // ১. event_reporting2 অথবা get_file পাথ থেকে \d+/\d+/\d+\.mp4 ফিল্টার
            val pathRegex = Regex("""get_file/\d+/[a-f0-9]+/(\d+/\d+/\d+\.mp4)""")
            val match = pathRegex.find(html)

            if (match != null) {
                val relativePath = match.groupValues[1]
                directStreamUrl = "$cdnUrl/$relativePath"
            }

            // ২. ব্যাকআপ চেক
            if (directStreamUrl.isEmpty()) {
                val directMatch = Regex("""['"](?:https?://[^'"]+?/)?(\d+/\d+/\d+\.mp4)[^'"]*?['"]""").find(html)
                if (directMatch != null) {
                    val relativePath = directMatch.groupValues[1]
                    directStreamUrl = "$cdnUrl/$relativePath"
                }
            }

            if (directStreamUrl.isNotEmpty()) {
                val cleanedUrl = directStreamUrl.replace("\\/", "/")
                return StreamItem(
                    quality = "HD",
                    streamUrl = cleanedUrl,
                    headers = mapOf(
                        "Referer" to "$mainUrl/",
                        "User-Agent" to userAgent
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }
}
