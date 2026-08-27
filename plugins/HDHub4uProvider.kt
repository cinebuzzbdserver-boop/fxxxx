package com.flixora.providers

import com.flixora.plugin.EpisodeItem
import com.flixora.plugin.MainAPI
import com.flixora.plugin.MediaStructure
import com.flixora.plugin.MovieItem
import com.flixora.plugin.SeasonItem
import com.flixora.plugin.StreamItem
import org.jsoup.Jsoup
import java.net.URLEncoder

class HDHub4uProvider : MainAPI() {
    override var name = "HDHub4u"
    override var mainUrl = "https://new5.hdhub4u.cl"

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

            val thumbElements = doc.select("li.thumb")

            for (thumb in thumbElements) {
                val aTag = thumb.selectFirst("figure > a") ?: thumb.selectFirst("figcaption > a")
                val url = aTag?.attr("href")?.trim() ?: ""
                val imgElement = thumb.selectFirst("figure > img")
                val image = imgElement?.attr("src")?.trim() ?: ""
                val title = imgElement?.attr("title")?.trim().takeIf { !it.isNullOrEmpty() } 
                    ?: thumb.selectFirst("figcaption p")?.text()?.trim() ?: ""

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
            val searchApiUrl = "https://search.pingora.fyi/collections/post/documents/search?q=$encodedQuery&query_by=post_title&limit=50&page=$page"

            val response = Jsoup.connect(searchApiUrl)
                .userAgent(userAgent)
                .referrer(mainUrl)
                .ignoreContentType(true)
                .timeout(15000)
                .execute()

            val rawJson = response.body().trim()

            // Pure Regex Parsing for Pingora Search Response Structure
            val hitRegex = Regex(""""permalink"\s*:\s*"(.*?)".*?"post_thumbnail"\s*:\s*"(.*?)".*?"post_title"\s*:\s*"(.*?)"""")
            val matches = hitRegex.findAll(rawJson)

            for (match in matches) {
                val url = match.groupValues[1].replace("\\/", "/").trim()
                val image = match.groupValues[2].replace("\\/", "/").trim()
                var title = match.groupValues[3].replace("\\/", "/").replace("\\\"", "\"").trim()

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
        try {
            val doc = Jsoup.connect(postUrl)
                .userAgent(userAgent)
                .timeout(15000)
                .get()

            val rawMovieLinks = mutableListOf<Pair<String, String>>()
            val linkElements = doc.select("a[href*='hubdrive.tips']")

            for (link in linkElements) {
                val href = link.attr("href").trim()
                if (href.isNotEmpty()) {
                    var qualityText = link.text().trim()
                    if (qualityText.isEmpty() || qualityText.equals("[SAMPLE]", ignoreCase = true)) {
                        qualityText = link.parent()?.text()?.trim() ?: "Download Link"
                    }
                    rawMovieLinks.add(Pair(qualityText, href))
                }
            }

            return MediaStructure(isSeries = false, rawMovieLinks = rawMovieLinks, seasons = emptyList())
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return MediaStructure(isSeries = false, rawMovieLinks = rawMovieLinks, seasons = emptyList())
    }

    override suspend fun resolveDirectLink(generateUrl: String): String? {
        try {
            if (generateUrl.contains("hubdrive.tips")) {
                // Extracting file ID from URL like https://hubdrive.tips/file/1907346668
                val fileIdRegex = Regex("""/file/([0-9]+)""")
                val match = fileIdRegex.find(generateUrl)
                val fileId = match?.groupValues?.get(1)?.trim() ?: return null

                val ajaxUrl = "https://hubdrive.tips/ajax.php?ajax=direct-download"
                
                // Executing POST request mimicking the exact browser/curl headers
                val response = Jsoup.connect(ajaxUrl)
                    .userAgent("Mozilla/5.0 (Linux; Android 16; 23090RA98I Build/BP2A.250605.031.A3) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.7871.183 Mobile Safari/537.36")
                    .referrer("https://hubdrive.tips/")
                    .header("origin", "https://hubdrive.tips")
                    .header("content-type", "application/x-www-form-urlencoded; charset=UTF-8")
                    .header("x-requested-with", "XMLHttpRequest")
                    .header("accept", "application/json, text/javascript, */*; q=0.01")
                    .requestBody("id=$fileId")
                    .ignoreContentType(true)
                    .method(org.jsoup.Connection.Method.POST)
                    .execute()

                val jsonBody = response.body()
                
                // Extracting "gd" key value from the JSON response safely using regex
                val gdRegex = Regex(""""gd"\s*:\s*"([^"]+)"""")
                val gdMatch = gdRegex.find(jsonBody)
                val directLink = gdMatch?.groupValues?.get(1)?.trim()

                if (!directLink.isNullOrEmpty()) {
                    return directLink
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }
}
