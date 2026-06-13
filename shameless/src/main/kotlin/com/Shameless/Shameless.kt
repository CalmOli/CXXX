package com.Shameless

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.nodes.Element

class Shameless : MainAPI() {
    override var mainUrl = "https://cloudstream-scraper.calm-oil.workers.dev"
    override var name = "Shameless"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.NSFW)
    private val providerName = "shameless"

    override val mainPage = mainPageOf(
        "$mainUrl/api/mainpage?provider=$providerName" to "Latest Videos",
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

        val docText = app.get(pageUrl).text
        val videoUrls = mutableListOf<Pair<String, Int>>()
        val urlRegex = Regex("""video_url\s*:\s*['"]([^'"]+)['"]""")
        for (match in urlRegex.findAll(docText)) {
            videoUrls.add(Pair(match.groupValues[1].replace(Regex("""/$"""), ""), Qualities.Unknown.value))
        }
        val getFileRegex = Regex("""https?://[^"'\s]+get_file[^"'\s]*\.mp4[^"'\s]*""")
        for (match in getFileRegex.findAll(docText)) {
            val u = match.value
            if (!u.contains("_preview") && !u.contains("_vthumb") && !u.contains("screenshots") && !u.contains(".jpg")) {
                videoUrls.add(Pair(u, Qualities.Unknown.value))
            }
        }

        val seen = mutableSetOf<String>()
        var count = 0
        for ((url, quality) in videoUrls) {
            if (seen.contains(url)) continue
            seen.add(url)
            callback.invoke(
                newExtractorLink(name, name, url) {
                    this.referer = pageUrl
                    this.quality = quality
                }
            )
            count++
        }
        return count > 0
    }
}
