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
        "$mainUrl/" to "Latest Videos",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else request.data
        val document = app.get(url).document
        val home = document.select("a[href*=\"/video/\"]").mapNotNull { a ->
            val href = fixUrl(a.attr("href"))
            val title = a.attr("title").ifEmpty {
                a.selectFirst("img")?.attr("alt") ?: return@mapNotNull null
            }
            val poster = a.selectFirst("img")?.attr("src")
            newMovieSearchResponse(title, href, TvType.NSFW) {
                this.posterUrl = fixUrlNull(poster)
            }
        }.distinctBy { it.url }
        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = true
            ),
            hasNext = true
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResponse = mutableListOf<SearchResponse>()
        for (i in 1..5) {
            val url = "$mainUrl/search?q=$query&page=$i"
            val document = app.get(url).document
            val results = document.select("a[href*=\"/video/\"]").mapNotNull { a ->
                val href = fixUrl(a.attr("href"))
                val title = a.attr("title").ifEmpty {
                    a.selectFirst("img")?.attr("alt") ?: return@mapNotNull null
                }
                val poster = a.selectFirst("img")?.attr("src")
                newMovieSearchResponse(title, href, TvType.NSFW) {
                    this.posterUrl = fixUrlNull(poster)
                }
            }.distinctBy { it.url }
            if (results.isEmpty()) break
            searchResponse.addAll(results)
        }
        return searchResponse.distinctBy { it.url }
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

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val document = app.get(data).document
        val docText = document.toString()
        val found = mutableListOf<Pair<String, Int>>()

        fun addUrl(rawUrl: String, quality: Int = Qualities.Unknown.value) {
            if (rawUrl.isEmpty() || rawUrl.contains(".jpg")) return
            val url = fixUrl(rawUrl)
            if (found.any { it.first == url }) return
            found.add(Pair(url, quality))
        }

        fun inferQuality(text: String): Int = when {
            "2160" in text || "4k" in text -> Qualities.P2160.value
            "1080" in text -> Qualities.P1080.value
            "720" in text -> Qualities.P720.value
            "480" in text -> Qualities.P480.value
            "360" in text -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }

        val videoUrlRegex = Regex("""video_url\s*:\s*['"]([^'"]+)['"]""")
        videoUrlRegex.findAll(docText).forEach {
            val url = it.groupValues[1]
            if (url.isNotEmpty()) addUrl(url)
        }

        document.select("video source[src], source[src]").forEach { source ->
            val src = source.attr("src")
            if (src.isNotEmpty()) {
                val label = source.attr("label")
                addUrl(src, inferQuality(label))
            }
        }

        val getFileRegex = Regex("""https?://[^"'\s]+get_file[^"'\s]*\.mp4[^"'\s]*""")
        getFileRegex.findAll(docText).forEach {
            val url = it.value
            if (!url.contains("_preview") && !url.contains("_vthumb") && !url.contains("_trailer") && !url.contains("screenshots")) {
                addUrl(url, inferQuality(url))
            }
        }

        val cdnRegex = Regex("""https?://[^"'\s<>]+\.(?:bkcdn|bxcdn)[^"'\s<>]*\.mp4[^"'\s<>]*""")
        cdnRegex.findAll(docText).forEach {
            addUrl(it.value)
        }

        val mp4Regex = Regex("""https?://[^"'\s<>]+\.mp4(?!\/[^"'\s<>]*\.(?:jpg|png|gif|webp))[^"'\s<>]*""")
        mp4Regex.findAll(docText).forEach {
            val url = it.value
            if (!url.contains("_preview") && !url.contains("_vthumb") && !url.contains("_trailer") && !url.contains("screenshots")) {
                addUrl(url, inferQuality(url))
            }
        }

        val unique = found.distinctBy { it.first }
        for ((url, quality) in unique) {
            val isM3u8 = url.contains(".m3u8")
            callback.invoke(
                newExtractorLink(source = this.name, name = this.name, url = url, type = if (isM3u8) ExtractorLinkType.M3U8 else null) {
                    this.referer = data
                    this.quality = quality
                }
            )
        }
        return unique.isNotEmpty()
    }
}
