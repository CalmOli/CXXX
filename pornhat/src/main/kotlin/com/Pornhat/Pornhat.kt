package com.Pornhat

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class Pornhat : MainAPI() {
    override var mainUrl = "https://www.pornhat.com"
    override var name = "Pornhat"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.NSFW)
    override val vpnStatus = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Fresh Videos",
        "$mainUrl/popular/" to "Popular",
        "$mainUrl/trending/" to "Trending",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else request.data.trimEnd('/') + "/$page/"
        val document = app.get(url).document
        val home = document.select("div.item.thumb-bl.thumb-bl-video").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = true
            ),
            hasNext = home.isNotEmpty()
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val link = this.selectFirst("a") ?: return null
        val title = link.attr("title").ifEmpty { return null }
        val href = fixUrl(link.attr("href"))
        val img = this.selectFirst("img")
        val posterUrl = fixUrlNull(img?.attr("data-original"))
        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResponse = mutableListOf<SearchResponse>()
        for (i in 1..5) {
            val url = if (i == 1) "$mainUrl/search/?q=$query" else "$mainUrl/search/$i/?q=$query"
            val document = app.get(url).document
            val results = document.select("div.item.thumb-bl.thumb-bl-video").mapNotNull {
                it.toSearchResult()
            }
            searchResponse.addAll(results)
            if (results.isEmpty()) break
        }
        return searchResponse.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1")?.text()?.trim()?.ifEmpty { null }
            ?: Regex("^HD ▶️ video (.*) - PornHat$").find(document.selectFirst("title")?.text()?.trim() ?: "")
                ?.groupValues?.getOrNull(1)
                ?.trim()
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

        val videoSources = mutableListOf<Pair<String, Int>>()

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
                videoSources.add(Pair(fixUrl(src), quality))
            }
        }

        if (videoSources.isEmpty()) {
            val regex = Regex("""https?://[^"']+get_file[^"']+""")
            val videoLinks = regex.findAll(docText).map { it.value }.toList().distinct()
            for (link in videoLinks) {
                val quality = when {
                    link.contains("2160", ignoreCase = true) -> Qualities.P2160.value
                    link.contains("1080", ignoreCase = true) -> Qualities.P1080.value
                    link.contains("720", ignoreCase = true) -> Qualities.P720.value
                    link.contains("480", ignoreCase = true) -> Qualities.P480.value
                    link.contains("360", ignoreCase = true) -> Qualities.P360.value
                    else -> Qualities.Unknown.value
                }
                videoSources.add(Pair(link, quality))
            }
        }

        for ((videoUrl, quality) in videoSources) {
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "$name ${quality}p",
                    url = videoUrl,
                ) {
                    this.referer = mainUrl
                    this.quality = quality
                }
            )
        }

        return true
    }
}
