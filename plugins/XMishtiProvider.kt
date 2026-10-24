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

    private val userAgent = "Mozilla/5.0 (Linux; Android 16; 23090RA98I Build/BP2A.250605.031.A3) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/155.0.8059.43 Mobile Safari/537.36"

    // AppConfig থেকে যদি www ছাড়া লিংক আসে, তবুও যেন www সহ সঠিক লিংক থাকে
    private fun getBaseUrl(): String {
        return if (mainUrl.contains("://xmishti.com")) {
            "https://www.xmishti.com"
        } else {
            mainUrl.trimEnd('/')
        }
    }

    // একদম MMSVibexProvider এবং তোমার cURL এর মতো সিম্পল ও ক্লিন রিকোয়েস্ট
    private suspend fun getBrowserDoc(targetUrl: String): Document = withContext(Dispatchers.IO) {
        Jsoup.connect(targetUrl)
            .userAgent(userAgent)
            .referrer("${getBaseUrl()}/")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .ignoreContentType(true)
            .ignoreHttpErrors(true)
            .followRedirects(true)
            .timeout(20000)
            .get()
    }

    override suspend fun getMainPage(page: Int): List<MovieItem> {
        val list = mutableListOf<MovieItem>()
        try {
            val base = getBaseUrl()
            // হুবহু তোমার দেওয়া হোমপেজ ও পেজিনেশন লিংক (১, ২, ৩...)
            val targetUrl = "$base/?mode=async&function=get_block&block_id=list_videos_most_recent_videos&sort_by=post_date&from=$page"
            val doc = getBrowserDoc(targetUrl)

            // তোমার দেওয়া HTML-এর <div class="thumb thumb_rel item"> সিলেক্ট করা
            val articles = doc.select("div.thumb")

            for (article in articles) {
                // বিজ্ঞাপন (AD / KamaReels) ইগনোর করা
                if (article.selectFirst("ins") != null || 
                    article.selectFirst(".qualtiy")?.text()?.contains("AD", ignoreCase = true) == true) {
                    continue
                }

                val anchor = article.selectFirst("a") ?: continue
                val rawUrl = anchor.attr("href").trim()
                if (rawUrl.isEmpty() || !rawUrl.contains("/video/")) continue

                val imgElement = article.selectFirst("img")
                var image = imgElement?.attr("src")?.trim().orEmpty()
                if (image.isEmpty()) {
                    image = imgElement?.attr("data-webp")?.trim().orEmpty()
                }

                val title = article.selectFirst("div.title")?.text()?.trim()
                    ?: anchor.attr("title").trim().ifEmpty { null }
                    ?: imgElement?.attr("alt")?.trim()
                    ?: "Unknown Title"

                val fullUrl = if (rawUrl.startsWith("http")) rawUrl else "$base$rawUrl"

                list.add(
                    MovieItem(
                        title = title,
                        image = image,
                        url = fullUrl,
                        isPoster = false,
                        referer = "$base/"
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
            val base = getBaseUrl()
            val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
            
            // হুবহু তোমার দেওয়া সার্চ এবং পরবর্তী পেজের স্ট্রাকচার
            val targetUrl = "$base/search/$encodedQuery/?mode=async&function=get_block&block_id=list_videos_videos_list_search_result&q=$encodedQuery&category_ids=&sort_by=&from_videos=$page"

            val doc = getBrowserDoc(targetUrl)
            val articles = doc.select("div.thumb")

            for (article in articles) {
                if (article.selectFirst("ins") != null || 
                    article.selectFirst(".qualtiy")?.text()?.contains("AD", ignoreCase = true) == true) {
                    continue
                }

                val anchor = article.selectFirst("a") ?: continue
                val rawUrl = anchor.attr("href").trim()
                if (rawUrl.isEmpty() || !rawUrl.contains("/video/")) continue

                val imgElement = article.selectFirst("img")
                var image = imgElement?.attr("src")?.trim().orEmpty()
                if (image.isEmpty()) {
                    image = imgElement?.attr("data-webp")?.trim().orEmpty()
                }

                val title = article.selectFirst("div.title")?.text()?.trim()
                    ?: anchor.attr("title").trim().ifEmpty { null }
                    ?: imgElement?.attr("alt")?.trim()
                    ?: "Unknown Title"

                val fullUrl = if (rawUrl.startsWith("http")) rawUrl else "$base$rawUrl"

                list.add(
                    MovieItem(
                        title = title,
                        image = image,
                        url = fullUrl,
                        isPoster = false,
                        referer = "$base/"
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
            rawMovieLinks.add(Pair("Direct Stream", postUrl))
            return MediaStructure(
                isSeries = false,
                rawMovieLinks = rawMovieLinks,
                seasons = emptyList()
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return MediaStructure(isSeries = false, rawMovieLinks = listOf(Pair("Direct Stream", postUrl)), seasons = emptyList())
    }

    override suspend fun resolveDirectLink(generateUrl: String): StreamItem? {
        try {
            val base = getBaseUrl()
            val doc = getBrowserDoc(generateUrl)
            val html = doc.html()

            // তোমার নির্দেশনা অনুযায়ী:
            // event_reporting2: 'https://www.xmishti.com/get_file/2/8eec.../4000/4053/4053.mp4/?v-acctoken=...'
            // এখান থেকে শুধু 4000/4053/4053.mp4 অংশটি নিয়ে https://cdn.xmishti.com/ এর সাথে যুক্ত করা
            val pathRegex = Regex("""get_file/\d+/[a-zA-Z0-9]+/(\d+/\d+/\d+\.mp4)""")
            val match = pathRegex.find(html)

            var directStreamUrl = ""
            if (match != null) {
                val filePath = match.groupValues[1] // যেমন: 4000/4053/4053.mp4
                directStreamUrl = "https://cdn.xmishti.com/$filePath"
            }

            // যদি অন্য কোনো ফরম্যাটে পাথ থাকে তার ব্যাকআপ
            if (directStreamUrl.isEmpty()) {
                val fallbackRegex = Regex("""/(\d+/\d+/\d+\.mp4)""")
                val fallbackMatch = fallbackRegex.find(html)
                if (fallbackMatch != null) {
                    val filePath = fallbackMatch.groupValues[1]
                    directStreamUrl = "https://cdn.xmishti.com/$filePath"
                }
            }

            if (directStreamUrl.isNotEmpty()) {
                val cleanedUrl = directStreamUrl.replace("\\/", "/")
                return StreamItem(
                    quality = "HD",
                    streamUrl = cleanedUrl,
                    headers = mapOf(
                        "Referer" to "$base/",
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
