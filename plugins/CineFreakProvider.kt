package com.flixora.providers

import com.flixora.plugin.EpisodeItem
import com.flixora.plugin.MainAPI
import com.flixora.plugin.MediaStructure
import com.flixora.plugin.MovieItem
import com.flixora.plugin.SeasonItem
import com.flixora.plugin.StreamItem
import org.jsoup.Jsoup
import java.net.URLEncoder

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
        return search(query, 1)
    }

    override suspend fun search(query: String, page: Int): List<MovieItem> {
        val list = mutableListOf<MovieItem>()
        try {
            val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8")
            val searchApiUrl = "$mainUrl/search-api.php?q=$encodedQuery&pg=$page"

            val response = Jsoup.connect(searchApiUrl)
                .userAgent(userAgent)
                .referrer(mainUrl)
                .ignoreContentType(true)
                .timeout(15000)
                .execute()

            val rawJson = response.body().trim()

            // Pure Regex Parsing (No External Dependency)
            val blockRegex = Regex("""\{"t":"(.*?)","l":"(.*?)".*?"i":"(.*?)".*?\}""")
            val matches = blockRegex.findAll(rawJson)

            for (match in matches) {
                var title = match.groupValues[1]
                val slug = match.groupValues[2]
                var image = match.groupValues[3]

                title = title.replace("\\/", "/").replace("\\\"", "\"").trim()
                image = image.replace("\\/", "/").trim()

                if (title.isNotEmpty() && slug.isNotEmpty()) {
                    val fullUrl = if (slug.startsWith("http")) {
                        slug
                    } else {
                        "$mainUrl/$slug/"
                    }

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

            val epCards = doc.select(".episode-grid .ep-card")

            if (epCards.isNotEmpty() || doc.selectFirst(".season-number") != null) {
                val seasonMap = mutableMapOf<String, MutableList<EpisodeItem>>()

                for (card in epCards) {
                    val seasonName = card.selectFirst(".season-number")?.text()?.trim()?.ifEmpty { null } ?: "Season 1"
                    val epBadge = card.selectFirst(".episode-badge")?.text()?.trim() ?: ""
                    val epTitle = if (epBadge.isNotEmpty()) epBadge else (card.selectFirst(".ep-title")?.text()?.trim() ?: "Episode")

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
            val genDoc = Jsoup.connect(generateUrl)
                .userAgent(userAgent)
                .referrer(mainUrl)
                .timeout(15000)
                .get()

            val htmlContent = genDoc.html()
            val locationRegex = Regex("""window\.location\.href\s*=\s*["']([^"']+)["']""")
            val match = locationRegex.find(htmlContent)
            val intermediateUrl = match?.groupValues?.get(1)?.trim() ?: return null

            val downloadPageUrl = when {
                intermediateUrl.contains("/f/") -> intermediateUrl.replace("/f/", "/d/")
                intermediateUrl.contains("/x/") -> intermediateUrl.replace("/x/", "/d/")
                else -> intermediateUrl
            }

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
