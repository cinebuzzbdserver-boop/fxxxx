package com.flixora.providers

import com.flixora.plugin.EpisodeItem
import com.flixora.plugin.MainAPI
import com.flixora.plugin.MediaStructure
import com.flixora.plugin.MovieItem
import com.flixora.plugin.SeasonItem
import com.flixora.plugin.StreamItem
import org.jsoup.Jsoup
import java.net.URLEncoder

class FibWatchProvider : MainAPI() {
    override var name = "FibWatch"
    override var mainUrl = "https://fibwatch.art"

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    override suspend fun getMainPage(page: Int): List<MovieItem> {
        val list = mutableListOf<MovieItem>()
        try {
            val targetUrl = if (page <= 1) {
                "$mainUrl/videos/latest"
            } else {
                "$mainUrl/videos/latest?page_id=$page"
            }

            val doc = Jsoup.connect(targetUrl)
                .userAgent(userAgent)
                .referrer(mainUrl)
                .timeout(15000)
                .get()

            val cards = doc.select(".video-latest-list")

            for (card in cards) {
                val thumbAnchor = card.selectFirst(".video-thumb a")
                val titleAnchor = card.selectFirst(".video-title a")

                val rawUrl = thumbAnchor?.attr("href")?.trim() ?: titleAnchor?.attr("href")?.trim() ?: ""
                val imgElement = card.selectFirst(".video-thumb img")
                val image = imgElement?.attr("src")?.trim() ?: ""
                val title = card.selectFirst(".video-title p.hptag")?.text()?.trim() 
                    ?: titleAnchor?.text()?.trim() 
                    ?: ""

                if (title.isNotEmpty() && rawUrl.isNotEmpty()) {
                    val fullUrl = if (rawUrl.startsWith("http")) rawUrl else "$mainUrl$rawUrl"
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
        return search(query, 1)
    }

    override suspend fun search(query: String, page: Int): List<MovieItem> {
        val list = mutableListOf<MovieItem>()
        try {
            val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8")
            val targetUrl = if (page <= 1) {
                "$mainUrl/search?keyword=$encodedQuery"
            } else {
                "$mainUrl/search?keyword=$encodedQuery&page_id=$page"
            }

            val doc = Jsoup.connect(targetUrl)
                .userAgent(userAgent)
                .referrer(mainUrl)
                .timeout(15000)
                .get()

            val cards = doc.select(".video-latest-list")

            for (card in cards) {
                val thumbAnchor = card.selectFirst(".video-thumb a")
                val titleAnchor = card.selectFirst(".video-title a")

                val rawUrl = thumbAnchor?.attr("href")?.trim() ?: titleAnchor?.attr("href")?.trim() ?: ""
                val imgElement = card.selectFirst(".video-thumb img")
                val image = imgElement?.attr("src")?.trim() ?: ""
                val title = card.selectFirst(".video-title p.hptag")?.text()?.trim() 
                    ?: titleAnchor?.text()?.trim() 
                    ?: ""

                if (title.isNotEmpty() && rawUrl.isNotEmpty()) {
                    val fullUrl = if (rawUrl.startsWith("http")) rawUrl else "$mainUrl$rawUrl"
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
        try {
            val doc = Jsoup.connect(postUrl)
                .userAgent(userAgent)
                .referrer(mainUrl)
                .timeout(15000)
                .get()

            // Video ID নির্ধারণ (data-vid অথবা resolution button থেকে)
            val videoId = doc.selectFirst("#see-more-res")?.attr("data-vid")?.trim()
                ?: doc.selectFirst("[data-vid]")?.attr("data-vid")?.trim()
                ?: ""

            // পেজের ডিফল্ট সিলেক্টেড কোয়ালিটি
            val currentRes = doc.selectFirst(".available-res .res-btn.selected")?.text()?.trim()?.ifEmpty { null } ?: "Default"

            // ১. এপিসোড রিকোয়েস্ট (Series Check)
            if (videoId.isNotEmpty()) {
                val epApiUrl = "$mainUrl/ajax/episodes.php?video_id=$videoId"
                val epResponse = Jsoup.connect(epApiUrl)
                    .userAgent(userAgent)
                    .referrer(postUrl)
                    .ignoreContentType(true)
                    .timeout(15000)
                    .execute()

                val epJson = epResponse.body().trim()
                val epRegex = Regex("""\{"display":"(.*?)","url":"(.*?)","is_current":(true|false)\}""")
                val epMatches = epRegex.findAll(epJson).toList()

                if (epMatches.isNotEmpty()) {
                    val seasonMap = mutableMapOf<String, MutableList<EpisodeItem>>()

                    for (match in epMatches) {
                        val display = match.groupValues[1].replace("\\/", "/").trim()
                        val relUrl = match.groupValues[2].replace("\\/", "/").trim()

                        if (relUrl.isNotEmpty()) {
                            val epFullUrl = if (relUrl.startsWith("http")) relUrl else "$mainUrl$relUrl"
                            
                            // Season ডিটেকশন (e.g., S05E01 -> Season 5 অথবা ডিফল্ট Season 1)
                            val seasonMatch = Regex("""S(\d+)""", RegexOption.IGNORE_CASE).find(display)
                            val seasonName = if (seasonMatch != null) {
                                "Season " + seasonMatch.groupValues[1].toInt()
                            } else {
                                "Season 1"
                            }

                            val rawLinks = mutableListOf(Pair(currentRes, epFullUrl))
                            val epList = seasonMap.getOrPut(seasonName) { mutableListOf() }
                            epList.add(EpisodeItem(name = display, rawGenerateLinks = rawLinks))
                        }
                    }

                    val seasonsList = seasonMap.map { (sName, epList) -> SeasonItem(name = sName, episodes = epList) }
                    if (seasonsList.isNotEmpty()) {
                        return MediaStructure(isSeries = true, rawMovieLinks = emptyList(), seasons = seasonsList)
                    }
                }
            }

            // ২. মুভি কোয়ালিটি (isSeries = false)
            val rawMovieLinks = mutableListOf<Pair<String, String>>()
            rawMovieLinks.add(Pair(currentRes, postUrl))

            if (videoId.isNotEmpty()) {
                val resApiUrl = "$mainUrl/ajax/resolution_switcher.php?video_id=$videoId"
                val resResponse = Jsoup.connect(resApiUrl)
                    .userAgent(userAgent)
                    .referrer(postUrl)
                    .ignoreContentType(true)
                    .timeout(15000)
                    .execute()

                val resJson = resResponse.body().trim()
                val popupBlock = resJson.substringAfter("\"popup\":", "").substringBefore("]")
                val resRegex = Regex("""\{"res":"(.*?)","url":"(.*?)","selected":(true|false)\}""")
                val resMatches = resRegex.findAll(popupBlock)

                for (match in resMatches) {
                    val res = match.groupValues[1].trim()
                    val relUrl = match.groupValues[2].replace("\\/", "/").trim()
                    val isSelected = match.groupValues[3].toBoolean()

                    // কারেন্ট/সিলেক্টেড কোয়ালিটি বাদে বাকি কোয়ালিটি অ্যাড করা
                    if (!isSelected && relUrl.isNotEmpty() && !res.equals("See More", ignoreCase = true)) {
                        val fullUrl = if (relUrl.startsWith("http")) relUrl else "$mainUrl$relUrl"
                        rawMovieLinks.add(Pair(res, fullUrl))
                    }
                }
            }

            return MediaStructure(isSeries = false, rawMovieLinks = rawMovieLinks, seasons = emptyList())
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return MediaStructure(isSeries = false, rawMovieLinks = listOf(Pair("Default", postUrl)), seasons = emptyList())
    }

    override suspend fun resolveDirectLink(generateUrl: String): String? {
        try {
            val doc = Jsoup.connect(generateUrl)
                .userAgent(userAgent)
                .referrer(mainUrl)
                .timeout(15000)
                .get()

            val htmlContent = doc.html()

            // Script ব্লকের ভেতরের VIDEO_URL ভ্যালু পার্সিং
            val videoUrlRegex = Regex("""var\s+VIDEO_URL\s*=\s*["']([^"']+)["']""")
            val match = videoUrlRegex.find(htmlContent)
            val directStreamUrl = match?.groupValues?.get(1)?.trim() ?: ""

            if (directStreamUrl.isNotEmpty()) {
                return directStreamUrl.replace("\\/", "/")
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }
}
