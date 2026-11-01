package com.moviefy.stream.providers

import com.moviefy.stream.ui.screens.MainAPI
import com.moviefy.stream.ui.screens.MediaStructure
import com.moviefy.stream.ui.screens.MovieItem
import com.moviefy.stream.ui.screens.StreamItem
import com.moviefy.stream.ui.screens.SeasonItem
import com.moviefy.stream.ui.screens.EpisodeItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class XDMoviesProvider : MainAPI {
    override var name = "XDMovies"
    override var mainUrl = "https://huh.xdmovies.wtf"

    private var userAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Mobile Safari/537.36"

    private var baseClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private fun getActiveUserAgent(): String {
        return System.getProperty("moviefy.cf.userAgent")?.takeIf { it.isNotBlank() } ?: userAgent
    }

    private fun getBaseUrl(): String {
        val clean = mainUrl.trim().trimEnd('/')
        return if (clean.isEmpty()) "https://huh.xdmovies.wtf" else clean
    }

    private suspend fun fetchHtml(url: String, referer: String? = null): String = withContext(Dispatchers.IO) {
        try {
            val reqBuilder = Request.Builder()
                .url(url)
                .header("User-Agent", getActiveUserAgent())
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            if (!referer.isNullOrEmpty()) {
                reqBuilder.header("Referer", referer)
            }

            baseClient.newCall(reqBuilder.build()).execute().use { response ->
                response.body?.string().orEmpty()
            }
        } catch (_: Exception) {
            ""
        }
    }

    private fun decodeHtmlEntities(text: String): String {
        return text.trim()
            .replace("&#8211;", "–")
            .replace("&#8212;", "—")
            .replace("&#8217;", "'")
            .replace("&#039;", "'")
            .replace("&quot;", "\"")
            .replace("&amp;", "&")
            .replace("&nbsp;", " ")
    }

    private fun fixUrl(url: String, base: String): String {
        val trimmed = url.trim()
        if (trimmed.isEmpty() || trimmed.contains("placeholder.com", ignoreCase = true)) return ""

        val resolved = when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            trimmed.startsWith("//") -> "https:$trimmed"
            trimmed.startsWith("/") -> "$base$trimmed"
            else -> "$base/$trimmed"
        }

        return if (resolved.contains("image.tmdb.org/t/p/")) {
            resolved.replace(Regex("/t/p/[a-zA-Z0-9]+(?:_[a-zA-Z0-9]+)?/"), "/t/p/original/")
        } else {
            resolved
        }
    }

    private fun extractCardImage(cardHtml: String, base: String): String {
        val tmdbRegex = Regex("""(?:https?://image\.tmdb\.org)?/t/p/(?:[a-zA-Z0-9_]+/)?([a-zA-Z0-9_-]+\.(?:jpg|jpeg|png|webp))""", RegexOption.IGNORE_CASE)
        val tmdbMatch = tmdbRegex.find(cardHtml)
        if (tmdbMatch != null) {
            val fileName = tmdbMatch.groupValues[1].trim()
            return "https://image.tmdb.org/t/p/original/$fileName"
        }

        val imgSrcRegex = Regex("""<img\b[^>]*?\bsrc=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        val srcMatch = imgSrcRegex.find(cardHtml)?.groupValues?.get(1)?.trim().orEmpty()

        if (srcMatch.isNotEmpty() &&
            !srcMatch.contains("placeholder.com", ignoreCase = true) &&
            !srcMatch.startsWith("data:image", ignoreCase = true)
        ) {
            return fixUrl(srcMatch, base)
        }

        return ""
    }

    private fun parseMovieGrid(html: String, base: String): List<MovieItem> {
        val list = mutableListOf<MovieItem>()

        val itemRegex = Regex(
            """<a\s+[^>]*href=["']([^"']+)["'][^>]*class=["'][^"']*movie-link[^"']*["'][^>]*>([\s\S]*?)</a>""",
            RegexOption.IGNORE_CASE
        )
        val titleRegex = Regex("""<h3[^>]*>([\s\S]*?)</h3>""", RegexOption.IGNORE_CASE)
        val yearRegex = Regex("""<p[^>]*>(\d{4})</p>""", RegexOption.IGNORE_CASE)

        for (match in itemRegex.findAll(html)) {
            val rawPath = match.groupValues[1].trim()
            val innerCard = match.groupValues[2]

            val rawTitle = titleRegex.find(innerCard)?.groupValues?.get(1)?.replace(Regex("<[^>]*>"), "")?.trim().orEmpty()
            val year = yearRegex.find(innerCard)?.groupValues?.get(1)?.trim()
            val fullImg = extractCardImage(innerCard, base)

            if (rawPath.isNotEmpty() && rawTitle.isNotEmpty()) {
                val fullUrl = fixUrl(rawPath, base)
                val displayTitle = if (!year.isNullOrEmpty() && !rawTitle.contains(year)) {
                    "$rawTitle ($year)"
                } else {
                    rawTitle
                }

                list.add(
                    MovieItem(
                        title = decodeHtmlEntities(displayTitle),
                        image = fullImg,
                        url = fullUrl,
                        isPoster = true,
                        referer = "$base/"
                    )
                )
            }
        }

        return list.distinctBy { it.url }
    }

    override suspend fun getMainPage(page: Int): List<MovieItem> {
        val base = getBaseUrl()
        val targetUrl = if (page <= 1) {
            "$base/"
        } else {
            "$base/?page=$page"
        }
        val html = fetchHtml(targetUrl, "$base/")
        return parseMovieGrid(html, base)
    }

    override suspend fun search(query: String, page: Int): List<MovieItem> = withContext(Dispatchers.IO) {
        val base = getBaseUrl()
        val list = mutableListOf<MovieItem>()
        val activeUa = getActiveUserAgent()

        try {
            val tokenUrl = "$base/php/get_token.php"
            val tokenReq = Request.Builder()
                .url(tokenUrl)
                .header("User-Agent", activeUa)
                .header("Referer", "$base/")
                .header("Accept", "application/json, text/javascript, */*; q=0.01")
                .header("X-Requested-With", "XMLHttpRequest")
                .build()

            var authToken = ""
            baseClient.newCall(tokenReq).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (body.isNotEmpty()) {
                    authToken = JSONObject(body).optString("token", "").trim()
                }
            }

            if (authToken.isEmpty()) return@withContext emptyList()

            val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8")
            val searchApiUrl = "$base/php/search_api.php?query=$encodedQuery&fuzzy=true"

            val searchReq = Request.Builder()
                .url(searchApiUrl)
                .header("User-Agent", activeUa)
                .header("Referer", "$base/")
                .header("Accept", "application/json, text/javascript, */*; q=0.01")
                .header("x-auth-token", authToken)
                .header("x-requested-with", "XMLHttpRequest")
                .build()

            val searchRespText = baseClient.newCall(searchReq).execute().use { resp ->
                resp.body?.string().orEmpty()
            }

            if (searchRespText.isNotBlank()) {
                val jsonArray = JSONArray(searchRespText)
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.optJSONObject(i) ?: continue

                    val title = obj.optString("title", "").trim()
                    val path = obj.optString("path", "").trim()
                    val posterPath = obj.optString("poster", "").trim()
                    val year = obj.optString("release_year", "").trim()

                    if (title.isNotEmpty() && path.isNotEmpty()) {
                        val fullUrl = fixUrl(path, base)
                        val fullImg = if (posterPath.isNotEmpty()) {
                            val cleanPoster = if (posterPath.startsWith("/")) posterPath else "/$posterPath"
                            "https://image.tmdb.org/t/p/original$cleanPoster"
                        } else {
                            ""
                        }

                        val displayTitle = if (year.isNotEmpty() && !title.contains(year)) {
                            "$title ($year)"
                        } else {
                            title
                        }

                        list.add(
                            MovieItem(
                                title = decodeHtmlEntities(displayTitle),
                                image = fullImg,
                                url = fullUrl,
                                isPoster = true,
                                referer = "$base/"
                            )
                        )
                    }
                }
            }
        } catch (_: Exception) {
            return@withContext emptyList()
        }

        list.distinctBy { it.url }
    }

    private data class ParsedData(val title: String, val url: String, val size: String)

    override suspend fun loadMediaStructure(postUrl: String): MediaStructure = withContext(Dispatchers.IO) {
        val base = getBaseUrl()
        val html = fetchHtml(postUrl, "$base/")
        
        val extractedItems = mutableListOf<ParsedData>()

        // Helper function to extract items safely based on classes
        fun extractByRegex(titleClass: String) {
            val combinedRegex = Regex(
                """<div[^>]*class=["'][^"']*$titleClass[^"']*["'][^>]*>([\s\S]*?)</div>[\s\S]*?<a\s+[^>]*href=["']([^"']+)["'][^>]*>([\s\S]*?)</a>""",
                RegexOption.IGNORE_CASE
            )
            for (match in combinedRegex.findAll(html)) {
                val title = decodeHtmlEntities(match.groupValues[1].replace(Regex("<[^>]*>"), "").trim())
                val url = match.groupValues[2].trim()
                val size = decodeHtmlEntities(match.groupValues[3].replace(Regex("<[^>]*>"), "").trim())
                if (title.isNotEmpty() && url.isNotEmpty()) {
                    extractedItems.add(ParsedData(title, url, size))
                }
            }
        }

        // ১. Series/Episodes এর জন্য (episode-title)
        extractByRegex("episode-title")
        
        // ২. Movies / Netflix Versions এর জন্য (custom-title)
        extractByRegex("custom-title")
        
        // ৩. পুরাতন ফর্মেট বা প্যাক কার্ডের জন্য (pack-title)
        val packRegex = Regex(
            """<h4[^>]*class=["'][^"']*pack-title[^"']*["'][^>]*>([\s\S]*?)</h4>[\s\S]*?<a\s+[^>]*href=["']([^"']+)["'][^>]*>([\s\S]*?)</a>""",
            RegexOption.IGNORE_CASE
        )
        for (match in packRegex.findAll(html)) {
            val title = decodeHtmlEntities(match.groupValues[1].replace(Regex("<[^>]*>"), "").trim())
            val url = match.groupValues[2].trim()
            val size = decodeHtmlEntities(match.groupValues[3].replace(Regex("<[^>]*>"), "").trim())
            if (title.isNotEmpty() && url.isNotEmpty()) {
                extractedItems.add(ParsedData(title, url, size))
            }
        }

        // ৪. ফলব্যাক: যদি স্পেসিফিক ক্লাস না মেলে তবে নরমাল ডাউনলোড বাটন খুঁজবে
        if (extractedItems.isEmpty()) {
            val fallbackRegex = Regex(
                """<a\s+[^>]*href=["']([^"']+)["'][^>]*class=["'][^"']*(?:download-button|btn)[^"']*["'][^>]*>([\s\S]*?)</a>""",
                RegexOption.IGNORE_CASE
            )
            for (match in fallbackRegex.findAll(html)) {
                val url = match.groupValues[1].trim()
                val innerText = decodeHtmlEntities(match.groupValues[2].replace(Regex("<[^>]*>"), "").replace(Regex("\\s+"), " ").trim())
                val title = innerText.ifEmpty { "Download" }
                if (url.isNotEmpty()) {
                    extractedItems.add(ParsedData(title, url, ""))
                }
            }
        }

        // ডুপ্লিকেট লিংক বাদ দেওয়া
        val distinctItems = extractedItems.distinctBy { it.url }

        // S..E.. প্যাটার্ন চেক করা (যেমন: S01E05 বা S4E01)
        val seasonEpisodeRegex = Regex("""[Ss](\d+)[Ee](\d+)""")
        val isSeriesPost = distinctItems.any { seasonEpisodeRegex.containsMatchIn(it.title) }

        if (isSeriesPost) {
            // এটি একটি সিরিজ, সিজন ও এপিসোড অনুযায়ী আলাদা করা হবে
            val seasonMap = mutableMapOf<Int, MutableMap<Int, MutableList<Pair<String, String>>>>()

            distinctItems.forEach { item ->
                val match = seasonEpisodeRegex.find(item.title)
                if (match != null) {
                    val sNum = match.groupValues[1].toIntOrNull() ?: 1
                    val eNum = match.groupValues[2].toIntOrNull() ?: 1
                    
                    val formattedLabel = if (item.size.isNotEmpty()) "${item.title} - ${item.size}" else item.title
                    
                    seasonMap.getOrPut(sNum) { mutableMapOf() }
                        .getOrPut(eNum) { mutableListOf() }
                        .add(Pair(formattedLabel, item.url))
                } else {
                    // যদি সিরিজের পেজে কোনো এক্সট্রা ফাইল বা ওএসটি (OST) থাকে, সেটি Season 0 (Extras) তে যাবে
                    val formattedLabel = if (item.size.isNotEmpty()) "${item.title} - ${item.size}" else item.title
                    seasonMap.getOrPut(0) { mutableMapOf() }
                        .getOrPut(0) { mutableListOf() }
                        .add(Pair(formattedLabel, item.url))
                }
            }

            val seasonsList = mutableListOf<SeasonItem>()
            seasonMap.toSortedMap().forEach { (sNum, epMap) ->
                val seasonName = if (sNum == 0) "Extras / Other Files" else "Season $sNum"
                val episodesList = mutableListOf<EpisodeItem>()
                
                epMap.toSortedMap().forEach { (eNum, links) ->
                    val epName = if (eNum == 0) "Extra File" else "Episode %02d".format(eNum)
                    episodesList.add(EpisodeItem(name = epName, rawGenerateLinks = links))
                }
                seasonsList.add(SeasonItem(name = seasonName, episodes = episodesList))
            }

            return@withContext MediaStructure(
                isSeries = true,
                rawMovieLinks = emptyList(),
                seasons = seasonsList
            )
        } else {
            // এটি একটি মুভি, নরমাল মুভি লিস্ট হিসেবে জেনারেট করা হবে
            val movieLinks = distinctItems.map { item ->
                val formattedLabel = if (item.size.isNotEmpty()) "${item.title} - ${item.size}" else item.title
                Pair(formattedLabel, item.url)
            }
            return@withContext MediaStructure(
                isSeries = false,
                rawMovieLinks = movieLinks,
                seasons = emptyList()
            )
        }
    }

    override suspend fun resolveDirectLink(generateUrl: String): StreamItem? = withContext(Dispatchers.IO) {
        if (generateUrl.isBlank()) return@withContext null

        try {
            val base = getBaseUrl()
            StreamItem(
                quality = "Direct Stream",
                streamUrl = generateUrl.trim(),
                headers = mapOf(
                    "User-Agent" to getActiveUserAgent(),
                    "Referer" to "$base/"
                )
            )
        } catch (_: Throwable) {
            null
        }
    }
}
