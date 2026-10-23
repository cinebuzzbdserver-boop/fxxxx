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

class MovieLinkBDProvider : MainAPI {
    override var name = "MovieLinkBD"
    override var mainUrl = "https://movielinkbd.tv"

    private val userAgent = "Mozilla/5.0 (Linux; Android 16; 23090RA98I Build/BP2A.250605.031.A3) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/153.0.8010.57 Mobile Safari/537.36"

    private suspend fun getBrowserDoc(targetUrl: String): Document = withContext(Dispatchers.IO) {
        Jsoup.connect(targetUrl)
            .userAgent(userAgent)
            .referrer("$mainUrl/")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .ignoreContentType(true)
            .ignoreHttpErrors(true)
            .timeout(20000)
            .get()
    }

    override suspend fun getMainPage(page: Int): List<MovieItem> {
        val list = mutableListOf<MovieItem>()
        try {
            val targetUrl = if (page <= 1) "$mainUrl/" else "$mainUrl/page/$page"
            val doc = getBrowserDoc(targetUrl)
            val cards = doc.select(".movie-cards-container .movie-card, .movie-card")

            for (card in cards) {
                val anchor = card.selectFirst("a[data-mlbd-click-ad], .image-container a, a.title") ?: continue
                val rawUrl = anchor.attr("href").trim()
                if (rawUrl.isEmpty()) continue

                val imgEl = card.selectFirst("img.mlbd-img") ?: card.selectFirst("img")
                var image = imgEl?.attr("data-src")?.trim().orEmpty()
                if (image.isEmpty() || image.startsWith("data:image")) {
                    image = imgEl?.attr("src")?.trim().orEmpty()
                }

                val title = card.selectFirst("a.title")?.text()?.trim()
                    ?: imgEl?.attr("title")?.trim()?.ifEmpty { null }
                    ?: imgEl?.attr("alt")?.trim()
                    ?: "Unknown Title"

                val fullUrl = if (rawUrl.startsWith("http")) rawUrl else "$mainUrl$rawUrl"

                list.add(
                    MovieItem(
                        title = title,
                        image = image,
                        url = fullUrl,
                        isPoster = true,
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
            val targetUrl = if (page <= 1) {
                "$mainUrl/search?q=$encodedQuery"
            } else {
                "$mainUrl/search?q=$encodedQuery&page=$page"
            }

            val doc = getBrowserDoc(targetUrl)
            val cards = doc.select(".movie-cards-container .movie-card, .movie-card")

            for (card in cards) {
                val anchor = card.selectFirst("a[data-mlbd-click-ad], .image-container a, a.title") ?: continue
                val rawUrl = anchor.attr("href").trim()
                if (rawUrl.isEmpty()) continue

                val imgEl = card.selectFirst("img.mlbd-img") ?: card.selectFirst("img")
                var image = imgEl?.attr("data-src")?.trim().orEmpty()
                if (image.isEmpty() || image.startsWith("data:image")) {
                    image = imgEl?.attr("src")?.trim().orEmpty()
                }

                val title = card.selectFirst("a.title")?.text()?.trim()
                    ?: imgEl?.attr("title")?.trim()?.ifEmpty { null }
                    ?: imgEl?.attr("alt")?.trim()
                    ?: "Unknown Title"

                val fullUrl = if (rawUrl.startsWith("http")) rawUrl else "$mainUrl$rawUrl"

                list.add(
                    MovieItem(
                        title = title,
                        image = image,
                        url = fullUrl,
                        isPoster = true,
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
            val doc = getBrowserDoc(postUrl)
            
            // পোস্ট পেজের ডাউনলোড বাটনগুলো ফিল্টার করা
            val dlButtons = doc.select("a[data-dl], .mlbd-download-button-wrap a")

            for (btn in dlButtons) {
                val btnText = btn.selectFirst(".btn-text")?.text()?.trim()
                    ?: btn.text().trim()

                val label = Regex("""\[(.*?)\]""").find(btnText)?.groupValues?.get(1)
                    ?: btnText.replace("Download", "").trim().ifEmpty { "Download Link" }

                // data-dl টোকেন অথবা href থেকে রিলেটিভ পাথ বের করা
                val href = btn.attr("href").trim()
                val targetPath = if (href.isNotEmpty() && href != "#") {
                    href
                } else {
                    val token = btn.attr("data-dl").trim()
                    if (token.isNotEmpty()) "/download?token=${URLEncoder.encode(token, "UTF-8")}" else ""
                }

                if (targetPath.isNotEmpty()) {
                    val fullTargetUrl = if (targetPath.startsWith("http")) targetPath else "$mainUrl$targetPath"
                    rawMovieLinks.add(Pair(label, fullTargetUrl))
                }
            }

            if (rawMovieLinks.isEmpty()) {
                rawMovieLinks.add(Pair("Direct Stream", postUrl))
            }

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
            // স্টেপ ১: ডাউনলোড হাব পেজ ওপেন করা (যেখানে One Click বাটন থাকে)
            val doc = getBrowserDoc(generateUrl)

            var intermediateUrl = ""

            // ১. One Click Download বাটন খোঁজা
            val oneClickBtn = doc.selectFirst("a.dlh-arow[data-host='oneclick'], a[href*='/file/']")
            if (oneClickBtn != null) {
                val href = oneClickBtn.attr("href").trim()
                intermediateUrl = if (href.startsWith("http")) href else "$mainUrl$href"
            }

            // স্টেপ ২: One Click লিঙ্ক ওপেন করে ফাইনাল Ultra/Cloud ডিরেক্ট লিঙ্ক রিভল্ভ করা
            val finalDoc = if (intermediateUrl.isNotEmpty()) getBrowserDoc(intermediateUrl) else doc

            // ২. Ultra Speed বা Cloudflare R2 / Fast Cloud ডিরেক্ট লিঙ্ক ফিল্টার
            val directStreamAnchor = finalDoc.selectFirst("a.btn-ultra[data-host='ultra'], a.btn-cloud[data-host='instant'], a[data-mlbd-click-ad='file-final-download']")

            var directStreamUrl = directStreamAnchor?.attr("href")?.trim().orEmpty()

            // ৩. ব্যাকআপ: যদি নির্দিষ্ট ক্লাস না মেলে তবে R2 ডিরেক্ট স্টোরেজ লিঙ্ক চেক
            if (directStreamUrl.isEmpty()) {
                val r2Regex = Regex("""https?://[^\s"'<>]+\.(?:r2\.dev|cloudflarestorage\.com)[^\s"'<>]*""")
                directStreamUrl = r2Regex.find(finalDoc.html())?.value?.trim().orEmpty()
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
