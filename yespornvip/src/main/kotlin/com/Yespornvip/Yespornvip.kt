package com.Yespornvip

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class Yespornvip : MainAPI() {
    override var mainUrl = "https://yesporn.vip"
    override var name = "Yespornvip"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        mainUrl to "Latest Videos"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "$request.data/page/$page/"
        val document = app.get(url).document
        val home = document.select("article.loop-video.thumb-block").mapNotNull {
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
        val link = selectFirst("a[href]") ?: return null
        val href = fixUrl(link.attr("href"))
        val title = selectFirst("header.entry-header span")?.text()?.trim()
            ?: return null
        val img = selectFirst("img[data-src]") ?: selectFirst("img")
        val posterUrl = fixUrlNull(
            img?.attr("data-src") ?: img?.attr("src")
        )
        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/search/$query/").document
        return document.select("article.loop-video.thumb-block").mapNotNull {
            it.toSearchResult()
        }
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

        val sourceLinks = document.select("video source[src]").map { it.attr("src") }

        val getFileRegex = Regex("""https?://[^"'\s]+get_file[^"'\s]*(?:\.mp4)?[^"'\s]*""")
        val regexLinks = getFileRegex.findAll(docText).map { it.value }.toList()

        val links = (sourceLinks + regexLinks).distinct()

        for (link in links) {
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = link,
                ) {
                    this.referer = mainUrl
                }
            )
        }

        return true
    }
}
