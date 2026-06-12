package com.Sexu

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class Sexu : MainAPI() {
    override var mainUrl = "https://sexu.com"
    override var name = "Sexu"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.NSFW)
    override val vpnStatus = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
        mainUrl to "Latest Videos"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document
        val items = document.select("li.grid__item").mapNotNull { item ->
            val titleEl = item.selectFirst("a.item__title") ?: return@mapNotNull null
            val linkEl = item.selectFirst("a.item__main[href]") ?: return@mapNotNull null
            val imgEl = item.selectFirst("img.item__inner[src]") ?: return@mapNotNull null

            val title = titleEl.text().trim()
            val href = fixUrl(linkEl.attr("href"))
            val poster = fixUrlNull(imgEl.attr("src"))

            newMovieSearchResponse(title, href, TvType.NSFW) {
                this.posterUrl = poster
            }
        }
        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = items,
                isHorizontalImages = true
            ),
            hasNext = page < 5
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResponse = mutableListOf<SearchResponse>()
        for (i in 1..5) {
            val url = if (i == 1) "$mainUrl/search/$query/" else "$mainUrl/search/$query/page/$i/"
            val document = try { app.get(url).document } catch (e: Exception) { break }
            val results = document.select("li.grid__item").mapNotNull { item ->
                val titleEl = item.selectFirst("a.item__title") ?: return@mapNotNull null
                val linkEl = item.selectFirst("a.item__main[href]") ?: return@mapNotNull null
                val imgEl = item.selectFirst("img.item__inner[src]")

                val title = titleEl.text().trim()
                val href = fixUrl(linkEl.attr("href"))
                val poster = imgEl?.attr("src")?.let { fixUrlNull(it) }

                newMovieSearchResponse(title, href, TvType.NSFW) {
                    this.posterUrl = poster
                }
            }
            if (results.isEmpty()) break
            searchResponse.addAll(results)
        }
        return searchResponse
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("meta[property=og:title]")?.attr("content")
            ?: document.selectFirst("h1")?.text()?.trim()
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
        document.select("source").forEach { source ->
            val src = source.attr("src")
            if (src.isNotEmpty()) {
                val url = if (src.startsWith("//")) "https:$src" else src
                val label = source.attr("label").ifEmpty {
                    source.attr("title").ifEmpty { "" }
                }
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
                        name = "$name - $label".trimEnd('-', ' ').ifBlank { name },
                        url = url
                    ) {
                        this.referer = mainUrl
                        this.quality = quality
                    }
                )
            }
        }
        document.select("video[src]").forEach { video ->
            val src = video.attr("src")
            if (src.isNotEmpty()) {
                val url = if (src.startsWith("//")) "https:$src" else src
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = url
                    ) {
                        this.referer = mainUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        }
        return true
    }
}
