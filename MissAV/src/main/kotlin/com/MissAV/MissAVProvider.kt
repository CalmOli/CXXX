package com.MissAv

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class MissAVProvider : MainAPI() {
    override var mainUrl              = "https://missav.ws"
    override var name                 = "MissAV"
    override val hasMainPage          = true
    override var lang                 = "en"
    override val hasDownloadSupport   = true
    override val hasChromecastSupport = true
    override val supportedTypes       = setOf(TvType.NSFW)
    override val vpnStatus            = VPNStatus.MightBeNeeded
    val subtitleCatUrl = "https://www.subtitlecat.com"

    override val mainPage = mainPageOf(
            "/dm514/en/new" to "Recent Update",
            "/dm588/en/release" to "New Release",
            "/dm291/en/today-hot" to "Most Viewed Today",
            "/dm169/en/weekly-hot" to "Most Viewed by Week",
            "/dm256/en/monthly-hot" to "Most Viewed by Month",
            "/dm97/en/fc2" to "Uncensored FC2 AV",
            "/dm34/en/madou" to "Madou AV",
            "/dm620/en/uncensored-leak" to "Uncensored Leak",
            "/en/klive" to "Korean Live AV"
        )
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
            val document = app.get("$mainUrl${request.data}?page=$page").document
            val responseList  = document.select(".thumbnail").mapNotNull { it.toSearchResult() }
            return newHomePageResponse(HomePageList(request.name, responseList, isHorizontalImages = true),hasNext = true)

    }

    private fun Element.toSearchResult(): SearchResponse {
        val status = this.select(".bg-blue-800").text()
        val title = if(status.isNotBlank()){"[$status] "+ this.select(".text-secondary").text()} else {this.select(".text-secondary").text()}
        val href = this.select(".text-secondary").attr("href")
        val posterUrl = this.selectFirst(".w-full")?.attr("data-src")
        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {

        val searchResponse = mutableListOf<SearchResponse>()

        for (i in 1..7) {
            val document = app.get("$mainUrl/en/search/$query?page=$i").document
            //val document = app.get("${mainUrl}/page/$i/?s=$query").document

            val results = document.select(".thumbnail").mapNotNull { it.toSearchResult() }

            if(results.isNotEmpty())
            {
                for (result in results)
                {
                    if(!searchResponse.contains(result))
                    {
                        searchResponse.add(result)
                    }
                }
            }
            else
            {
                break
            }
            /*if (!searchResponse.containsAll(results)) {
                searchResponse.addAll(results)
            } else {
                break
            }

            if (results.isEmpty()) break*/
        }

        return searchResponse

    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("meta[property=og:title]")?.attr("content")?.trim().toString()
        val poster = fixUrlNull(document.selectFirst("[property='og:image']")?.attr("content"))
        val description = document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val data = app.get(data)
        val doc = data.document

        // Extract video UUID from surrit.com URL in the page
        val uuid = Regex("surrit\\.com/([a-f0-9-]+)/").find(data.text)?.groupValues?.get(1)
        if (uuid != null) {
            val playlistUrl = "https://surrit.com/$uuid/playlist.m3u8"
            try {
                val playlistDoc = app.get(playlistUrl, referer = data.url).text
                val qualities = Regex("(\\d+p)/video\\.m3u8").findAll(playlistDoc)
                    .map { it.groupValues[1] }.toList()
                if (qualities.isEmpty()) {
                    // Fallback: use the playlist URL itself
                    callback.invoke(newExtractorLink(name, name, playlistUrl, ExtractorLinkType.M3U8) {
                        this.referer = data.url
                    })
                } else {
                    qualities.forEach { quality ->
                        val videoUrl = "https://surrit.com/$uuid/$quality/video.m3u8"
                        callback.invoke(newExtractorLink(name, name, videoUrl, ExtractorLinkType.M3U8) {
                            this.referer = data.url
                            this.quality = when (quality) {
                                "360p" -> Qualities.P360.value
                                "480p" -> Qualities.P480.value
                                "720p" -> Qualities.P720.value
                                "1080p" -> Qualities.P1080.value
                                else -> Qualities.Unknown.value
                            }
                        })
                    }
                }
            } catch (e: Exception) {
                // Fallback to getAndUnpack if playlist fetch fails
                getAndUnpack(data.text).let { unpackedText ->
                    Regex("=\\s*'([^']+\\.m3u8[^']*)'").findAll(unpackedText)
                        .map { it.groupValues[1] }.toList().distinct()
                        .forEach { url ->
                            callback.invoke(newExtractorLink(name, name, url, ExtractorLinkType.M3U8) {
                                this.referer = data.url
                            })
                        }
                }
            }
        } else {
            getAndUnpack(data.text).let { unpackedText ->
                Regex("=\\s*'([^']+\\.m3u8[^']*)'").findAll(unpackedText)
                    .map { it.groupValues[1] }.toList().distinct()
                    .forEach { url ->
                        callback.invoke(newExtractorLink(name, name, url, ExtractorLinkType.M3U8) {
                            this.referer = data.url
                        })
                    }
            }
        }

        try {
            val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim().toString()
            val javCode = "([a-zA-Z]+-\\d+)".toRegex().find(title)?.groups?.get(1)?.value
            if(!javCode.isNullOrEmpty())
            {
                val query = "$subtitleCatUrl/index.php?search=$javCode"
                val subDoc = app.get(query, timeout = 15).document
                val subList = subDoc.select("td a")
                for(item in subList)
                {
                    if(item.text().contains(javCode,ignoreCase = true))
                    {
                        val fullUrl = "$subtitleCatUrl/${item.attr("href")}"
                        val pDoc = app.get(fullUrl, timeout = 10).document
                        val sList = pDoc.select(".col-md-6.col-lg-4")
                        for(item in sList)
                        {
                            try {
                                val language = item.select(".sub-single span:nth-child(2)").text()
                                val text = item.select(".sub-single span:nth-child(3) a")
                                if(text != null && text.size > 0 && text[0].text() == "Download")
                                {
                                    val url = "$subtitleCatUrl${text[0].attr("href")}"
                                    subtitleCallback.invoke(
                                        SubtitleFile(
                                            language.replace("\uD83D\uDC4D \uD83D\uDC4E",""),  // Use label for the name
                                            url     // Use extracted URL
                                        )
                                    )
                                }
                            } catch (e: Exception) { }
                        }

                    }
                }

            }
        } catch (e: Exception) { }



        return true
    }
}
