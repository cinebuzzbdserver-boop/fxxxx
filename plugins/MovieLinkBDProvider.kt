package com.moviefy.stream.providers

import android.util.Base64
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

    private fun decryptMlbdData(b64Str: String, seedHex: String, rounds: Int): String {
        return try {
            var x = seedHex.toLong(16) and 0xFFFFFFFFL
            for (i in 0 until rounds) {
                x = (x * 1103515245L + 12345L) and 0xFFFFFFFFL
            }

            val rawBytes = Base64.decode(b64Str.trim(), Base64.DEFAULT)
            val out = StringBuilder()

            for (byte in rawBytes) {
                x = (x * 1103515245L + 12345L) and 0xFFFFFFFFL
                val keyByte = ((x shr 16) and 0xFFL).toInt()
                val decByte = (byte.toInt() and 0xFF) xor keyByte
                out.append(decByte.toChar())
            }
            out.toString()
        } catch (e: Exception) {
            ""
        }
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

            val playerScript = doc.selectFirst("script#mlbdInlinePlayerData")
            if (playerScript != null) {
                val pSeed = playerScript.attr("data-s").trim()
                val pRounds = playerScript.attr("data-r").trim().toIntOrNull() ?: 600
                val pPayload = playerScript.data().trim()

                if (pPayload.isNotEmpty() && pSeed.isNotEmpty()) {
                    val decryptedPlayer = decryptMlbdData(pPayload, pSeed, pRounds)
                    val streamUrls = Regex("""https?://[^\s"'<>]+""").findAll(decryptedPlayer).map { it.value.trim() }.toList()

                    streamUrls.distinct().forEachIndexed { index, streamLink ->
                        val label = when {
                            streamLink.contains("m3u8") -> "Fast Stream (HLS)"
                            index == 0 -> "Direct Player"
                            else -> "Server ${index + 1}"
                        }
                        rawMovieLinks.add(Pair(label, streamLink))
                    }
                }
            }

            val dlButtons = doc.select("a[data-dl], .mlbd-download-button-wrap a")
            for (btn in dlButtons) {
                val btnText = btn.selectFirst(".btn-text")?.text()?.trim() ?: btn.text().trim()
                val label = Regex("""\[(.*?)\]""").find(btnText)?.groupValues?.get(1)
                    ?: btnText.replace("Download", "").trim().ifEmpty { "Download Link" }

                val token = btn.attr("data-dl").trim()
                val ds = btn.attr("data-ds").trim()
                val dr = btn.attr("data-dr").trim().toIntOrNull() ?: 300
                val rawHref = btn.attr("href").trim()

                var targetUrl = ""
                if (token.isNotEmpty() && ds.isNotEmpty()) {
                    val decodedPath = decryptMlbdData(token, ds, dr)
                    if (decodedPath.isNotEmpty()) {
                        targetUrl = if (decodedPath.startsWith("http")) decodedPath else "$mainUrl$decodedPath"
                    }
                }

                if (targetUrl.isEmpty() && rawHref.isNotEmpty() && rawHref != "#") {
                    targetUrl = if (rawHref.startsWith("http")) rawHref else "$mainUrl$rawHref"
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
            if (generateUrl.contains(".r2.dev") || 
                generateUrl.contains(".cloudflarestorage.com") || 
                generateUrl.contains(".mp4") || 
                generateUrl.contains(".m3u8")) {
                return StreamItem(
                    quality = "HD",
                    streamUrl = generateUrl,
                    headers = mapOf("Referer" to "$mainUrl/", "User-Agent" to userAgent)
                )
            }

            val hubDoc = getBrowserDoc(generateUrl, refererUrl = "$mainUrl/")

            var oneClickUrl = ""
            val oneClickBtn = hubDoc.selectFirst("a.dlh-arow[data-host='oneclick'], a[href*='/file/'], a[data-btn='One Click Download']")
            if (oneClickBtn != null) {
                val href = oneClickBtn.attr("href").trim()
                oneClickUrl = if (href.startsWith("http")) href else "$mainUrl$href"
            }

            val fileDoc = if (oneClickUrl.isNotEmpty()) {
                getBrowserDoc(oneClickUrl, refererUrl = generateUrl)
            } else {
                hubDoc
            }

            var directStreamUrl = ""

            val ultraBtn = fileDoc.selectFirst("a.btn-ultra[data-host='ultra'], a[data-btn*='ULTRA']")
            if (ultraBtn != null && ultraBtn.attr("href").trim().startsWith("http")) {
                directStreamUrl = ultraBtn.attr("href").trim()
            }

            if (directStreamUrl.isEmpty()) {
                val cloudBtn = fileDoc.selectFirst("a.btn-cloud[data-host='instant'], a[data-btn*='Fast Cloud']")
                if (cloudBtn != null && cloudBtn.attr("href").trim().startsWith("http")) {
                    directStreamUrl = cloudBtn.attr("href").trim()
                }
            }

            if (directStreamUrl.isEmpty()) {
                val anyFinalBtn = fileDoc.selectFirst("a[data-mlbd-click-ad='file-final-download']")
                if (anyFinalBtn != null && anyFinalBtn.attr("href").trim().startsWith("http")) {
                    directStreamUrl = anyFinalBtn.attr("href").trim()
                }
            }

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
