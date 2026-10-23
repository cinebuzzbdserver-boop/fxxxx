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
                // ১. ভিডিও কাউন্ট টেক্সট অথবা এলবাম/গ্রুপ ক্লাস থাকলে ইগনোর করবে
                if (article.selectFirst(".video-count-text") != null || 
                    article.hasClass("homepage-pinned-album") || 
                    article.selectFirst(".actor-title") != null) {
                    continue
                }

                val anchor = article.selectFirst("a") ?: continue
                val rawUrl = anchor.attr("href").trim()
                if (rawUrl.isEmpty()) continue

                // ২. actor / actors গ্রুপ রিলেটেড লিংক ইগনোর করবে
                if (rawUrl.contains("/actor/") || rawUrl.contains("?actors=")) {
                    continue
                }

                val imgElement = article.selectFirst("img.video-main-thumb") ?: article.selectFirst("img")
                var image = imgElement?.attr("data-src")?.trim().orEmpty()
                if (image.isEmpty() || image.contains("px.gif")) {
                    image = imgElement?.attr("src")?.trim().orEmpty()
                }

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
                // ১. ভিডিও কাউন্ট টেক্সট অথবা এলবাম/গ্রুপ ক্লাস থাকলে ইগনোর করবে
                if (article.selectFirst(".video-count-text") != null || 
                    article.hasClass("homepage-pinned-album") || 
                    article.selectFirst(".actor-title") != null) {
                    continue
                }

                val anchor = article.selectFirst("a") ?: continue
                val rawUrl = anchor.attr("href").trim()
                if (rawUrl.isEmpty()) continue

                // ২. actor / actors গ্রুপ রিলেটেড লিংক ইগনোর করবে
                if (rawUrl.contains("/actor/") || rawUrl.contains("?actors=")) {
                    continue
                }

                val imgElement = article.selectFirst("img.video-main-thumb") ?: article.selectFirst("img")
                var image = imgElement?.attr("data-src")?.trim().orEmpty()
                if (image.isEmpty() || image.contains("px.gif")) {
                    image = imgElement?.attr("src")?.trim().orEmpty()
                }

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
            
            var directStreamUrl = doc.selectFirst("video#wpst-video source, video source")?.attr("src")?.trim().orEmpty()

            if (directStreamUrl.isEmpty()) {
                directStreamUrl = doc.selectFirst("a.mvp-button.mvp-center")?.attr("href")?.trim().orEmpty()
            }

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
