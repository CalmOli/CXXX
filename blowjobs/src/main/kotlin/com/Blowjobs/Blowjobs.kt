package com.Blowjobs

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class Blowjobs : MainAPI() {
    override var mainUrl = "https://blowjobs.pro"
    override var name = "Blowjobs"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        mainUrl to "Latest Videos"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "$mainUrl/latest-updates/$page/"
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
        val link = this.selectFirst("a[href*=\"/videos/\"]") ?: this.selectFirst("a.popup-video-link") ?: return null
        val href = fixUrl(link.attr("href"))
        if (href.isNullOrEmpty()) return null

        val title = this.selectFirst("strong.title")?.text()?.trim()?.ifEmpty { null }
            ?: link.attr("title").ifEmpty { null }
            ?: this.selectFirst("img")?.attr("alt")?.ifEmpty { null }
            ?: return null

        val img = this.selectFirst("img.thumb.lazy-load")
            ?: this.selectFirst("img")
        val posterUrl = fixUrlNull(img?.attr("data-original") ?: img?.attr("src"))
        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        var page = 1
        while (true) {
            val url = if (page == 1) "$mainUrl/search/$query/" else "$mainUrl/search/$query/$page/"
            val document = app.get(url).document
            val items = document.select("div#list_videos_most_recent_videos_items div.item").mapNotNull {
                it.toSearchResult()
            }
            if (items.isEmpty()) break
            results.addAll(items)
            page++
        }
        return results
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
        val regex = Regex("""https?://[^"']+get_file[^"']+\.mp4""")
        val links = regex.findAll(docText).map { it.value }.toSet()

        if (links.isNotEmpty()) {
            for (link in links) {
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = this.name,
                        url = link
                    ) {
                        this.referer = mainUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
            return true
        }

        val videoSources = document.select("video source[src]")
        for (source in videoSources) {
            val src = source.attr("src")
            if (src.isNotEmpty()) {
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = this.name,
                        url = src
                    ) {
                        this.referer = mainUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        }

        if (videoSources.isNotEmpty()) return true

        val fallbackRegex = Regex("""https?://[^"']+get_file[^"']+""")
        val fallbackLinks = fallbackRegex.findAll(docText).map { it.value }.toSet()
        for (link in fallbackLinks) {
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = link
                ) {
                    this.referer = mainUrl
                    this.quality = Qualities.Unknown.value
                }
            )
        }
        return true
    }
}
