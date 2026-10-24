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

    private val userAgent = "Mozilla/5.0 (Linux; Android 16; 23090RA98I Build/BP2A.250605.031.A3) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/155.0.8059.43 Mobile Safari/537.36"

    private var sessionCookies: MutableMap<String, String> = mutableMapOf()

    // এই ফাংশনটি দিয়ে ব্রাউজারের মতো রিকোয়েস্ট পাঠানো হবে এবং ফ্র্যাগমেন্ট পার্স করা হবে
    private suspend fun getBrowserDoc(targetUrl: String, refererUrl: String = "$mainUrl/"): Document = withContext(Dispatchers.IO) {
        val resp = Jsoup.connect(targetUrl)
            .userAgent(userAgent)
            .referrer(refererUrl)
            .header("Accept", "*/*")
            .header("Accept-Language", "en-US,en;q=0.9,bn;q=0.8")
            .header("sec-ch-ua", "\"Chromium\";v=\"155\", \"Not A(Brand\";v=\"24\", \"Google Chrome\";v=\"155\"")
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

        // KVS এর ইনকমপ্লিট HTML ফ্র্যাগমেন্টগুলো পার্স করার জন্য parseBodyFragment ব্যবহার করা হলো
        Jsoup.parseBodyFragment(htmlBody, mainUrl)
    }

    override suspend fun getMainPage(page: Int): List<MovieItem> {
        val list = mutableListOf<MovieItem>()
        try {
            val targetUrl = "$mainUrl/?mode=async&function=get_block&block_id=list_videos_most_recent_videos&sort_by=post_date&from=$page&_=${System.currentTimeMillis()}"
            val doc = getBrowserDoc(targetUrl)
            
            // সব thumb item সিলেক্ট করা
            val items = doc.select(".thumb.item, .thumb_rel.item, .thumb")

            for (item in items) {
                // বিজ্ঞাপন ও প্রমোশনাল ব্যানার ইগনোর করা
                if (item.selectFirst("ins, .qualtiy:contains(AD), .title:contains(KamaReels)") != null) {
                    continue
                }

                val anchor = item.selectFirst("a[href*='/video/'], a") ?: continue
                val rawUrl = anchor.attr("href").trim()
                if (rawUrl.isEmpty() || rawUrl == "#") continue

                val imgElement = item.selectFirst("img")
                var image = imgElement?.attr("data-webp")?.trim().orEmpty()
                if (image.isEmpty()) {
                    image = imgElement?.attr("src")?.trim().orEmpty()
                }
                if (image.startsWith("//")) {
                    image = "https:$image"
                }

                val title = item.selectFirst(".title")?.text()?.trim()
                    ?: anchor.attr("title").trim().ifEmpty { null }
                    ?: imgElement?.attr("alt")?.trim()
                    ?: "Unknown Title"

                val fullUrl = if (rawUrl.startsWith("http")) rawUrl else "$mainUrl$rawUrl"

                list.add(
                    MovieItem(
                        title = title,
                        image = image,
                        url = fullUrl,
                        isPoster = false, // ব্যানার থাম্বনেইল
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
            val items = doc.select(".thumb.item, .thumb_rel.item, .thumb")

            for (item in items) {
                if (item.selectFirst("ins, .qualtiy:contains(AD), .title:contains(KamaReels)") != null) {
                    continue
                }

                val anchor = item.selectFirst("a[href*='/video/'], a") ?: continue
                val rawUrl = anchor.attr("href").trim()
                if (rawUrl.isEmpty() || rawUrl == "#") continue

                val imgElement = item.selectFirst("img")
                var image = imgElement?.attr("data-webp")?.trim().orEmpty()
                if (image.isEmpty()) {
                    image = imgElement?.attr("src")?.trim().orEmpty()
                }
                if (image.startsWith("//")) {
                    image = "https:$image"
                }

                val title = item.selectFirst(".title")?.text()?.trim()
                    ?: anchor.attr("title").trim().ifEmpty { null }
                    ?: imgElement?.attr("alt")?.trim()
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
            // ভিডিওর ডিরেক্ট পেজটি async নয়, তাই এটি সরাসরি parse হবে
            val resp = Jsoup.connect(generateUrl)
                .userAgent(userAgent)
                .referrer("$mainUrl/")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9,bn;q=0.8")
                .header("Sec-Fetch-Dest", "document")
                .header("Sec-Fetch-Mode", "navigate")
                .header("Sec-Fetch-Site", "same-origin")
                .cookies(sessionCookies)
                .ignoreContentType(true)
                .ignoreHttpErrors(true)
                .timeout(25000)
                .execute()

            sessionCookies.putAll(resp.cookies())
            val html = resp.body()

            var directStreamUrl = ""

            // ১. event_reporting2 অথবা get_file পাথ থেকে \d+/\d+/\d+\.mp4 ক্যাপচার করা
            val pathRegex = Regex("""(?:get_file/\d+/[a-f0-9]+/|get_file/[^/]+/[^/]+/)?(\d+/\d+/\d+\.mp4)""")
            val match = pathRegex.find(html)

            if (match != null) {
                val relativePath = match.groupValues[1]
                directStreamUrl = "$cdnUrl/$relativePath"
            }

            // ২. ব্যাকআপ চেক: HTML-এ ডিরেক্ট cdn লিঙ্ক বা video source ট্যাগ
            if (directStreamUrl.isEmpty()) {
                val cdnRegex = Regex("""https?://cdn\.xmishti\.com/[^\s"'<>]+\.mp4""")
                val cdnMatch = cdnRegex.find(html)
                if (cdnMatch != null) {
                    directStreamUrl = cdnMatch.value
                }
            }

            if (directStreamUrl.isEmpty()) {
                val videoSrc = Jsoup.parseBodyFragment(html).selectFirst("video source, video")?.attr("src")?.trim().orEmpty()
                if (videoSrc.isNotEmpty()) {
                    directStreamUrl = if (videoSrc.startsWith("http")) videoSrc else "$mainUrl$videoSrc"
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
