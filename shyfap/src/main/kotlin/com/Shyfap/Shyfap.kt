package com.Shyfap

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.json.JSONArray
import org.jsoup.nodes.Element

class Shyfap : MainAPI() {
    override var mainUrl = "https://www.shyfap.net"
    override var name = "Shyfap"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        mainUrl to "Latest Videos"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "$mainUrl/videos_1/$page"
        val document = app.get(url).document
        val home = document.select("div.catalog > div.catalog_item").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = true
            ),
            hasNext = true
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val link = this.selectFirst("a.media-card[href*=\"/video/\"]") ?: return null
        val href = fixUrl(link.attr("href"))
        val title = this.selectFirst("div.media-card_title")?.text()?.trim()?.ifEmpty { null } ?: return null
        val posterDiv = link.selectFirst("div.media-card_preview")
        val posterUrl = fixUrlNull(posterDiv?.attr("data-preview"))
        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResponse = mutableListOf<SearchResponse>()
        for (i in 1..5) {
            val url = if (i == 1) "$mainUrl/search/?q=$query" else "$mainUrl/search/?q=$query&page=$i"
            val document = app.get(url).document
            val results = document.select("div.catalog > div.catalog_item").mapNotNull {
                it.toSearchResult()
            }
            if (!searchResponse.containsAll(results)) {
                searchResponse.addAll(results)
            } else {
                break
            }
            if (results.isEmpty()) break
        }
        return searchResponse
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1")?.text()?.trim()?.ifEmpty { null }
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")
            ?: "No Title"
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
        }
    }

    private suspend fun resolveOkru(apiUrl: String, referer: String, callback: (ExtractorLink) -> Unit) {
        try {
            val response = app.get(apiUrl, headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
                "Referer" to referer
            )).text
            val json = JSONObject(response)

            val playerInfo = json.optJSONObject("playerInfo")
            val videos: JSONArray? = playerInfo?.optJSONArray("videos") ?: json.optJSONArray("videos") ?: return

            for (i in 0 until videos.length()) {
                val video = videos.getJSONObject(i)
                val url = video.optString("url", "")
                if (url.isEmpty()) continue

                val qualityStr = video.optString("name", video.optString("quality", ""))
                val quality = when {
                    qualityStr.contains("2160") || qualityStr.contains("4k") -> Qualities.P2160.value
                    qualityStr.contains("1080") || qualityStr.contains("full") -> Qualities.P1080.value
                    qualityStr.contains("720") -> Qualities.P720.value
                    qualityStr.contains("480") -> Qualities.P480.value
                    qualityStr.contains("360") -> Qualities.P360.value
                    else -> Qualities.Unknown.value
                }

                callback.invoke(
                    newExtractorLink(source = this.name, name = this.name, url = url, type = ExtractorLinkType.M3U8) {
                        this.referer = referer
                        this.quality = quality
                    }
                )
            }
        } catch (_: Exception) { }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val document = app.get(data).document
        val docText = document.toString()
        val found = mutableListOf<Pair<String, Int>>()
        val okruUrls = mutableListOf<String>()
        val previewRe = Regex("""_preview|_vthumb|_trailer|screenshots|\.jpg|preview\.mp4|/preview/|sexu-preview""")

        fun cleanUrl(url: String): String {
            val u = fixUrl(url)
            return if (u.contains("/get_file/")) u else u.trimEnd('/')
        }
        fun isValid(url: String): Boolean = url.isNotEmpty() && !previewRe.containsMatchIn(url)

        val videoUrlRegex = Regex("""video_(?:alt_)?url\d*\s*:\s*['"]([^'"]+)['"]""")
        videoUrlRegex.findAll(docText).forEach {
            val url = it.groupValues[1]
            if (url.isNotEmpty() && !url.startsWith("function/")) {
                val quality = when {
                    "2160" in url || "4k" in url -> Qualities.P2160.value
                    "1080" in url -> Qualities.P1080.value
                    "720" in url -> Qualities.P720.value
                    "480" in url -> Qualities.P480.value
                    "360" in url -> Qualities.P360.value
                    else -> Qualities.Unknown.value
                }
                if (url.contains("api.ok.ru")) {
                    okruUrls.add(url)
                } else {
                    found.add(Pair(cleanUrl(url), quality))
                }
            }
        }

        document.select("video source[src], source[src]").forEach { source ->
            val src = source.attr("src")
            if (isValid(src)) {
                val label = source.attr("label")
                val quality = when {
                    "2160" in label || "4k" in label -> Qualities.P2160.value
                    "1080" in label -> Qualities.P1080.value
                    "720" in label -> Qualities.P720.value
                    "480" in label -> Qualities.P480.value
                    "360" in label -> Qualities.P360.value
                    else -> Qualities.Unknown.value
                }
                found.add(Pair(cleanUrl(src), quality))
            }
        }

        for (prop in listOf("og:video", "og:video:url", "og:video:secure_url")) {
            val meta = document.selectFirst("meta[property=$prop]")
            if (meta != null) {
                val src = meta.attr("content")
                if (isValid(src)) { found.add(Pair(cleanUrl(src), Qualities.Unknown.value)); break }
            }
        }

        val urlRegexes = listOf(
            Regex("""https?://[^"'\s]+get_stream[^"'\s]*\.mp4[^"'\s]*"""),
            Regex("""https?://[^"'\s]+get_file[^"'\s]*\.mp4[^"'\s]*"""),
            Regex("""https?://[^"'\s<>]+\.(?:bkcdn|bxcdn)[^"'\s<>]*\.mp4[^"'\s<>]*"""),
            Regex("""https?://[^"'\s<>]+\.mp4(?!\/[^"'\s<>]*\.(?:jpg|png|gif|webp))[^"'\s<>]*"""),
            Regex("""https?://[^"'\s<>]+\.m3u8[^"'\s<>]*"""),
        )
        for (re in urlRegexes) {
            re.findAll(docText).forEach {
                val url = it.value
                if (isValid(url)) {
                    if (url.contains("api.ok.ru")) {
                        okruUrls.add(url)
                    } else {
                        val quality = when {
                            "2160" in url || "4k" in url -> Qualities.P2160.value
                            "1080" in url -> Qualities.P1080.value
                            "720" in url -> Qualities.P720.value
                            "480" in url -> Qualities.P480.value
                            "360" in url -> Qualities.P360.value
                            else -> Qualities.Unknown.value
                        }
                        found.add(Pair(cleanUrl(url), quality))
                    }
                }
            }
        }

        val unique = found.distinctBy { it.first }
        var count = 0
        for ((url, quality) in unique) {
            val isM3u8 = url.contains(".m3u8")
            callback.invoke(
                newExtractorLink(source = this.name, name = this.name, url = url, type = if (isM3u8) ExtractorLinkType.M3U8 else null) {
                    this.referer = data
                    this.quality = quality
                }
            )
            count++
        }

        for (url in okruUrls.distinct()) {
            resolveOkru(url, data, callback)
            count++
        }

        return count > 0
    }
}
