package com.flixora.providers

import com.flixora.plugin.EpisodeItem
import com.flixora.plugin.MainAPI
import com.flixora.plugin.MediaStructure
import com.flixora.plugin.MovieItem
import com.flixora.plugin.SeasonItem
import com.flixora.plugin.StreamItem
import org.jsoup.Jsoup

class MovieLinkBDProvider : MainAPI() {
    override var name = "MovieLinkBD"
    override var mainUrl = "https://wuqns4.movielinkbd.li"

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

            val cards = doc.select(".movie-card")

            for (card in cards) {
                val anchor = card.selectFirst("a[data-mlbd-click-ad='movie-card'], .image-container a, a.title")
                val relUrl = anchor?.attr("href")?.trim() ?: ""
                val fullUrl = if (relUrl.startsWith("http")) relUrl else "$mainUrl$relUrl"

                val imgElement = card.selectFirst(".image-container img")
                var image = imgElement?.attr("data-src")?.trim() ?: ""
                if (image.isEmpty() || image.contains("mlbd_load.svg")) {
                    image = imgElement?.attr("src")?.trim() ?: ""
                }

                val titleElement = card.selectFirst("a.title") ?: anchor
                val title = titleElement?.text()?.trim() ?: imgElement?.attr("alt")?.trim() ?: ""

                if (title.isNotEmpty() && relUrl.isNotEmpty()) {
                    list.add(
                        MovieItem(
                            title = title,
                            image = image,
                            url = fullUrl,
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
        val list = mutableListOf<MovieItem>()
        try {
            val searchUrl = "$mainUrl/?s=${query.trim().replace(" ", "+")}"
            val doc = Jsoup.connect(searchUrl)
                .userAgent(userAgent)
                .timeout(15000)
                .get()

            val cards = doc.select(".movie-card")
            for (card in cards) {
                val anchor = card.selectFirst("a[data-mlbd-click-ad='movie-card'], .image-container a, a.title")
                val relUrl = anchor?.attr("href")?.trim() ?: ""
                val fullUrl = if (relUrl.startsWith("http")) relUrl else "$mainUrl$relUrl"

                val imgElement = card.selectFirst(".image-container img")
                var image = imgElement?.attr("data-src")?.trim() ?: ""
                if (image.isEmpty() || image.contains("mlbd_load.svg")) {
                    image = imgElement?.attr("src")?.trim() ?: ""
                }

                val titleElement = card.selectFirst("a.title") ?: anchor
                val title = titleElement?.text()?.trim() ?: imgElement?.attr("alt")?.trim() ?: ""

                if (title.isNotEmpty() && relUrl.isNotEmpty()) {
                    list.add(
                        MovieItem(
                            title = title,
                            image = image,
                            url = fullUrl,
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

            val epCards = doc.select(".ep-card")

            // ১. সিরিজ / নাটক পার্সিং (ep-card উপস্থিত থাকলে)
            if (epCards.isNotEmpty()) {
                val seasonMap = mutableMapOf<String, MutableList<EpisodeItem>>()

                for (card in epCards) {
                    val seasonNum = card.attr("data-season-number").trim()
                    val epTitle = card.selectFirst(".mlbd-episode-title")?.text()?.trim() 
                        ?: card.attr("data-ep").trim().ifEmpty { "Episode" }

                    val seasonName = if (seasonNum.isNotEmpty()) {
                        "Season $seasonNum"
                    } else {
                        val seasonRegex = Regex("""S(\d+)""", RegexOption.IGNORE_CASE)
                        val match = seasonRegex.find(epTitle)
                        if (match != null) "Season ${match.groupValues[1]}" else "Season 1"
                    }

                    val downloadButtons = card.select("a[href*='/file/']")
                    val rawLinks = mutableListOf<Pair<String, String>>()

                    for (btn in downloadButtons) {
                        val rawQuality = btn.text().trim()
                        val quality = rawQuality
                            .replace(Regex("""(?i)download|\s+"""), " ")
                            .replace(Regex("""[\[\]]"""), "")
                            .trim()
                            .ifEmpty { "Default Quality" }

                        val relPath = btn.attr("href").trim()
                        if (relPath.isNotEmpty()) {
                            val fullUrl = if (relPath.startsWith("http")) relPath else "$mainUrl$relPath"
                            rawLinks.add(Pair(quality, fullUrl))
                        }
                    }

                    if (rawLinks.isNotEmpty()) {
                        val epList = seasonMap.getOrPut(seasonName) { mutableListOf() }
                        epList.add(EpisodeItem(name = epTitle, rawGenerateLinks = rawLinks))
                    }
                }

                val seasonsList = seasonMap.map { (sName, epList) -> SeasonItem(name = sName, episodes = epList) }
                if (seasonsList.isNotEmpty()) {
                    return MediaStructure(isSeries = true, rawMovieLinks = emptyList(), seasons = seasonsList)
                }
            }

            // ২. মুভি পার্সিং (.mlbd-download-button-wrap অথবা পেজের সাধারণ /file/ লিংক)
            val rawMovieLinks = mutableListOf<Pair<String, String>>()
            val movieDownloadBtns = doc.select(".mlbd-download-button-wrap a[href*='/file/'], a.btn[href*='/file/']")

            for (btn in movieDownloadBtns) {
                val rawQuality = btn.text().trim()
                val quality = rawQuality
                    .replace(Regex("""(?i)download|\s+"""), " ")
                    .replace(Regex("""[\[\]]"""), "")
                    .trim()
                    .ifEmpty { "Download" }

                val relPath = btn.attr("href").trim()
                if (relPath.isNotEmpty()) {
                    val fullUrl = if (relPath.startsWith("http")) relPath else "$mainUrl$relPath"
                    rawMovieLinks.add(Pair(quality, fullUrl))
                }
            }

            return MediaStructure(isSeries = false, rawMovieLinks = rawMovieLinks, seasons = emptyList())
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return MediaStructure(isSeries = false, rawMovieLinks = emptyList(), seasons = emptyList())
    }

    override suspend fun resolveDirectLink(generateUrl: String): String? {
        try {
            // ১. /file/ পেজে রিকোয়েস্ট করে গন্তব্য অ্যাকশন পেজের লিংক বের করা
            val fileDoc = Jsoup.connect(generateUrl)
                .userAgent(userAgent)
                .referrer(mainUrl)
                .timeout(15000)
                .get()

            val primaryActionBtn = fileDoc.selectFirst("a.gdl-action-primary, a[data-host='oneclick'], a[href*='/file/']")
            val nextRelUrl = primaryActionBtn?.attr("href")?.trim() ?: ""

            val nextStepUrl = when {
                nextRelUrl.isEmpty() -> generateUrl
                nextRelUrl.startsWith("http") -> nextRelUrl
                else -> "$mainUrl$nextRelUrl"
            }

            // ২. অ্যাকশন পেজে রিকোয়েস্ট পাঠিয়ে ফাইনাল R2 স্ট্রিমিং/ডাউনলোড লিংক বের করা
            val actionDoc = if (nextStepUrl != generateUrl) {
                Jsoup.connect(nextStepUrl)
                    .userAgent(userAgent)
                    .referrer(generateUrl)
                    .timeout(15000)
                    .get()
            } else {
                fileDoc
            }

            val finalDownloadBtn = actionDoc.selectFirst("a.btn-cloud, a[data-mlbd-click-ad='file-final-download'], a[href*='r2.dev']")
            val directStreamUrl = finalDownloadBtn?.attr("href")?.trim() ?: ""

            if (directStreamUrl.isNotEmpty()) {
                return directStreamUrl
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }
}
