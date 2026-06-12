package com.Javbangers

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class Javbangers : MainAPI() {
    override var mainUrl = "https://www.javbangers.com"
    override var name = "Javbangers"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "$mainUrl/?page=" to "Latest Videos"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) mainUrl else "${request.data}$page"
        val document = app.get(url).document
        val home = document.select("div.margin-fix div.item").mapNotNull {
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
        val linkEl = selectFirst("a[href*=\"/video/\"]") ?: return null
        val href = fixUrl(linkEl.attr("href"))
        val title = selectFirst("strong.title")?.text()?.ifEmpty { null }
            ?: linkEl.attr("title").ifEmpty { null }
            ?: selectFirst("img")?.attr("alt")?.ifEmpty { null }
            ?: return null
        val img = selectFirst("img.thumb.lazy-load")
        val posterUrl = fixUrlNull(
            img?.attr("data-original")
                ?: img?.attr("src")
        )
        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/search/?q=$query").document
        return document.select("div.margin-fix div.item").mapNotNull {
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
        val regex = Regex("""https?://[^"']+get_file[^"']+\.mp4""")
        val videoId = data.substringAfterLast("/").substringBefore("?")

        val links = regex.findAll(docText).map { it.value }.filter {
            it.contains(videoId)
        }.toList().ifEmpty {
            regex.findAll(docText).map { it.value }.toList()
        }

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

        document.select("video source[src]").forEach { source ->
            val src = source.attr("src")
            if (src.isNotBlank()) {
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = fixUrl(src),
                    ) {
                        this.referer = mainUrl
                    }
                )
            }
        }

        return true
    }
}
