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

    private val userAgent = "Mozilla/5.0 (Linux; Android 16; 23090RA98I Build/BP2A.250605.031.A3) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.7871.183 Mobile Safari/537.36"

    private fun getClient(url: String, refUrl: String = mainUrl): org.jsoup.Connection {
        return Jsoup.connect(url)
            .userAgent(userAgent)
            .referrer(refUrl)
            .header("Host", "mlsbd.co")
            .header("accept", "text/plain, */*; q=0.01")
            .header("accept-language", "en-IN,en-US;q=0.9,en;q=0.8")
            .header("accept-encoding", "identity")
            .header("connection", "keep-alive")
            .header("x-requested-with", "XMLHttpRequest")
            .header("sec-ch-ua", "\"Not;A=Brand\";v=\"8\", \"Chromium\";v=\"150\", \"Android WebView\";v=\"150\"")
            .header("sec-ch-ua-mobile", "?1")
            .header("sec-ch-ua-platform", "\"Android\"")
            .header("sec-fetch-dest", "empty")
            .header("sec-fetch-mode", "cors")
            .header("sec-fetch-site", "same-origin")
            .header("priority", "u=1, i")
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

            val doc = getClient(targetUrl, mainUrl).get()
            val posts = doc.select("div.single-post")

            for (post in posts) {
                val aTag = post.selectFirst("div.thumb a") ?: post.selectFirst("div.post-desc a")
                val url = aTag?.attr("href")?.trim() ?: ""
                
                // Exactly grabbing image from picture/img structure safely
                var image = post.selectFirst("picture img")?.attr("src")?.trim() ?: ""
                if (image.isEmpty()) {
                    image = post.selectFirst("picture source[type='image/jpeg']")?.attr("srcset")?.trim() ?: ""
                }
                if (image.isEmpty()) {
                    image = post.selectFirst("picture source")?.attr("srcset")?.trim() ?: ""
                }

                val titleElement = post.selectFirst("h2.post-title")
                val title = titleElement?.text()?.trim() 
                    ?: aTag?.attr("title")?.trim() ?: ""

                if (title.isNotEmpty() && url.isNotEmpty()) {
                    list.add(
                        MovieItem(
                            title = title,
                            image = image,
                            url = url,
                            imageSize = "2:3"
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

            val refUrl = if (page <= 1) "$mainUrl/?s=$encodedQuery" else "$mainUrl/?s=$encodedQuery&paged=${page - 1}"
            val doc = getClient(searchUrl, refUrl).get()
            val posts = doc.select("div.single-post")

            for (post in posts) {
                val aTag = post.selectFirst("div.thumb a") ?: post.selectFirst("div.post-desc a")
                val url = aTag?.attr("href")?.trim() ?: ""
                
                var image = post.selectFirst("picture img")?.attr("src")?.trim() ?: ""
                if (image.isEmpty()) {
                    image = post.selectFirst("picture source[type='image/jpeg']")?.attr("srcset")?.trim() ?: ""
                }
                if (image.isEmpty()) {
                    image = post.selectFirst("picture source")?.attr("srcset")?.trim() ?: ""
                }

                val titleElement = post.selectFirst("h2.post-title")
                val title = titleElement?.text()?.trim() 
                    ?: aTag?.attr("title")?.trim() ?: ""

                if (title.isNotEmpty() && url.isNotEmpty()) {
                    list.add(
                        MovieItem(
                            title = title,
                            image = image,
                            url = url,
                            imageSize = "2:3"
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
            val doc = getClient(postUrl, mainUrl).get()
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
            val saveLinksRes = getClient(generateUrl, mainUrl).execute()
            val saveLinksDoc = saveLinksRes.parse()
            val cookies = saveLinksRes.cookies()

            val multiCloudLink = saveLinksDoc.selectFirst("a[href*='multicloudlinks.com']")?.attr("href")?.trim() 
                ?: saveLinksDoc.selectFirst("a.break-words")?.attr("href")?.trim() 
                ?: return null

            val multiCloudRes = Jsoup.connect(multiCloudLink)
                .userAgent(userAgent)
                .referrer(generateUrl)
                .cookies(cookies)
                .header("Host", "new.multicloudlinks.com")
                .header("accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("x-requested-with", "XMLHttpRequest")
                .timeout(15000)
                .followRedirects(true)
                .execute()

            val multiCloudDoc = multiCloudRes.parse()
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
