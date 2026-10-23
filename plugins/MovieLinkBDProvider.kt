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

    private suspend fun getBrowserDoc(targetUrl: String, refererUrl: String = "$mainUrl/"): Document = withContext(Dispatchers.IO) {
        Jsoup.connect(targetUrl)
            .userAgent(userAgent)
            .referrer(refererUrl)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .header("Sec-Fetch-Site", "same-origin")
            .header("Sec-Fetch-Mode", "navigate")
            .ignoreContentType(true)
            .ignoreHttpErrors(true)
            .timeout(25000)
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
            val dlButtons = doc.select("a[data-dl], .mlbd-download-button-wrap a")

            for (btn in dlButtons) {
                val btnText = btn.selectFirst(".btn-text")?.text()?.trim() ?: btn.text().trim()
                val label = Regex("""\[(.*?)\]""").find(btnText)?.groupValues?.get(1)
                    ?: btnText.replace("Download", "").trim().ifEmpty { "Download Link" }

                val token = btn.attr("data-dl").trim()
                val ds = btn.attr("data-ds").trim()
                val rawHref = btn.attr("href").trim()

                // স্টেপ ১: ডাউনলোড টোকেন বা ইউআরএল নির্ধারণ
                val targetUrl = when {
                    token.isNotEmpty() -> {
                        "$mainUrl/dl?data=${URLEncoder.encode(token, "UTF-8")}&ds=$ds"
                    }
                    rawHref.isNotEmpty() && rawHref != "#" -> {
                        if (rawHref.startsWith("http")) rawHref else "$mainUrl$rawHref"
                    }
                    else -> ""
                }

                if (targetUrl.isNotEmpty()) {
                    rawMovieLinks.add(Pair(label, targetUrl))
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
            // স্টেপ ১: টোকেন বা ডাউনলোড হাব পেজে রিকোয়েস্ট পাঠানো
            val hubDoc = getBrowserDoc(generateUrl, refererUrl = "$mainUrl/")

            // স্টেপ ২: One Click Download লিঙ্ক বের করা
            var oneClickUrl = ""
            val oneClickBtn = hubDoc.selectFirst("a.dlh-arow[data-host='oneclick'], a[href*='/file/'], a[data-btn='One Click Download']")
            if (oneClickBtn != null) {
                val href = oneClickBtn.attr("href").trim()
                oneClickUrl = if (href.startsWith("http")) href else "$mainUrl$href"
            }

            // যদি হাব পেজে বাটন না থাকে, সরাসরি generateUrl কেই ফাইনাল পেজ ধরে চেষ্টা করা
            val fileDoc = if (oneClickUrl.isNotEmpty()) {
                getBrowserDoc(oneClickUrl, refererUrl = generateUrl)
            } else {
                hubDoc
            }

            // স্টেপ ৩: ফাইনাল পেজ থেকে ULTRA SPEED বা FAST CLOUD লিঙ্ক এক্সট্র্যাক্ট করা
            var directStreamUrl = ""

            // ৩.১: Ultra Speed লিঙ্ক খোঁজা
            val ultraBtn = fileDoc.selectFirst("a.btn-ultra[data-host='ultra'], a[data-btn*='ULTRA']")
            if (ultraBtn != null && ultraBtn.attr("href").trim().startsWith("http")) {
                directStreamUrl = ultraBtn.attr("href").trim()
            }

            // ৩.২: Fast Cloud লিঙ্ক খোঁজা (Ultra না থাকলে)
            if (directStreamUrl.isEmpty()) {
                val cloudBtn = fileDoc.selectFirst("a.btn-cloud[data-host='instant'], a[data-btn*='Fast Cloud']")
                if (cloudBtn != null && cloudBtn.attr("href").trim().startsWith("http")) {
                    directStreamUrl = cloudBtn.attr("href").trim()
                }
            }

            // ৩.৩: data-mlbd-click-ad অথবা যেকোনো R2 ডিরেক্ট স্টোরেজ লিঙ্ক
            if (directStreamUrl.isEmpty()) {
                val anyFinalBtn = fileDoc.selectFirst("a[data-mlbd-click-ad='file-final-download']")
                if (anyFinalBtn != null && anyFinalBtn.attr("href").trim().startsWith("http")) {
                    directStreamUrl = anyFinalBtn.attr("href").trim()
                }
            }

            // ৩.৪: ব্যাকআপ রেজেক্স (Cloudflare R2 / Cloud Storage)
            if (directStreamUrl.isEmpty()) {
                val r2Regex = Regex("""https?://[^\s"'<>]+\.(?:r2\.dev|cloudflarestorage\.com)[^\s"'<>]*""")
                directStreamUrl = r2Regex.find(fileDoc.html())?.value?.trim().orEmpty()
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
