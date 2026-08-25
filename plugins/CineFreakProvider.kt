package com.flixora.providers

import com.flixora.plugin.MainAPI
import com.flixora.plugin.MovieItem
import org.jsoup.Jsoup

class CineFreakProvider : MainAPI() {
    override var name = "CineFreak"
    override var mainUrl = "https://cinefreak.nl"

    override suspend fun getMainPage(page: Int): List<MovieItem> {
        val list = mutableListOf<MovieItem>()
        try {
            val targetUrl = if (page <= 1) {
                mainUrl
            } else {
                "$mainUrl/page/$page/"
            }

            val doc = Jsoup.connect(targetUrl)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
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
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
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
}
