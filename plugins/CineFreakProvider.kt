package com.flixora.providers

import com.flixora.plugin.EpisodeItem
import com.flixora.plugin.MainAPI
import com.flixora.plugin.MediaDetails
import com.flixora.plugin.MovieItem
import com.flixora.plugin.SeasonItem
import com.flixora.plugin.StreamItem
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
        val details = loadMedia(postUrl)
        if (!details.isSeries) {
            return details.movieLinks
        }
        return details.seasons.firstOrNull()?.episodes?.firstOrNull()?.streamLinks ?: emptyList()
    }

    override suspend fun loadMedia(postUrl: String): MediaDetails = coroutineScope {
        try {
            val doc = Jsoup.connect(postUrl)
                .userAgent(userAgent)
                .timeout(15000)
                .get()

            val epCards = doc.select(".episode-grid .ep-card")
            val comboBoxes = doc.select(".quality-box")

            // ১. যদি সিরিজ / টিভি শো পাওয়া যায়
            if (epCards.isNotEmpty() || doc.selectFirst(".season-number") != null) {
                val seasonMap = mutableMapOf<String, MutableList<EpisodeItem>>()

                // এ) সিঙ্গেল এপিসোড কার্ডসমূহ পার্স করা
                for (card in epCards) {
                    val seasonName = card.selectFirst(".season-number")?.text()?.trim()?.ifEmpty { null } ?: "Season 1"
                    val epBadge = card.selectFirst(".episode-badge")?.text()?.trim() ?: ""
                    val epTitle = if (epBadge.isNotEmpty()) epBadge else (card.selectFirst(".ep-title")?.text()?.trim() ?: "Episode")

                    // Watch Box থেকে লিংক সংগ্রহ
                    val watchBox = card.selectFirst(".quality-box.watch-links") ?: card.selectFirst(".quality-box.download-links")
                    val links = watchBox?.select(".quality-grid a") ?: card.select(".quality-grid a")

                    val rawTasks = mutableListOf<Pair<String, String>>()
                    for (link in links) {
                        val quality = link.text().trim()
                        val relPath = link.attr("href").trim()
                        if (relPath.isNotEmpty()) {
                            val fullGenUrl = if (relPath.startsWith("http")) relPath else "$mainUrl$relPath"
                            rawTasks.add(Pair(quality, fullGenUrl))
                        }
                    }

                    val deferredStreams = rawTasks.map { (quality, genUrl) ->
                        async { resolveDirectStreamLink(quality, genUrl) }
                    }
                    val streams = deferredStreams.awaitAll().filterNotNull()

                    if (streams.isNotEmpty()) {
                        val epList = seasonMap.getOrPut(seasonName) { mutableListOf() }
                        epList.add(EpisodeItem(name = epTitle, streamLinks = streams))
                    }
                }

                // বি) মার্চ করা সিজন বক্স (যদি সিঙ্গেল এপিসোড ছাড়া সরাসরি সিজন প্যাক কম্বো থাকে)
                if (seasonMap.isEmpty()) {
                    val comboDl = doc.selectFirst(".quality-box.watch-links") ?: doc.selectFirst(".quality-box.download-links")
                    val links = comboDl?.select(".quality-grid a") ?: emptyList()

                    val rawTasks = mutableListOf<Pair<String, String>>()
                    for (link in links) {
                        val quality = link.text().trim()
                        val relPath = link.attr("href").trim()
                        if (relPath.isNotEmpty()) {
                            val fullGenUrl = if (relPath.startsWith("http")) relPath else "$mainUrl$relPath"
                            rawTasks.add(Pair(quality, fullGenUrl))
                        }
                    }

                    val deferredStreams = rawTasks.map { (quality, genUrl) ->
                        async { resolveDirectStreamLink(quality, genUrl) }
                    }
                    val streams = deferredStreams.awaitAll().filterNotNull()

                    if (streams.isNotEmpty()) {
                        val seasonName = doc.selectFirst(".season-number")?.text()?.trim()?.ifEmpty { null } ?: "Season 1"
                        seasonMap[seasonName] = mutableListOf(EpisodeItem(name = "Full Season", streamLinks = streams))
                    }
                }

                val seasonsList = seasonMap.map { (sName, epList) ->
                    SeasonItem(name = sName, episodes = epList)
                }

                if (seasonsList.isNotEmpty()) {
                    return@coroutineScope MediaDetails(
                        isSeries = true,
                        movieLinks = emptyList(),
                        seasons = seasonsList
                    )
                }
            }

            // ২. সাধারণ মুভি স্ট্রাকচার পার্সিং
            val downloadDiv = doc.selectFirst(".download-links-div")
            val titles = downloadDiv?.select("h4.movie-title") ?: emptyList()
            val rawTasks = mutableListOf<Pair<String, String>>()

            for (titleElement in titles) {
                val qualityText = titleElement.text().trim()
                var sibling = titleElement.nextElementSibling()

                while (sibling != null && !sibling.tagName().equals("h4", ignoreCase = true)) {
                    if (sibling.hasClass("dlbtn-container")) {
                        val downloadBtn = sibling.selectFirst("a.dlbtn-download")
                        val genUrl = downloadBtn?.attr("href")?.trim() ?: ""
                        if (genUrl.isNotEmpty()) {
                            val fullGenUrl = if (genUrl.startsWith("http")) genUrl else "$mainUrl$genUrl"
                            rawTasks.add(Pair(qualityText, fullGenUrl))
                            break
                        }
                    }
                    sibling = sibling.nextElementSibling()
                }
            }

            val deferredResults = rawTasks.map { (quality, generateUrl) ->
                async { resolveDirectStreamLink(quality, generateUrl) }
            }

            val results = deferredResults.awaitAll().filterNotNull()
            return@coroutineScope MediaDetails(
                isSeries = false,
                movieLinks = results,
                seasons = emptyList()
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@coroutineScope MediaDetails(isSeries = false, movieLinks = emptyList(), seasons = emptyList())
    }

    private fun resolveDirectStreamLink(quality: String, generateUrl: String): StreamItem? {
        try {
            // ১. Generate পেজ রিকোয়েস্ট করে window.location.href লিংক পার্স করা
            val genDoc = Jsoup.connect(generateUrl)
                .userAgent(userAgent)
                .referrer(mainUrl)
                .timeout(15000)
                .get()

            val htmlContent = genDoc.html()
            val locationRegex = Regex("""window\.location\.href\s*=\s*["']([^"']+)["']""")
            val match = locationRegex.find(htmlContent)
            val intermediateUrl = match?.groupValues?.get(1)?.trim() ?: return null

            // ২. /f/ অথবা /x/ পাথকে /d/ পাথে রূপান্তর করা
            val downloadPageUrl = when {
                intermediateUrl.contains("/f/") -> intermediateUrl.replace("/f/", "/d/")
                intermediateUrl.contains("/x/") -> intermediateUrl.replace("/x/", "/d/")
                else -> intermediateUrl
            }

            // ৩. /d/ পেজে রিকোয়েস্ট পাঠিয়ে Cloudflare R2 ডিরেক্ট লিংক পার্স করা
            val dlDoc = Jsoup.connect(downloadPageUrl)
                .userAgent(userAgent)
                .referrer(generateUrl)
                .timeout(15000)
                .get()

            val directLinkElement = dlDoc.selectFirst("a.download-now")
            val directStreamUrl = directLinkElement?.attr("href")?.trim() ?: ""

            if (directStreamUrl.isNotEmpty()) {
                return StreamItem(
                    quality = quality,
                    streamUrl = directStreamUrl
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }
}
