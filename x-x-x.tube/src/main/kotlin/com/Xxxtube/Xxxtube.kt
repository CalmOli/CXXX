package com.Xxxtube

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject

class Xxxtube : MainAPI() {
    override var mainUrl = "https://cloudstream-scraper.calm-oil.workers.dev"
    override var name = "Xxxtube"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.NSFW)
    private val providerName = "xxxtube"

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
        val pageUrl = data
        var count = 0
        val seen = mutableSetOf<String>()

        // Get Worker response first (includes HTML + sources)
        val encoded = java.net.URLEncoder.encode(pageUrl, "UTF-8")
        val apiUrl = "$mainUrl/api/loadlinks?provider=$providerName&url=$encoded"
        val res = app.get(apiUrl).text
        val obj = JSONObject(res)
        val html = obj.optString("html", null)

        // Strategy 2: Worker-extracted sources (fallback)
        val pageUrl2 = obj.optString("page", pageUrl)
        if (obj.has("sources")) {
            val srcArr = obj.getJSONArray("sources")
            for (i in 0 until srcArr.length()) {
                val src = srcArr.getJSONObject(i)
                var url = src.getString("url")
                if (!url.contains("/get_file/")) url = url.trimEnd('/')
                if (url.isEmpty() || seen.contains(url)) continue
                seen.add(url)
                val quality = src.optInt("quality", Qualities.Unknown.value)
                val isM3u8 = src.optBoolean("isM3u8", false) || url.contains(".m3u8")
                callback.invoke(
                    newExtractorLink(source = name, name = name, url = url, type = if (isM3u8) ExtractorLinkType.M3U8 else null) {
                        this.referer = pageUrl2
                        this.quality = quality
                    }
                )
                count++
            }
        }

        // Strategy 3: Direct extraction from page (final fallback)
        if (count == 0 && html != null) {
            val videoUrls = mutableListOf<Pair<String, Int>>()

            fun addUrl(url: String, quality: Int = Qualities.Unknown.value) {
                var cleaned = url
                if (!cleaned.contains("/get_file/")) cleaned = cleaned.trimEnd('/')
                if (cleaned.isEmpty() || seen.contains(cleaned)) return
                if (Regex("""_preview|_vthumb|screenshots|\.jpg|_trailer|preview\.mp4|/preview/|sexu-preview""").containsMatchIn(cleaned)) return
                val q = if (quality != Qualities.Unknown.value) quality else when {
                    "2160" in cleaned || "4k" in cleaned -> Qualities.P2160.value
                    "1080" in cleaned -> Qualities.P1080.value
                    "720" in cleaned -> Qualities.P720.value
                    "480" in cleaned -> Qualities.P480.value
                    "360" in cleaned -> Qualities.P360.value
                    else -> Qualities.Unknown.value
                }
                seen.add(cleaned)
                videoUrls.add(Pair(cleaned, q))
            }

            Regex("""video_url\s*:\s*['"]([^'"]+)['"]""").findAll(html).forEach {
                val u = it.groupValues[1]
                if (!u.startsWith("function/")) addUrl(u)
            }

            Regex("""https?://[^"'\s]+get_file[^"'\s]*\.mp4[^"'\s]*""").findAll(html).forEach {
                addUrl(it.value)
            }

            Regex("""https?://[^"'\s<>]+\.mp4[^"'\s<>]*""").findAll(html).forEach {
                addUrl(it.value)
            }

            Regex("""https?://[^"'\s<>]+\.m3u8[^"'\s<>]*""").findAll(html).forEach {
                addUrl(it.value)
            }

            for ((url, quality) in videoUrls) {
                callback.invoke(
                    newExtractorLink(source = name, name = name, url = url) {
                        this.referer = pageUrl2
                        this.quality = quality
                    }
                )
                count++
            }
        }

        return count > 0
    }
}
