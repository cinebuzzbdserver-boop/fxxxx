package com.flixora.providers

import com.flixora.plugin.EpisodeItem
import com.flixora.plugin.MainAPI
import com.flixora.plugin.MediaStructure
import com.flixora.plugin.MovieItem
import com.flixora.plugin.SeasonItem
import com.flixora.plugin.StreamItem
import org.jsoup.Jsoup
import java.net.URLEncoder

class MLSBDProvider : MainAPI() {
    override var name = "MLSBD"
    override var mainUrl = "https://mlsbd.co"

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

            val posts = doc.select("div.single-post")

            for (post in posts) {
                val aTag = post.selectFirst("div.thumb a") ?: post.selectFirst("div.post-desc a")
                val url = aTag?.attr("href")?.trim() ?: ""
                
                val imgElement = post.selectFirst("picture img") ?: post.selectFirst("img")
                val image = imgElement?.attr("src")?.trim() 
                    ?: imgElement?.attr("data-src")?.trim() ?: ""

                val titleElement = post.selectFirst("h2.post-title")
                val title = titleElement?.text()?.trim() 
                    ?: aTag?.attr("title")?.trim() ?: ""

                if (title.isNotEmpty() && url.isNotEmpty()) {
                    list.add(
                        MovieItem(
                            title = title,
                            image = image,
                            url = url,
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
            val searchUrl = if (page <= 1) {
                "$mainUrl/?s=$encodedQuery"
            } else {
                "$mainUrl/?s=$encodedQuery&paged=$page"
            }

            val doc = Jsoup.connect(searchUrl)
                .userAgent(userAgent)
                .referrer(mainUrl)
                .timeout(15000)
                .get()

            val posts = doc.select("div.single-post")

            for (post in posts) {
                val aTag = post.selectFirst("div.thumb a") ?: post.selectFirst("div.post-desc a")
                val url = aTag?.attr("href")?.trim() ?: ""
                
                val imgElement = post.selectFirst("picture img") ?: post.selectFirst("img")
                val image = imgElement?.attr("src")?.trim() 
                    ?: imgElement?.attr("data-src")?.trim() ?: ""

                val titleElement = post.selectFirst("h2.post-title")
                val title = titleElement?.text()?.trim() 
                    ?: aTag?.attr("title")?.trim() ?: ""

                if (title.isNotEmpty() && url.isNotEmpty()) {
                    list.add(
                        MovieItem(
                            title = title,
                            image = image,
                            url = url,
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
                .timeout(15000)
                .get()

            // Selecting download and watch buttons pointing to savelinks.me
            val linkElements = doc.select("a.Dbtn, a[href*='savelinks.me']")

            for (link in linkElements) {
                val href = link.attr("href").trim()
                if (href.isNotEmpty()) {
                    var qualityText = link.selectFirst("span")?.text()?.trim() ?: link.text().trim()
                    if (qualityText.isEmpty()) {
                        qualityText = "Download Link"
                    }
                    rawMovieLinks.add(Pair(qualityText, href))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return MediaStructure(isSeries = false, rawMovieLinks = rawMovieLinks, seasons = emptyList())
    }

    override suspend fun resolveDirectLink(generateUrl: String): String? {
        try {
            // Step 1: Connect to savelinks.me link to extract multicloudlinks redirect
            val saveLinksDoc = Jsoup.connect(generateUrl)
                .userAgent(userAgent)
                .referrer(mainUrl)
                .timeout(15000)
                .get()

            val multiCloudLink = saveLinksDoc.selectFirst("a[href*='multicloudlinks.com']")?.attr("href")?.trim() 
                ?: saveLinksDoc.selectFirst("a.break-words")?.attr("href")?.trim() 
                ?: return null

            // Step 2: Follow multicloudlinks to get the final turbo download link (.website/d/...)
            val multiCloudDoc = Jsoup.connect(multiCloudLink)
                .userAgent(userAgent)
                .referrer(generateUrl)
                .timeout(15000)
                .followRedirects(true)
                .get()

            val turboBtn = multiCloudDoc.selectFirst("a.premium-btn, a[href*='multidownload.website']")
            val directStreamUrl = turboBtn?.attr("href")?.trim() ?: ""

            if (directStreamUrl.isNotEmpty()) {
                return if (directStreamUrl.startsWith("http")) directStreamUrl else "https:$directStreamUrl"
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }
}
