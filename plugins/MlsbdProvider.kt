package com.flixora.providers

import com.flixora.plugin.MainAPI
import com.flixora.plugin.MovieItem
import org.jsoup.Jsoup

class MlsbdProvider : MainAPI() {
    override var name = "MLSBD"
    override var mainUrl = "https://mlsbd.co"

    override suspend fun getMainPage(): List<MovieItem> {
        val list = mutableListOf<MovieItem>()
        try {
            val doc = Jsoup.connect(mainUrl)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .timeout(15000)
                .get()

            // প্রতিটি পোস্ট কন্টেইনার সিলেক্ট করা
            val cards = doc.select("div.single-post")

            for (card in cards) {
                // ১. পোস্টের ইউআরএল
                val urlElement = card.selectFirst(".post-desc a")
                val url = urlElement?.attr("href")?.trim() ?: ""

                // ২. পোস্টের টাইটেল
                val titleElement = card.selectFirst(".post-desc h2.post-title")
                val title = titleElement?.text()?.trim() ?: ""

                // ৩. পোস্টের থাম্বনেইল ইমেজ (img src অথবা picture এর ভেতরের সোর্স)
                val imgElement = card.selectFirst(".thumb img")
                var image = imgElement?.attr("src")?.trim() ?: ""

                // যদি ডিরেক্ট src না পাওয়া যায়, তবে picture source ট্যাগ থেকে নেওয়া
                if (image.isEmpty()) {
                    image = card.selectFirst(".thumb picture source")?.attr("srcset")?.trim() ?: ""
                }

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

            val cards = doc.select("div.single-post")
            for (card in cards) {
                val url = card.selectFirst(".post-desc a")?.attr("href")?.trim() ?: ""
                val title = card.selectFirst(".post-desc h2.post-title")?.text()?.trim() ?: ""
                
                var image = card.selectFirst(".thumb img")?.attr("src")?.trim() ?: ""
                if (image.isEmpty()) {
                    image = card.selectFirst(".thumb picture source")?.attr("srcset")?.trim() ?: ""
                }

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
