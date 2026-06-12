package com.Shyfap

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
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
        val title = link.selectFirst("div.media-card_title")?.text()?.trim()?.ifEmpty { null } ?: return null
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val docText = document.toString()

        val regex = Regex("""video_(?:url|alt_url\d*):\s*'(https?://[^']+)'""")
        val links = regex.findAll(docText).map { it.groupValues[1] }.toList()

        val sourceLinks = document.select("video source[src]").map { it.attr("src") }

        val getFileRegex = Regex("""(https?://[^'"]*get_file[^'"]*)""")
        val getFileLinks = getFileRegex.findAll(docText).map { it.groupValues[1] }.toList()

        val allLinks = (links + sourceLinks + getFileLinks).distinct()

        for (link in allLinks) {
            if (link.isNotEmpty()) {
                val quality = Regex("""(\d{3,4})\.mp4""").find(link)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: Qualities.Unknown.value
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = this.name,
                        url = link
                    ) {
                        this.referer = mainUrl
                        this.quality = quality
                    }
                )
            }
        }
        return true
    }
}
