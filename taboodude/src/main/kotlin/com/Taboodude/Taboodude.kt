package com.Taboodude

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class Taboodude : MainAPI() {
    override var mainUrl = "https://www.taboodude.com"
    override var name = "Taboodude"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.NSFW)
    override val vpnStatus = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
        mainUrl to "Latest Videos"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${mainUrl}/?page=$page"
        val document = app.get(url).document
        val home = document.select("div#list_videos_most_recent_videos_items div.item").mapNotNull {
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
        val link = this.selectFirst("a[href*=\"/video/\"]") ?: return null
        val href = fixUrl(link.attr("href"))
        val title = this.selectFirst("strong.title")?.text()?.trim()?.ifEmpty { null }
            ?: link.attr("title").ifEmpty { null }
            ?: this.selectFirst("img")?.attr("alt")?.ifEmpty { null }
            ?: return null
        val poster = this.selectFirst("img[data-original]")?.attr("data-original")?.ifEmpty { null }
            ?: this.selectFirst("img")?.attr("src")?.ifEmpty { null }
            ?: this.selectFirst("img")?.attr("data-src")?.ifEmpty { null }
        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = fixUrlNull(poster)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResponse = mutableListOf<SearchResponse>()
        for (i in 1..5) {
            val url = if (i == 1) "$mainUrl/search/$query/" else "$mainUrl/search/$query/?page=$i"
            val document = app.get(url).document
            val results = document.select("div#list_videos_most_recent_videos_items div.item").mapNotNull {
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
        var found = false

        document.select("video source, source[type*=video]").forEach { source ->
            val src = source.attr("src")
            if (src.isNotEmpty()) {
                val label = source.attr("label")
                val quality = when {
                    "1080" in label -> Qualities.P1080.value
                    "720" in label -> Qualities.P720.value
                    "480" in label -> Qualities.P480.value
                    "360" in label -> Qualities.P360.value
                    else -> Qualities.Unknown.value
                }
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = src
                    ) {
                        this.referer = mainUrl
                        this.quality = quality
                    }
                )
                found = true
            }
        }

        document.select("video[src]").forEach { video ->
            val src = video.attr("src")
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
                found = true
            }
        }

        document.select("script").forEach { script ->
            val html = script.html()
            Regex("""(?:file|src|url)\s*[:=]\s*["']([^"']*\.(?:mp4|m3u8))["']""")
                .findAll(html)
                .forEach { match ->
                    val src = match.groupValues[1]
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
                        found = true
                    }
                }
        }

        document.select("iframe[src]").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotEmpty() && !src.contains("google") && !src.contains("facebook")) {
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "$name (iframe)",
                        url = src
                    ) {
                        this.referer = mainUrl
                    }
                )
                found = true
            }
        }

        return found
    }
}
