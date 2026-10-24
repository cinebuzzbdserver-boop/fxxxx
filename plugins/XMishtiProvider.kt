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

    private suspend fun getBrowserDoc(targetUrl: String): Document = withContext(Dispatchers.IO) {
        Jsoup.connect(targetUrl)
            .userAgent(userAgent)
            .referrer("$mainUrl/")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .header("X-Requested-With", "XMLHttpRequest")
            .ignoreContentType(true)
            .ignoreHttpErrors(true)
            .timeout(20000)
            .get()
    }

    override suspend fun getMainPage(page: Int): List<MovieItem> {
        val list = mutableListOf<MovieItem>()
        try {
            val targetUrl = "$mainUrl/?mode=async&function=get_block&block_id=list_videos_most_recent_videos&sort_by=post_date&from=$page"
            val doc = getBrowserDoc(targetUrl)
            val items = doc.select(".thumb.item, .thumb_rel.item")

            for (item in items) {
                if (item.selectFirst("ins, .qualtiy:contains(AD), .title:contains(KamaReels)") != null) {
                    continue
                }

                val anchor = item.selectFirst("a") ?: continue
                val rawUrl = anchor.attr("href").trim()
                if (rawUrl.isEmpty()) continue

                val imgElement = item.selectFirst("img")
                var image = imgElement?.attr("data-webp")?.trim().orEmpty()
                if (image.isEmpty()) {
                    image = imgElement?.attr("src")?.trim().orEmpty()
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

    override suspend fun search(query: String, page: Int): List<MovieItem> {
        val list = mutableListOf<MovieItem>()
        try {
            val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8")
            val targetUrl = "$mainUrl/search/$encodedQuery/?mode=async&function=get_block&block_id=list_videos_videos_list_search_result&q=$encodedQuery&category_ids=&sort_by=&from_videos=$page"
            
            val doc = getBrowserDoc(targetUrl)
            val items = doc.select(".thumb.item, .thumb_rel.item")

            for (item in items) {
                if (item.selectFirst("ins, .qualtiy:contains(AD), .title:contains(KamaReels)") != null) {
                    continue
                }

                val anchor = item.selectFirst("a") ?: continue
                val rawUrl = anchor.attr("href").trim()
                if (rawUrl.isEmpty()) continue

                val imgElement = item.selectFirst("img")
                var image = imgElement?.attr("data-webp")?.trim().orEmpty()
                if (image.isEmpty()) {
                    image = imgElement?.attr("src")?.trim().orEmpty()
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
            val html = doc.html()

            var directStreamUrl = ""

            val pathRegex = Regex("""get_file/\d+/[a-f0-9]+/(\d+/\d+/\d+\.mp4)""")
            val match = pathRegex.find(html)

            if (match != null) {
                val relativePath = match.groupValues[1]
                directStreamUrl = "$cdnUrl/$relativePath"
            }

            if (directStreamUrl.isEmpty()) {
                val fullUrlRegex = Regex("""event_reporting2:\s*['"](https?://[^'"]+?/get_file/\d+/[a-f0-9]+/(\d+/\d+/\d+\.mp4)[^'"]*?)['"]""")
                val fullMatch = fullUrlRegex.find(html)
                if (fullMatch != null) {
                    val relativePath = fullMatch.groupValues[2]
                    directStreamUrl = "$cdnUrl/$relativePath"
                }
            }

            if (directStreamUrl.isEmpty()) {
                val directVideoSrc = doc.selectFirst("video source, video")?.attr("src")?.trim().orEmpty()
                if (directVideoSrc.isNotEmpty()) {
                    directStreamUrl = if (directVideoSrc.startsWith("http")) directVideoSrc else "$mainUrl$directVideoSrc"
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
