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

    // Real modern mobile/desktop browser user-agent to bypass basic bot detection
    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    private fun getBrowserConnection(url: String, refUrl: String = mainUrl): org.jsoup.Connection {
        return Jsoup.connect(url)
            .userAgent(userAgent)
            .referrer(refUrl)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Accept-Encoding", "gzip, deflate, br")
            .header("Connection", "keep-alive")
            .header("Upgrade-Insecure-Requests", "1")
            .header("Sec-Ch-Ua", "\"Chromium\";v=\"122\", \"Not(A:Brand\";v=\"24\", \"Google Chrome\";v=\"122\"")
            .header("Sec-Ch-Ua-Mobile", "?0")
            .header("Sec-Ch-Ua-Platform", "\"Windows\"")
            .header("Sec-Fetch-Dest", "document")
            .header("Sec-Fetch-Mode", "navigate")
            .header("Sec-Fetch-Site", "same-origin")
            .header("Sec-Fetch-User", "?1")
            .timeout(20000)
            .followRedirects(true)
    }

    override suspend fun getMainPage(page: Int): List<MovieItem> {
        val list = mutableListOf<MovieItem>()
        try {
            val targetUrl = if (page <= 1) {
                mainUrl
            } else {
                "$mainUrl/page/$page/"
            }

            val doc = getBrowserConnection(targetUrl).get()
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

            val doc = getBrowserConnection(searchUrl, mainUrl).get()
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
            // Mimic real browser visit to the movie post page with cookies/session handling
            val res = getBrowserConnection(postUrl, mainUrl).execute()
            val doc = res.parse()
            val cookies = res.cookies()

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
            // Step 1: Hit savelinks.me with browser-like headers & referrer
            val saveLinksRes = getBrowserConnection(generateUrl, mainUrl).execute()
            val saveLinksDoc = saveLinksRes.parse()
            val cookies = saveLinksRes.cookies()

            val multiCloudLink = saveLinksDoc.selectFirst("a[href*='multicloudlinks.com']")?.attr("href")?.trim() 
                ?: saveLinksDoc.selectFirst("a.break-words")?.attr("href")?.trim() 
                ?: return null

            // Step 2: Hit multi-cloud links redirect page holding cookies and referrer
            val multiCloudRes = getBrowserConnection(multiCloudLink, generateUrl)
                .cookies(cookies)
                .execute()
            val multiCloudDoc = multiCloudRes.parse()
            val multiCookies = multiCloudRes.cookies()

            // Merge cookies if needed
            val combinedCookies = mutableMapOf<String, String>().apply {
                putAll(cookies)
                putAll(multiCookies)
            }

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
