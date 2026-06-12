package com.Laidhub

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class Laidhub : MainAPI() {
    override var mainUrl = "https://www.laidhub.com"
    override var name = "Laidhub"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.NSFW)
    override val vpnStatus = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
        mainUrl to "Latest Videos"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data}page/$page/"
        val document = app.get(url).document
        val home = document.select("div.row div.item-col.col").mapNotNull {
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
        val link = this.selectFirst("a.thumb.video-preview-aspect-ratio[href*=\"/video/\"]") ?: return null
        val href = fixUrl(link.attr("href"))

        val title = this.selectFirst("span.title")?.text()?.trim()
            ?: link.selectFirst("img")?.attr("alt")
            ?: return null

        val poster = this.selectFirst("img[data-oe=\"shuffle-thumbs\"]")?.attr("src")
            ?: this.selectFirst("img.lazy")?.attr("data-src")

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = fixUrlNull(poster)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResponse = mutableListOf<SearchResponse>()
        for (i in 1..5) {
            val url = if (i == 1) "$mainUrl/search/$query/" else "$mainUrl/search/$query/page/$i/"
            val document = app.get(url).document
            val results = document.select("div.row div.item-col.col").mapNotNull {
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val docText = document.toString()

        document.select("video source[src]").forEach { source ->
            val src = source.attr("src")
            if (src.isNotEmpty()) {
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = src
                    ) {
                        this.referer = mainUrl
                    }
                )
            }
        }

        val fullUrlPatterns = listOf(
            Regex("""https?://[^"'\s]+cdn\.laidhub\.com[^"'\s]+\.(?:mp4|m3u8)[^"'\s]*"""),
            Regex("""https?://[^"'\s]+\.laidhub\.com[^"'\s]+\.(?:mp4|m3u8)[^"'\s]*"""),
        )

        val groupPatterns = listOf(
            Regex("""(?:src|url)\s*[:=]\s*["']([^"']+\.(?:mp4|m3u8)[^"']*)"""),
            Regex("""video_url\s*[:=]\s*["']([^"']+)"""),
            Regex("""source\s*:\s*["']([^"']+\.mp4[^"']*)"""),
        )

        for (pattern in fullUrlPatterns) {
            for (match in pattern.findAll(docText)) {
                val link = match.value
                if (link.isNotEmpty()) {
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = name,
                            url = link
                        ) {
                            this.referer = mainUrl
                        }
                    )
                }
            }
        }

        for (pattern in groupPatterns) {
            for (match in pattern.findAll(docText)) {
                val link = match.groupValues[1]
                if (link.isNotEmpty()) {
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = name,
                            url = link
                        ) {
                            this.referer = mainUrl
                        }
                    )
                }
            }
        }

        return true
    }
}
