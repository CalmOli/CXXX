package com.Analdin

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class Analdin : MainAPI() {
    override var mainUrl = "https://www.analdin.com"
    override var name = "Analdin"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        mainUrl to "Latest Videos"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "$mainUrl/latest-updates/$page/"
        val document = app.get(url).document
        val home = document.select("div.list-videos > div.margin-fix div.item").mapNotNull {
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
        val link = this.selectFirst("a.popup-video-link, a[href*=\"/videos/\"]") ?: return null
        val href = fixUrl(link.attr("href"))
        val title = this.selectFirst("strong.title")?.text()?.trim()?.ifEmpty { null }
            ?: link.attr("title").ifEmpty { null }
            ?: this.selectFirst("img")?.attr("alt")?.ifEmpty { null }
            ?: return null
        val img = this.selectFirst("img.thumb.lazy-load")
        val posterUrl = fixUrlNull(
            img?.attr("data-original")
                ?: link.attr("thumb")
                ?: img?.attr("src")
        )
        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/search/$query/").document
        return document.select("div.item").mapNotNull {
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
            val quality = when {
                link.contains("hd.mp4", ignoreCase = true) -> Qualities.P1080.value
                link.contains(".mp4") -> Qualities.P720.value
                else -> Qualities.Unknown.value
            }
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = link,
                ) {
                    this.referer = mainUrl
                    this.quality = quality
                }
            )
        }

        document.select("video source").forEach { source ->
            val src = source.attr("src")
            if (src.isNotEmpty()) {
                val label = source.attr("label")
                val quality = when {
                    "2160" in label -> Qualities.P2160.value
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
                        url = src,
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
