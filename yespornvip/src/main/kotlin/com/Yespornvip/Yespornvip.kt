package com.Yespornvip

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject

class Yespornvip : MainAPI() {
    override var mainUrl = "https://cloudstream-scraper.calm-oil.workers.dev"
    override var name = "Yespornvip"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.NSFW)
    private val providerName = "yespornvip"

    override val mainPage = mainPageOf(
        "$mainUrl/api/mainpage?provider=$providerName" to "Latest Videos",
        "$mainUrl/api/mainpage?provider=$providerName" to "Top Rated",
        "$mainUrl/api/mainpage?provider=$providerName" to "Most Viewed",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val res = app.get(request.data).text
        val obj = JSONObject(res)
        val sections = obj.getJSONArray("sections")
        val list = mutableListOf<SearchResponse>()

        for (i in 0 until sections.length()) {
            val section = sections.getJSONObject(i)
            if (section.getString("name") == request.name) {
                val items = section.getJSONArray("items")
                for (j in 0 until items.length()) {
                    val item = items.getJSONObject(j)
                    val url = item.getString("url")
                    val title = item.getString("title")
                    val poster = if (item.has("poster") && !item.isNull("poster")) item.getString("poster") else null
                    list.add(newMovieSearchResponse(title, url, TvType.NSFW) {
                        this.posterUrl = poster
                    })
                }
                break
            }
        }

        return newHomePageResponse(
            list = HomePageList(request.name, list, isHorizontalImages = true),
            hasNext = false
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/api/search?provider=$providerName&q=${java.net.URLEncoder.encode(query, "UTF-8")}"
        val res = app.get(url).text
        val obj = JSONObject(res)
        val items = obj.getJSONArray("items")
        val results = mutableListOf<SearchResponse>()

        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            val url = item.getString("url")
            val title = item.getString("title")
            val poster = if (item.has("poster") && !item.isNull("poster")) item.getString("poster") else null
            results.add(newMovieSearchResponse(title, url, TvType.NSFW) {
                this.posterUrl = poster
            })
        }

        return results
    }

    override suspend fun load(url: String): LoadResponse {
        val encoded = java.net.URLEncoder.encode(url, "UTF-8")
        val apiUrl = "$mainUrl/api/load?provider=$providerName&url=$encoded"
        val res = app.get(apiUrl).text
        val obj = JSONObject(res)
        val title = obj.optString("title", "No Title")
        val poster = if (obj.has("poster") && !obj.isNull("poster")) obj.getString("poster") else null
        val description = if (obj.has("description") && !obj.isNull("description")) obj.getString("description") else null

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val encoded = java.net.URLEncoder.encode(data, "UTF-8")
        val apiUrl = "$mainUrl/api/loadlinks?provider=$providerName&url=$encoded"
        val res = app.get(apiUrl).text
        val obj = JSONObject(res)
        val sources = obj.getJSONArray("sources")
        var count = 0

        for (i in 0 until sources.length()) {
            val src = sources.getJSONObject(i)
            val url = src.getString("url")
            val quality = src.optString("quality", "Unknown")
            callback.invoke(
                newExtractorLink(name, name, url) {
                    this.referer = data
                    this.quality = when {
                        quality.contains("2160") -> Qualities.P2160.value
                        quality.contains("1080") -> Qualities.P1080.value
                        quality.contains("720") -> Qualities.P720.value
                        quality.contains("480") -> Qualities.P480.value
                        quality.contains("360") -> Qualities.P360.value
                        else -> Qualities.Unknown.value
                    }
                }
            )
            count++
        }

        return count > 0
    }
}
