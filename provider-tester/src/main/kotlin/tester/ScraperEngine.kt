package tester

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class ScraperEngine(private val http: HttpClient) {

    fun testMainPage(provider: ProviderConfig): TestResult<VideoItem> {
        val start = System.currentTimeMillis()
        return try {
            val firstCategory = provider.mainPageCategories.keys.first()
            val url = buildMainPageUrl(provider.mainUrl, firstCategory, 1)
            val response = http.get(url, referer = provider.mainUrl)

            if (response.code != 200) {
                return TestResult(false, errorMessage = "HTTP ${response.code}", durationMs = System.currentTimeMillis() - start, rawHtmlLength = response.body.length)
            }

            val doc = Jsoup.parse(response.body, url)
            val items = extractVideoCards(doc, provider)
            TestResult(
                success = items.isNotEmpty(),
                items = items,
                errorMessage = if (items.isEmpty()) "No video items found with extracted selectors" else null,
                durationMs = System.currentTimeMillis() - start,
                rawHtmlLength = response.body.length
            )
        } catch (e: Exception) {
            TestResult(false, errorMessage = "${e.javaClass.simpleName}: ${e.message}", durationMs = System.currentTimeMillis() - start)
        }
    }

    fun testSearch(provider: ProviderConfig, query: String): TestResult<VideoItem> {
        val start = System.currentTimeMillis()
        return try {
            val url = buildSearchUrl(provider, query, 1)
            val response = if (provider.flags.usesPostSearch) {
                http.post(url, formData = mapOf("q" to query), referer = provider.mainUrl)
            } else {
                http.get(url, referer = provider.mainUrl)
            }

            if (response.code != 200) {
                return TestResult(false, errorMessage = "HTTP ${response.code}", durationMs = System.currentTimeMillis() - start, rawHtmlLength = response.body.length)
            }

            val doc = Jsoup.parse(response.body, url)
            val items = extractVideoCards(doc, provider)
            TestResult(
                success = items.isNotEmpty(),
                items = items,
                errorMessage = if (items.isEmpty()) "No search results found for '$query'" else null,
                durationMs = System.currentTimeMillis() - start,
                rawHtmlLength = response.body.length
            )
        } catch (e: Exception) {
            TestResult(false, errorMessage = "${e.javaClass.simpleName}: ${e.message}", durationMs = System.currentTimeMillis() - start)
        }
    }

    fun testLoad(provider: ProviderConfig, url: String? = null): TestResult<DetailResult> {
        val start = System.currentTimeMillis()
        return try {
            val targetUrl = url ?: getFirstVideoUrl(provider)
            if (targetUrl == null) {
                return TestResult(false, errorMessage = "Could not determine a video URL to test (mainPage returned no items)", durationMs = System.currentTimeMillis() - start)
            }

            val response = http.get(targetUrl, referer = provider.mainUrl)
            if (response.code != 200) {
                return TestResult(false, errorMessage = "HTTP ${response.code}", durationMs = System.currentTimeMillis() - start, rawHtmlLength = response.body.length)
            }

            val doc = Jsoup.parse(response.body, targetUrl)
            val detail = extractDetail(doc, provider)
            TestResult(
                success = detail.title.isNotBlank(),
                items = listOf(detail),
                errorMessage = if (detail.title.isBlank()) "Could not extract title" else null,
                durationMs = System.currentTimeMillis() - start,
                rawHtmlLength = response.body.length
            )
        } catch (e: Exception) {
            TestResult(false, errorMessage = "${e.javaClass.simpleName}: ${e.message}", durationMs = System.currentTimeMillis() - start)
        }
    }

    fun testLoadLinks(provider: ProviderConfig, url: String? = null): TestResult<LinkResult> {
        val start = System.currentTimeMillis()
        return try {
            val targetUrl = url ?: getFirstVideoUrl(provider)
            if (targetUrl == null) {
                return TestResult(false, errorMessage = "Could not determine a video URL to test", durationMs = System.currentTimeMillis() - start)
            }

            val response = http.get(targetUrl, referer = provider.mainUrl)
            if (response.code != 200) {
                return TestResult(false, errorMessage = "HTTP ${response.code}", durationMs = System.currentTimeMillis() - start, rawHtmlLength = response.body.length)
            }

            val doc = Jsoup.parse(response.body, targetUrl)
            val links = extractLinks(doc, response.body, provider)
            TestResult(
                success = links.isNotEmpty(),
                items = links,
                errorMessage = if (links.isEmpty()) "No video links extracted" else null,
                durationMs = System.currentTimeMillis() - start,
                rawHtmlLength = response.body.length
            )
        } catch (e: Exception) {
            TestResult(false, errorMessage = "${e.javaClass.simpleName}: ${e.message}", durationMs = System.currentTimeMillis() - start)
        }
    }

    private fun getFirstVideoUrl(provider: ProviderConfig): String? {
        val mainPageResult = testMainPage(provider)
        return mainPageResult.items.firstOrNull()?.url
    }

    fun debugMainPage(provider: ProviderConfig): String {
        val sb = StringBuilder()
        val firstCategory = provider.mainPageCategories.keys.first()
        val url = buildMainPageUrl(provider.mainUrl, firstCategory, 1)
        sb.appendLine("URL: $url")
        sb.appendLine("Category: ${provider.mainPageCategories.values.first()}")

        val response = http.get(url, referer = provider.mainUrl)
        sb.appendLine("HTTP code: ${response.code}")
        sb.appendLine("Body length: ${response.body.length}")

        if (response.body.length < 100) {
            sb.appendLine("Body (first 500 chars): ${response.body.take(500)}")
            return sb.toString()
        }

        val doc = Jsoup.parse(response.body, url)
        sb.appendLine("Document title: ${doc.title()}")

        val container = provider.cardSelectors.container
        if (container.isNotBlank()) {
            val cards = doc.select(container)
            sb.appendLine("Container '$container': ${cards.size} elements")
            if (cards.isNotEmpty()) {
                val firstCard = cards.first()!!
                sb.appendLine("  First card HTML (200 chars): ${firstCard.outerHtml().take(200)}")
                val titleSel = provider.cardSelectors.title
                if (titleSel.isNotBlank()) {
                    val titleEl = firstCard.selectFirst(titleSel)
                    sb.appendLine("  Title selector '$titleSel': ${titleEl?.text()?.take(60) ?: "NOT FOUND"}")
                }
                val hrefSel = provider.cardSelectors.href
                if (hrefSel.isNotBlank()) {
                    val hrefEl = firstCard.selectFirst(hrefSel)
                    sb.appendLine("  Href selector '$hrefSel': ${hrefEl?.attr("href")?.take(80) ?: "NOT FOUND"}")
                }
                val imgSel = provider.cardSelectors.img
                if (imgSel.isNotBlank()) {
                    val imgEl = firstCard.selectFirst(imgSel)
                    sb.appendLine("  Img selector '$imgSel': ${if (imgEl != null) "found" else "NOT FOUND"}")
                    if (imgEl != null) {
                        provider.cardSelectors.imgAttrs.forEach { attr ->
                            sb.appendLine("    $attr: ${imgEl.attr(attr).take(80).ifBlank { "(empty)" }}")
                        }
                    }
                }
            }
        }

        sb.appendLine("\nHeuristic selectors tried:")
        listOf("div.video-item", "article.item", "div.thumb-block", "div.js-video-item", "div.mb", "section", "div.video-card", "div.item").forEach { sel ->
            val count = doc.select(sel).size
            if (count > 0) sb.appendLine("  '$sel': $count elements")
        }

        return sb.toString()
    }

    private fun buildMainPageUrl(mainUrl: String, categoryPath: String, page: Int): String {
        val base = mainUrl.trimEnd('/')
        if (categoryPath.isBlank()) {
            return if (page <= 1) "$base/" else "$base/page/$page/"
        }
        if (categoryPath.startsWith("http")) {
            return if (page <= 1) categoryPath else "${categoryPath.trimEnd('/')}/$page/"
        }
        val path = categoryPath.trim('/')
        return if (page <= 1) "$base/$path/" else "$base/$path/$page/"
    }

    private fun buildSearchUrl(provider: ProviderConfig, query: String, page: Int): String {
        var url = provider.searchUrlTemplate
        if (url.isBlank()) {
            return "${provider.mainUrl}/?q=$query"
        }
        url = url.replace("{query}", query.replace(" ", "+"))
        url = url.replace("{page}", page.toString())
        if (!url.startsWith("http")) {
            url = "${provider.mainUrl.trimEnd('/')}/$url"
        }
        return url
    }

    private fun extractVideoCards(doc: Document, provider: ProviderConfig): List<VideoItem> {
        val items = mutableListOf<VideoItem>()

        if (provider.cardSelectors.container.isNotBlank()) {
            val cards = doc.select(provider.cardSelectors.container)
            if (cards.isNotEmpty()) {
                cards.forEach { card ->
                    val item = extractItemFromCard(card, provider)
                    if (item != null) items.add(item)
                }
                if (items.isNotEmpty()) return items
            }
        }

        return extractVideoCardsHeuristic(doc, provider.mainUrl)
    }

    private fun extractItemFromCard(card: Element, provider: ProviderConfig): VideoItem? {
        val title = if (provider.cardSelectors.title.isNotBlank()) {
            card.selectFirst(provider.cardSelectors.title)?.text()?.trim()
        } else {
            card.selectFirst("a")?.attr("title")?.ifBlank { card.selectFirst("a")?.text() }?.trim()
                ?: card.selectFirst("h2, h3, p, span")?.text()?.trim()
        } ?: return null

        if (title.isBlank() || title.length < 3) return null

        val href = if (provider.cardSelectors.href.isNotBlank()) {
            card.selectFirst(provider.cardSelectors.href)?.attr("href")
        } else {
            card.selectFirst("a[href]")?.attr("href")
        } ?: return null

        val fullUrl = resolveUrl(href, provider.mainUrl)

        val posterUrl = if (provider.cardSelectors.img.isNotBlank()) {
            val imgEl = card.selectFirst(provider.cardSelectors.img)
            provider.cardSelectors.imgAttrs.firstNotNullOfOrNull { attr ->
                imgEl?.attr(attr)?.takeIf { it.isNotBlank() && !it.contains("blank.gif") && !it.contains("placeholder") }
            }
        } else {
            card.selectFirst("img")?.let { img ->
                listOf("data-src", "data-original", "src").firstNotNullOfOrNull { attr ->
                    img.attr(attr).takeIf { it.isNotBlank() && !it.contains("blank.gif") && !it.contains("placeholder") }
                }
            }
        }

        return VideoItem(
            title = title.take(80),
            url = fullUrl,
            posterUrl = posterUrl?.let { resolveUrl(it, provider.mainUrl) }
        )
    }

    private fun extractVideoCardsHeuristic(doc: Document, mainUrl: String): List<VideoItem> {
        val items = mutableListOf<VideoItem>()
        val selectorStrategies = listOf(
            "div.video-item", "article.item", "div.thumb-block",
            "div.js-video-item", "div.mb", "section", "div.video-card",
            "div.item", "li.video", "div.video"
        )

        for (selector in selectorStrategies) {
            val cards = doc.select(selector)
            if (cards.size >= 3) {
                cards.forEach { card ->
                    val link = card.selectFirst("a[href]") ?: return@forEach
                    val title = link.attr("title").ifBlank {
                        card.selectFirst("h2, h3, p.title, p.inf a, span.title")?.text()
                            ?: link.text()
                    }.trim()
                    if (title.isBlank() || title.length < 3) return@forEach

                    val href = link.attr("href")
                    val posterUrl = card.selectFirst("img")?.let { img ->
                        listOf("data-src", "data-original", "src").firstNotNullOfOrNull { attr ->
                            img.attr(attr).takeIf { it.isNotBlank() && !it.contains("blank.gif") }
                        }
                    }

                    items.add(VideoItem(
                        title = title.take(80),
                        url = resolveUrl(href, mainUrl),
                        posterUrl = posterUrl?.let { resolveUrl(it, mainUrl) }
                    ))
                }
                if (items.isNotEmpty()) break
            }
        }
        return items
    }

    private fun extractDetail(doc: Document, provider: ProviderConfig): DetailResult {
        val title = extractTextBySelector(doc, provider.detailSelectors.title)
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?: doc.selectFirst("title")?.text()
            ?: doc.selectFirst("h1")?.text()
            ?: ""

        val poster = extractAttrBySelector(doc, provider.detailSelectors.poster, "content")
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst("meta[name=twitter:image]")?.attr("content")

        val description = extractTextBySelector(doc, provider.detailSelectors.description)
            ?: doc.selectFirst("meta[property=og:description]")?.attr("content")
            ?: doc.selectFirst("meta[name=description]")?.attr("content")

        val tags = if (provider.detailSelectors.tags.isNotBlank()) {
            doc.select(provider.detailSelectors.tags).map { it.text().trim() }.filter { it.isNotBlank() }
        } else {
            doc.select("a[href*='tag'], a[href*='category'], a.is-keyword").map { it.text().trim() }.filter { it.isNotBlank() }.take(20)
        }

        return DetailResult(
            title = title.trim().take(120),
            poster = poster?.let { resolveUrl(it, provider.mainUrl) },
            description = description?.trim()?.take(500),
            tags = tags.take(15)
        )
    }

    private fun extractLinks(doc: Document, rawHtml: String, provider: ProviderConfig): List<LinkResult> {
        val links = mutableListOf<LinkResult>()

        if (provider.linkExtraction.videoUrlRegex.isNotBlank()) {
            try {
                val pattern = Regex(provider.linkExtraction.videoUrlRegex)
                pattern.findAll(rawHtml).forEach { match ->
                    val url = match.groupValues.getOrElse(1) { match.value }.trim('"', '\'')
                    val resolvedUrl = resolveUrl(url.replace("\\/", "/"), provider.mainUrl)
                    if (resolvedUrl.startsWith("http") && links.none { it.url == resolvedUrl }) {
                        links.add(LinkResult(url = resolvedUrl, quality = guessQuality(resolvedUrl)))
                    }
                }
            } catch (_: Exception) {}
        }

        val videoUrlRegex = Regex("""['"]?(https?://[^'"\s<>]+\.(?:mp4|m3u8|webm)(?:\?[^'"\s<>]*)?)['"]?""")
        videoUrlRegex.findAll(rawHtml).forEach { match ->
            val url = match.groupValues[1]
            if (links.none { it.url == url }) {
                links.add(LinkResult(url = url, quality = guessQuality(url), type = guessType(url)))
            }
        }

        val scriptPatterns = listOf(
            Regex(""""contentUrl"\s*:\s*"([^"]+)""""),
            Regex("""video_url\s*[:=]\s*['"]([^'"]+)['"]"""),
            Regex("""file\s*[:=]\s*['"]([^'"]+\.(?:mp4|m3u8)[^'"]*)['"]"""),
            Regex("""src\s*[:=]\s*['"]([^'"]+\.(?:mp4|m3u8)[^'"]*)['"]""")
        )
        scriptPatterns.forEach { pattern ->
            pattern.findAll(rawHtml).forEach { match ->
                val url = match.groupValues[1].replace("\\/", "/")
                val resolvedUrl = resolveUrl(url, provider.mainUrl)
                if (resolvedUrl.startsWith("http") && !resolvedUrl.contains("_preview") && !resolvedUrl.contains("_trailer") && !resolvedUrl.contains(".jpg") && links.none { it.url == resolvedUrl }) {
                    links.add(LinkResult(url = resolvedUrl, quality = guessQuality(resolvedUrl), type = guessType(resolvedUrl)))
                }
            }
        }

        val broadVideoPatterns = listOf(
            Regex("""https?://[^"'\s<>]+get_file/[^"'\s<>]*\.mp4[^"'\s<>]*"""),
            Regex("""https?://[^"'\s<>]+\.(?:bkcdn|bxcdn|cdn\d+)[^"'\s<>]*\.mp4[^"'\s<>]*""")
        )
        broadVideoPatterns.forEach { pattern ->
            pattern.findAll(rawHtml).forEach { match ->
                val url = match.value.replace("\\/", "/")
                if (!url.contains("_preview") && !url.contains("_trailer") && !url.contains("_vthumb") && !url.contains(".jpg") && links.none { it.url == url }) {
                    links.add(LinkResult(url = url, quality = guessQuality(url), type = guessType(url)))
                }
            }
        }

        doc.select("video source[src], video[src]").forEach { el ->
            val src = el.attr("src")
            if (src.isNotBlank()) {
                val resolvedUrl = resolveUrl(src, provider.mainUrl)
                if (links.none { it.url == resolvedUrl }) {
                    val label = el.attr("label").ifBlank { el.attr("title") }
                    links.add(LinkResult(
                        url = resolvedUrl,
                        quality = if (label.isNotBlank()) guessQuality(label) else guessQuality(src),
                        type = guessType(src)
                    ))
                }
            }
        }

        doc.select("source[src]").forEach { source ->
            val src = source.attr("src")
            if (src.isNotBlank() && (src.contains(".mp4") || src.contains(".m3u8") || src.contains(".webm") || src.startsWith("//"))) {
                val resolvedUrl = resolveUrl(src, provider.mainUrl)
                if (links.none { it.url == resolvedUrl }) {
                    val label = source.attr("label").ifBlank { source.attr("title").ifBlank { source.attr("res") } }
                    links.add(LinkResult(
                        url = resolvedUrl,
                        quality = if (label.isNotBlank()) guessQuality(label) else guessQuality(src),
                        type = guessType(src)
                    ))
                }
            }
        }

        return links
    }

    private fun extractTextBySelector(doc: Document, selector: String): String? {
        if (selector.isBlank()) return null
        val el = doc.selectFirst(selector) ?: return null
        return when {
            selector.contains("meta") -> el.attr("content").ifBlank { null }?.trim()
            else -> el.text().trim().ifBlank { null }
        }
    }

    private fun extractAttrBySelector(doc: Document, selector: String, attr: String): String? {
        if (selector.isBlank()) return null
        return doc.selectFirst(selector)?.attr(attr)?.ifBlank { null }
    }

    private fun resolveUrl(url: String, baseUrl: String): String {
        if (url.startsWith("http")) return url
        if (url.startsWith("//")) return "https:$url"
        val base = baseUrl.trimEnd('/')
        return if (url.startsWith("/")) "$base$url" else "$base/$url"
    }

    private fun isVideoUrl(url: String): Boolean {
        return url.contains(".mp4") || url.contains(".m3u8") || url.contains(".webm") ||
               url.contains("video") || url.contains("stream")
    }

    private fun guessQuality(url: String): String {
        val qualityRegex = Regex("""(\d{3,4})[pP]""")
        return qualityRegex.find(url)?.groupValues?.get(1)?.let { "${it}p" } ?: "Unknown"
    }

    private fun guessType(url: String): String {
        return when {
            url.contains(".m3u8") -> "M3U8"
            url.contains(".webm") -> "WEBM"
            else -> "MP4"
        }
    }
}
