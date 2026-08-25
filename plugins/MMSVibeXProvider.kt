package com.flixora.providers

import com.flixora.plugin.EpisodeItem
import com.flixora.plugin.MainAPI
import com.flixora.plugin.MediaStructure
import com.flixora.plugin.MovieItem
import com.flixora.plugin.SeasonItem
import com.flixora.plugin.StreamItem
import org.jsoup.Jsoup

class MMSVibeXProvider : MainAPI() {
    override var name = "MMSVibeX"
    override var mainUrl = "https://mmsvibex.net"

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    override suspend fun getMainPage(page: Int): List<MovieItem> {
        val list = mutableListOf<MovieItem>()
        try {
            val targetUrl = if (page <= 1) {
                mainUrl
            } else {
                "$mainUrl/page/$page/"
            }

            val doc = Jsoup.connect(targetUrl)
                .userAgent(userAgent)
                .timeout(15000)
                .get()

            val articles = doc.select("article.thumb-block")

            for (article in articles) {
                val anchor = article.selectFirst("a[href]")
                val rawUrl = anchor?.attr("href")?.trim() ?: ""
                val fullUrl = if (rawUrl.startsWith("http")) rawUrl else "$mainUrl$rawUrl"

                var image = article.attr("data-main-thumb").trim()
                if (image.isEmpty()) {
                    val imgElement = article.selectFirst(".post-thumbnail-container img, img.video-main-thumb")
                    image = imgElement?.attr("src")?.trim() ?: ""
                }

                val title = article.selectFirst(".entry-header .title")?.text()?.trim()
                    ?: anchor?.attr("title")?.trim()
                    ?: ""

                if (title.isNotEmpty() && rawUrl.isNotEmpty()) {
                    list.add(
                        MovieItem(
                            title = title,
                            image = image,
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
        val list = mutableListOf<MovieItem>()
        try {
            val searchUrl = "$mainUrl/?s=${query.trim().replace(" ", "+")}"
            val doc = Jsoup.connect(searchUrl)
                .userAgent(userAgent)
                .timeout(15000)
                .get()

            val articles = doc.select("article.thumb-block")
            for (article in articles) {
                val anchor = article.selectFirst("a[href]")
                val rawUrl = anchor?.attr("href")?.trim() ?: ""
                val fullUrl = if (rawUrl.startsWith("http")) rawUrl else "$mainUrl$rawUrl"

                var image = article.attr("data-main-thumb").trim()
                if (image.isEmpty()) {
                    val imgElement = article.selectFirst(".post-thumbnail-container img, img.video-main-thumb")
                    image = imgElement?.attr("src")?.trim() ?: ""
                }

                val title = article.selectFirst(".entry-header .title")?.text()?.trim()
                    ?: anchor?.attr("title")?.trim()
                    ?: ""

                if (title.isNotEmpty() && rawUrl.isNotEmpty()) {
                    list.add(
                        MovieItem(
                            title = title,
                            image = image,
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
        val rawMovieLinks = mutableListOf<Pair<String, String>>()
        try {
            val doc = Jsoup.connect(postUrl)
                .userAgent(userAgent)
                .referrer(mainUrl)
                .timeout(15000)
                .get()

            // ১. HTML5 <video><source src="..."> থেকে সরাসরি ভিডিও লিংক এক্সট্র্যাক্ট করা
            val videoSource = doc.selectFirst(".video-player video source, video#wpst-video source")
            var videoUrl = videoSource?.attr("src")?.trim() ?: ""

            // ২. Fallback: যদি source এ না পাওয়া যায় তবে meta ট্যাগ থেকে রিট্রিভ করা
            if (videoUrl.isEmpty()) {
                val metaContent = doc.selectFirst("meta[itemprop='contentURL']")
                videoUrl = metaContent?.attr("content")?.trim() ?: ""
            }

            if (videoUrl.isNotEmpty()) {
                val finalStreamUrl = if (videoUrl.startsWith("http")) videoUrl else "$mainUrl$videoUrl"
                rawMovieLinks.add(Pair("Direct Stream", finalStreamUrl))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return MediaStructure(
            isSeries = false,
            rawMovieLinks = rawMovieLinks,
            seasons = emptyList()
        )
    }

    override suspend fun resolveDirectLink(generateUrl: String): String? {
        return if (generateUrl.isNotEmpty()) generateUrl else null
    }
}
