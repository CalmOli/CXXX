package com.Theyarehuge

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class Theyarehuge : MainAPI() {
    override var mainUrl = "https://www.theyarehuge.com"
    override var name = "Theyarehuge"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.NSFW)
    override val vpnStatus = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
        mainUrl to "Latest Videos"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "$mainUrl/popular.porn-video/$page/"
        val document = app.get(url).document
        val home = document.select("a.item.drclass").mapNotNull {
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
        val href = fixUrlNull(this.attr("href")) ?: return null
        val title = this.selectFirst("div.video-title")?.text()?.trim()?.ifEmpty { return null } ?: return null
        val img = this.selectFirst("img.thumb")
        val posterUrl = fixUrlNull(img?.attr("src"))
        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResponse = mutableListOf<SearchResponse>()
        for (i in 1..5) {
            val url = if (i == 1) "$mainUrl/search/$query/" else "$mainUrl/search/$query/$i/"
            val document = app.get(url).document
            val results = document.select("a.item.drclass").mapNotNull {
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

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val document = app.get(data).document
        val docText = document.toString()
        val found = mutableListOf<Pair<String, Int>>()

        val videoUrlRegex = Regex("""video_url\s*:\s*['"]([^'"]+)['"]""")
        videoUrlRegex.findAll(docText).forEach {
            val url = it.groupValues[1]
            if (url.isNotEmpty()) found.add(Pair(fixUrl(url), Qualities.Unknown.value))
        }

        document.select("video source[src]").forEach { source ->
            val src = source.attr("src")
            if (src.isNotEmpty() && !src.contains(".jpg")) {
                val label = source.attr("label")
                val quality = when {
                    "2160" in label -> Qualities.P2160.value
                    "1080" in label -> Qualities.P1080.value
                    "720" in label -> Qualities.P720.value
                    "480" in label -> Qualities.P480.value
                    "360" in label -> Qualities.P360.value
                    else -> Qualities.Unknown.value
                }
                found.add(Pair(fixUrl(src), quality))
            }
        }

        val getFileRegex = Regex("""https?://[^"'\s]+get_file[^"'\s]*\.mp4[^"'\s]*""")
        getFileRegex.findAll(docText).forEach {
            val url = it.value
            if (!url.contains("_preview") && !url.contains("_vthumb") && !url.contains("_trailer") && !url.contains("screenshots") && !url.contains(".jpg")) {
                found.add(Pair(url, Qualities.Unknown.value))
            }
        }

        val cdnRegex = Regex("""https?://[^"'\s<>]+\.(?:bkcdn|bxcdn)[^"'\s<>]*\.mp4[^"'\s<>]*""")
        cdnRegex.findAll(docText).forEach {
            found.add(Pair(it.value, Qualities.Unknown.value))
        }

        val unique = found.distinctBy { it.first }
        for ((url, quality) in unique) {
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = url,
                ) {
                    this.referer = mainUrl
                    this.quality = quality
                }
            )
        }
        return unique.isNotEmpty()
    }
}
