package com.Xxxtube

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class Xxxtube : MainAPI() {
    override var mainUrl = "https://x-x-x.tube"
    override var name = "Xxxtube"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.NSFW)
    override val vpnStatus = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
        mainUrl to "Latest Videos"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "$mainUrl/videos_1/$page/"
        val document = app.get(url).document
        val home = document.select("div.catalog_item").mapNotNull {
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
        val link = selectFirst("a.media-card[href*=\"/video/\"]") ?: return null
        val title = selectFirst("div.media-card_title")?.text()?.trim()?.ifEmpty { return null } ?: return null
        val href = fixUrl(link.attr("href"))
        val posterPath = selectFirst("div.media-card_preview")?.attr("data-preview") ?: return null
        val posterUrl = if (posterPath.startsWith("http")) posterPath else "${mainUrl}${posterPath}"
        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResponse = mutableListOf<SearchResponse>()
        for (i in 1..5) {
            val url = if (i == 1) "$mainUrl/search/$query/" else "$mainUrl/search/$query/$i/"
            val document = app.get(url).document
            val results = document.select("div.catalog_item").mapNotNull {
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
        val videoSources = document.select("video source")
        for (source in videoSources) {
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
        val docText = document.toString()
        val regex = Regex("""https?://[^"']+get_file[^"']+""")
        val links = regex.findAll(docText).map { it.value }.toList()
        for (link in links) {
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
        return true
    }
}
