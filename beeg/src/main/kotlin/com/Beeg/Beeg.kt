package com.Beeg

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.json.JSONArray

class Beeg : MainAPI() {
    override var mainUrl = "https://beeg.com"
    override var name = "Beeg"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.NSFW)

    private val apiBase = "https://store.externulls.com"
    private val cdnBase = "https://video.beeg.com/"

    companion object {
        private var cachedItems: List<JSONObject>? = null
        private var cachedOffset = -1
    }

    override val mainPage = mainPageOf(
        "latest" to "Latest Videos"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val offset = (page - 1) * 48
        val json = app.get(
            "$apiBase/facts/tag?id=27173&limit=48&offset=$offset",
            headers = mapOf("Referer" to "https://beeg.com/")
        ).text
        val items = parseVideoList(json)
        return newHomePageResponse(
            list = HomePageList(request.name, items, isHorizontalImages = true),
            hasNext = true
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return emptyList()
    }

    override suspend fun load(url: String): LoadResponse {
        val fileId = url.substringAfterLast("/").substringBefore("?")
        val item = findCachedVideo(fileId) ?: fetchVideo(fileId)
        if (item == null) {
            return newMovieLoadResponse("Beeg Video", url, TvType.NSFW, url)
        }
        val fileInfo = item.optJSONObject("file") ?: return newMovieLoadResponse("Beeg Video", url, TvType.NSFW, url)
        val fileData = fileInfo.optJSONArray("data")
        val title = if (fileData != null && fileData.length() > 0)
            fileData.getJSONObject(0).optString("cd_value", "Beeg Video") else "Beeg Video"
        val poster = getPoster(fileId, item)
        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val fileId = data.substringAfterLast("/").substringBefore("?")
        val item = fetchVideo(fileId) ?: return false
        val fileInfo = item.optJSONObject("file") ?: return false
        var count = 0

        val hls = fileInfo.optJSONObject("hls_resources")
        val hlsRaw = hls?.optString("fl_cdn_multi", "")
        if (hlsRaw != null && hlsRaw.isNotEmpty()) {
            callback.invoke(
                newExtractorLink(source = name, name = "HLS", url = "$cdnBase$hlsRaw", type = ExtractorLinkType.M3U8) {
                    this.referer = "https://beeg.com/"
                    this.quality = Qualities.P1080.value
                }
            )
            count++
        }

        val fallback = fileInfo.optString("fallback", "")
        if (fallback.isNotEmpty()) {
            callback.invoke(
                newExtractorLink(source = name, name = "MP4", url = "$cdnBase$fallback") {
                    this.referer = "https://beeg.com/"
                    this.quality = Qualities.P480.value
                }
            )
            count++
        }

        return count > 0
    }

    private fun parseVideoList(json: String): List<SearchResponse> {
        val items = mutableListOf<SearchResponse>()
        val arr = JSONArray(json)
        val cached = mutableListOf<JSONObject>()
        for (i in 0 until arr.length()) {
            val item = arr.getJSONObject(i)
            cached.add(item)
            val fileInfo = item.optJSONObject("file") ?: continue
            val fileData = fileInfo.optJSONArray("data")
            if (fileData == null || fileData.length() == 0) continue
            val first = fileData.getJSONObject(0)
            val title = first.optString("cd_value", "").ifEmpty { continue }
            val fileId = first.optLong("cd_file", 0)
            if (fileId == 0L) continue
            val url = "https://beeg.com/$fileId"
            val poster = getPoster("$fileId", item)
            items.add(newMovieSearchResponse(title, url, TvType.NSFW) {
                this.posterUrl = poster
            })
        }
        cachedItems = cached
        return items
    }

    private fun getPoster(fileId: String, item: JSONObject): String? {
        val fcFacts = item.optJSONArray("fc_facts")
        if (fcFacts != null && fcFacts.length() > 0) {
            val facts = fcFacts.getJSONObject(0)
            val thumbs = facts.optJSONArray("fc_thumbs")
            if (thumbs != null && thumbs.length() > 0) {
                return "https://thumbs.externulls.com/videos/$fileId/${thumbs.getInt(0)}.webp?size=1280x720"
            }
        }
        return null
    }

    private fun findCachedVideo(fileId: String): JSONObject? {
        return cachedItems?.find { item ->
            val fileInfo = item.optJSONObject("file")
            val fileData = fileInfo?.optJSONArray("data")
            if (fileData != null && fileData.length() > 0) {
                fileData.getJSONObject(0).optLong("cd_file", 0).toString() == fileId
            } else false
        }
    }

    private suspend fun fetchVideo(fileId: String): JSONObject? {
        val json = app.get(
            "$apiBase/facts/tag?id=27173&limit=48&offset=0",
            headers = mapOf("Referer" to "https://beeg.com/")
        ).text
        val arr = JSONArray(json)
        for (i in 0 until arr.length()) {
            val item = arr.getJSONObject(i)
            val fileInfo = item.optJSONObject("file") ?: continue
            val fileData = fileInfo.optJSONArray("data")
            if (fileData != null && fileData.length() > 0) {
                if (fileData.getJSONObject(0).optLong("cd_file", 0).toString() == fileId) {
                    cachedItems = (0 until arr.length()).map { arr.getJSONObject(it) }
                    return item
                }
            }
        }
        return null
    }
}
