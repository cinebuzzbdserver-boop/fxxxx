package com.flixora.providers

import com.flixora.plugin.MainAPI
import com.flixora.plugin.MovieItem
import org.jsoup.Jsoup

class CineFreakProvider : MainAPI() {
    override var name = "CineFreak"
    override var mainUrl = "https://cinefreak.nl"

    override suspend fun getMainPage(): List<MovieItem> {
        val list = mutableListOf<MovieItem>()
        try {
            val doc = Jsoup.connect(mainUrl)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .timeout(15000)
                .get()

            // এইচটিএমএল কার্ডগুলো সিলেক্ট করা
            val cards = doc.select("a.movie-card")

            for (card in cards) {
                // ১. পোস্টের ইউআরএল
                val url = card.attr("href").trim()

                // ২. পোস্টের থাম্বনেইল ইমেজ
                val imgElement = card.selectFirst(".movie-card-image img")
                val image = imgElement?.attr("src")?.trim() ?: ""

                // ৩. মুভি/সিরিজের টাইটেল
                val titleElement = card.selectFirst(".movie-card-content .movie-card-title")
                val title = titleElement?.text()?.trim() ?: ""

                if (title.isNotEmpty() && url.isNotEmpty()) {
                    list.add(MovieItem(title = title, image = image, url = url))
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
                    list.add(MovieItem(title = title, image = image, url = url))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }
}
