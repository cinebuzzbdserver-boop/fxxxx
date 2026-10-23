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

class MMSVibexProvider : MainAPI {
    override var name = "MMSVibex"
    override var mainUrl = "https://mmsvibex.fit"

    private val userAgent = "Mozilla/5.0 (Linux; Android 16; 23090RA98I Build/BP2A.250605.031.A3) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/153.0.8010.57 Mobile Safari/537.36"

    private suspend fun getBrowserDoc(targetUrl: String): Document = withContext(Dispatchers.IO) {
        Jsoup.connect(targetUrl)
            .userAgent(userAgent)
            .referrer("$mainUrl/")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .ignoreContentType(true)
            .ignoreHttpErrors(true)
            .timeout(20000)
            .get()
    }

    override suspend fun getMainPage(page: Int): List<MovieItem> {
        val list = mutableListOf<MovieItem>()
        try {
            val targetUrl = if (page <= 1) "$mainUrl/" else "$mainUrl/page/$page/"
            val doc = getBrowserDoc(targetUrl)
            val articles = doc.select("article.thumb-block")

            for (article in articles) {
                val anchor = article.selectFirst("a") ?: continue
                val rawUrl = anchor.attr("href").trim()
                if (rawUrl.isEmpty()) continue

                val imgElement = article.selectFirst("img.video-main-thumb") ?: article.selectFirst("img")
                val image = imgElement?.attr("src")?.trim().orEmpty()

                val title = article.selectFirst(".entry-header .title")?.text()?.trim()
                    ?: anchor.attr("title").trim().ifEmpty { null }
                    ?: imgElement?.attr("alt")?.trim()
                    ?: "Unknown Title"

                val fullUrl = if (rawUrl.startsWith("http")) rawUrl else "$mainUrl$rawUrl"

                list.add(
                    MovieItem(
                        title = title,
                        image = image,
                        url = fullUrl,
                        isPoster = false, // 16:9 ব্যানার থাম্বনেইল
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
            val targetUrl = if (page <= 1) {
                "$mainUrl/?s=$encodedQuery"
            } else {
                "$mainUrl/page/$page/?s=$encodedQuery"
            }

            val doc = getBrowserDoc(targetUrl)
            val articles = doc.select("article.thumb-block")

            for (article in articles) {
                val anchor = article.selectFirst("a") ?: continue
                val rawUrl = anchor.attr("href").trim()
                if (rawUrl.isEmpty()) continue

                val imgElement = article.selectFirst("img.video-main-thumb") ?: article.selectFirst("img")
                val image = imgElement?.attr("src")?.trim().orEmpty()

                val title = article.selectFirst(".entry-header .title")?.text()?.trim()
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
            val doc = getBrowserDoc(generateUrl)
            
            // ১. HTML5 <video><source src="..."> ট্যাগ চেক
            var directStreamUrl = doc.selectFirst("video#wpst-video source, video source")?.attr("src")?.trim().orEmpty()

            // ২. যদি সোর্স ট্যাগ না পায়, তাহলে mvp-player এর ডিরেক্ট বাটন চেক
            if (directStreamUrl.isEmpty()) {
                directStreamUrl = doc.selectFirst("a.mvp-button.mvp-center")?.attr("href")?.trim().orEmpty()
            }

            // ৩. ব্যাকআপ: পেজের স্ক্রিপ্ট বা সোর্স থেকে .mp4 ইউআরএল Regex দিয়ে এক্সট্র্যাক্ট করা
            if (directStreamUrl.isEmpty()) {
                val mp4Regex = Regex("""https?://[^\s"'<>]+\.mp4(?:\?[^\s"'<>]*)?""")
                directStreamUrl = mp4Regex.find(doc.html())?.value?.trim().orEmpty()
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
