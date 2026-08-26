package com.flixora.providers

import com.flixora.plugin.EpisodeItem
import com.flixora.plugin.MainAPI
import com.flixora.plugin.MediaStructure
import com.flixora.plugin.MovieItem
import com.flixora.plugin.SeasonItem
import com.flixora.plugin.StreamItem
import org.jsoup.Jsoup

class CineFreakProvider : MainAPI() {
    override var name = "CineFreak"
    override var mainUrl = "https://cinefreak.nl"

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

            val cards = doc.select("a.movie-card")

            for (card in cards) {
                val url = card.attr("href").trim()
                val imgElement = card.selectFirst(".movie-card-image img")
                val image = imgElement?.attr("src")?.trim() ?: ""
                val titleElement = card.selectFirst(".movie-card-content .movie-card-title")
                val title = titleElement?.text()?.trim() ?: ""

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
        val list = mutableListOf<MovieItem>()
        try {
            val searchUrl = "$mainUrl/?s=${query.trim().replace(" ", "+")}"
            val doc = Jsoup.connect(searchUrl)
                .userAgent(userAgent)
                .timeout(15000)
                .get()

            val cards = doc.select("a.movie-card")
            for (card in cards) {
                val url = card.attr("href").trim()
                val image = card.selectFirst(".movie-card-image img")?.attr("src")?.trim() ?: ""
                val title = card.selectFirst(".movie-card-content .movie-card-title")?.text()?.trim() ?: ""

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

            val epCards = doc.select(".episode-grid .ep-card")

            // ১. সিরিজ বা টিভি শো থাকলে শুধুমাত্র download-links নেওয়া হবে
            if (epCards.isNotEmpty() || doc.selectFirst(".season-number") != null) {
                val seasonMap = mutableMapOf<String, MutableList<EpisodeItem>>()

                // সিঙ্গেল এপিসোড পার্সিং
                for (card in epCards) {
                    val seasonName = card.selectFirst(".season-number")?.text()?.trim()?.ifEmpty { null } ?: "Season 1"
                    val epBadge = card.selectFirst(".episode-badge")?.text()?.trim() ?: ""
                    val epTitle = if (epBadge.isNotEmpty()) epBadge else (card.selectFirst(".ep-title")?.text()?.trim() ?: "Episode")

                    // শুধুমাত্র .quality-box.download-links টার্গেট
                    val downloadBox = card.selectFirst(".quality-box.download-links")
                    val links = downloadBox?.select(".quality-grid a") ?: emptyList()

                    val rawLinks = mutableListOf<Pair<String, String>>()
                    for (link in links) {
                        val quality = link.text().trim()
                        val relPath = link.attr("href").trim()
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

                // ফুল সিজন কম্বো পার্সিং (যদি সিঙ্গেল এপিসোড না থাকে)
                if (seasonMap.isEmpty()) {
                    val comboDl = doc.selectFirst(".quality-box.download-links")
                    val links = comboDl?.select(".quality-grid a") ?: emptyList()

                    val rawLinks = mutableListOf<Pair<String, String>>()
                    for (link in links) {
                        val quality = link.text().trim()
                        val relPath = link.attr("href").trim()
                        if (relPath.isNotEmpty()) {
                            val fullUrl = if (relPath.startsWith("http")) relPath else "$mainUrl$relPath"
                            rawLinks.add(Pair(quality, fullUrl))
                        }
                    }

                    if (rawLinks.isNotEmpty()) {
                        val seasonName = doc.selectFirst(".season-number")?.text()?.trim()?.ifEmpty { null } ?: "Season 1"
                        seasonMap[seasonName] = mutableListOf(EpisodeItem(name = "Full Season", rawGenerateLinks = rawLinks))
                    }
                }

                val seasonsList = seasonMap.map { (sName, epList) -> SeasonItem(name = sName, episodes = epList) }
                if (seasonsList.isNotEmpty()) {
                    return MediaStructure(isSeries = true, rawMovieLinks = emptyList(), seasons = seasonsList)
                }
            }

            // ২. সাধারণ মুভি স্ট্রাকচার পার্সিং
            val downloadDiv = doc.selectFirst(".download-links-div")
            val titles = downloadDiv?.select("h4.movie-title") ?: emptyList()
            val rawMovieLinks = mutableListOf<Pair<String, String>>()

            for (titleElement in titles) {
                val qualityText = titleElement.text().trim()
                var sibling = titleElement.nextElementSibling()
                while (sibling != null && !sibling.tagName().equals("h4", ignoreCase = true)) {
                    if (sibling.hasClass("dlbtn-container")) {
                        val downloadBtn = sibling.selectFirst("a.dlbtn-download")
                        val genUrl = downloadBtn?.attr("href")?.trim() ?: ""
                        if (genUrl.isNotEmpty()) {
                            val fullUrl = if (genUrl.startsWith("http")) genUrl else "$mainUrl$genUrl"
                            rawMovieLinks.add(Pair(qualityText, fullUrl))
                            break
                        }
                    }
                    sibling = sibling.nextElementSibling()
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
            // ১. Generate পেজ রিকোয়েস্ট করে window.location.href লিংক বের করা
            val genDoc = Jsoup.connect(generateUrl)
                .userAgent(userAgent)
                .referrer(mainUrl)
                .timeout(15000)
                .get()

            val htmlContent = genDoc.html()
            val locationRegex = Regex("""window\.location\.href\s*=\s*["']([^"']+)["']""")
            val match = locationRegex.find(htmlContent)
            val intermediateUrl = match?.groupValues?.get(1)?.trim() ?: return null

            // ২. /f/ অথবা /x/ পাথকে /d/ পাথে রূপান্তর
            val downloadPageUrl = when {
                intermediateUrl.contains("/f/") -> intermediateUrl.replace("/f/", "/d/")
                intermediateUrl.contains("/x/") -> intermediateUrl.replace("/x/", "/d/")
                else -> intermediateUrl
            }

            // ৩. /d/ পেজে রিকোয়েস্ট পাঠিয়ে Cloudflare R2 ডিরেক্ট স্ট্রিম লিংক বের করা
            val dlDoc = Jsoup.connect(downloadPageUrl)
                .userAgent(userAgent)
                .referrer(generateUrl)
                .timeout(15000)
                .get()

            val directLinkElement = dlDoc.selectFirst("a.download-now")
            val directStreamUrl = directLinkElement?.attr("href")?.trim() ?: ""

            if (directStreamUrl.isNotEmpty()) {
                return directStreamUrl
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }
}
