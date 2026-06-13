package com.Bingato

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.nodes.Element

class Bingato : MainAPI() {
    override var mainUrl = "https://cloudstream-scraper.calm-oil.workers.dev"
    override var name = "Bingato"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.NSFW)
    private val providerName = "bingato"

    override val mainPage = mainPageOf(
        "$mainUrl/api/mainpage?provider=$providerName" to "Latest Videos",
        "$mainUrl/api/mainpage?provider=$providerName" to "Most Viewed",
        "$mainUrl/api/mainpage?provider=$providerName" to "Longest",
        "$mainUrl/api/mainpage?provider=$providerName" to "Quality",
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
        val pageUrl = obj.optString("page", data)
        var count = 0
        val seen = mutableSetOf<String>()

        // Always visit page first to establish session cookies for get_file URLs
        val doc = try { app.get(pageUrl).document } catch (_: Exception) { null }
        val docText = doc?.toString()

        // Strategy 1: Use Worker-extracted sources if available
        if (obj.has("sources")) {
            val srcArr = obj.getJSONArray("sources")
            for (i in 0 until srcArr.length()) {
                val src = srcArr.getJSONObject(i)
                var url = src.getString("url")
                // get_file URLs need trailing slash; others can be cleaned
                if (!url.contains("/get_file/")) url = url.trimEnd('/')
                if (url.isEmpty() || seen.contains(url)) continue
                seen.add(url)
                val quality = src.optInt("quality", Qualities.Unknown.value)
                val isM3u8 = src.optBoolean("isM3u8", false) || url.contains(".m3u8")
                if (isM3u8) {
                    callback.invoke(
                        newExtractorLink(source = name, name = name, url = url, ExtractorLinkType.M3U8) {
                            this.referer = pageUrl
                            this.quality = quality
                        }
                    )
                } else {
                    callback.invoke(
                        newExtractorLink(source = name, name = name, url = url) {
                            this.referer = pageUrl
                            this.quality = quality
                        }
                    )
                }
                count++
            }
        }

        // Strategy 2: Fallback - extract from page directly
        if (count == 0) {
            if (docText != null) {
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

                // video_url JS variable
                Regex("""video_url\s*:\s*['"]([^'"]+)['"]""").findAll(docText).forEach {
                    val u = it.groupValues[1]
                    if (!u.startsWith("function/")) addUrl(u)
                }

                // get_file URLs
                Regex("""https?://[^"'\s]+get_file[^"'\s]*\.mp4[^"'\s]*""").findAll(docText).forEach {
                    addUrl(it.value)
                }

                // <source src> tags
                doc.select("source[src]").forEach { source ->
                    val src = source.attr("src")
                    if (src.isNotEmpty()) addUrl(fixUrl(src))
                }

                // og:video meta tags
                for (prop in listOf("og:video", "og:video:url", "og:video:secure_url")) {
                    val meta = doc.selectFirst("meta[property=$prop]")
                    if (meta != null) {
                        val content = meta.attr("content")
                        if (content.isNotEmpty()) { addUrl(fixUrl(content)); break }
                    }
                }

                // direct .mp4 URLs
                Regex("""https?://[^"'\s<>]+\.mp4[^"'\s<>]*""").findAll(docText).forEach {
                    addUrl(it.value)
                }

                // .m3u8 URLs
                Regex("""https?://[^"'\s<>]+\.m3u8[^"'\s<>]*""").findAll(docText).forEach {
                    addUrl(it.value)
                }

                for ((url, quality) in videoUrls) {
                    callback.invoke(
                        newExtractorLink(source = name, name = name, url = url) {
                            this.referer = pageUrl
                            this.quality = quality
                        }
                    )
                    count++
                }
            }
        }

        return count > 0
    }
}
