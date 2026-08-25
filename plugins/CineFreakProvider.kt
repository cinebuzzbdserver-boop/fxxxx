package com.flixora.providers

import com.flixora.plugin.MainAPI
import com.flixora.plugin.MovieItem
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

    override suspend fun loadLinks(postUrl: String): List<StreamItem> = coroutineScope {
        val streamItems = mutableListOf<StreamItem>()
        try {
            val doc = Jsoup.connect(postUrl)
                .userAgent(userAgent)
                .timeout(15000)
                .get()

            val downloadDiv = doc.selectFirst(".download-links-div") ?: return@coroutineScope emptyList()
            val titles = downloadDiv.select("h4.movie-title")

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
                async {
                    resolveDirectStreamLink(quality, generateUrl)
                }
            }

            val results = deferredResults.awaitAll().filterNotNull()
            streamItems.addAll(results)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@coroutineScope streamItems
    }

    private fun resolveDirectStreamLink(quality: String, generateUrl: String): StreamItem? {
        try {
            // ১. Generate പേজ রিকোয়েস্ট করে window.location.href লিংক পার্স করা
            val genDoc = Jsoup.connect(generateUrl)
                .userAgent(userAgent)
                .referrer(mainUrl)
                .timeout(15000)
                .get()

            val htmlContent = genDoc.html()
            val locationRegex = Regex("""window\.location\.href\s*=\s*["']([^"']+)["']""")
            val match = locationRegex.find(htmlContent)
            val intermediateUrl = match?.groupValues?.get(1)?.trim() ?: return null

            // ২. /f/ পাথকে /d/ পাথে রূপান্তর করা
            val downloadPageUrl = if (intermediateUrl.contains("/f/")) {
                intermediateUrl.replace("/f/", "/d/")
            } else {
                intermediateUrl
            }

            // ৩. /d/ পেজে রিকোয়েস্ট পাঠিয়ে Cloudflare R2 ডিরেক্ট লিংক পার্স করা
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
