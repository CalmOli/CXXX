package tester

import java.io.File

object ProviderParser {

    fun discoverProviders(repoRoot: String): List<ProviderConfig> {
        val rootDir = File(repoRoot)
        return rootDir.listFiles { f -> f.isDirectory && f.name != "provider-tester" && f.name != "build" && !f.name.startsWith(".") }
            ?.mapNotNull { dir -> parseProviderFromDir(dir) }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()
    }

    private fun parseProviderFromDir(dir: File): ProviderConfig? {
        val srcDir = File(dir, "src/main/kotlin")
        if (!srcDir.exists()) return null

        val kotlinFiles = srcDir.walkTopDown().filter { it.extension == "kt" }.toList()
        val mainFile = kotlinFiles.find { f ->
            val text = f.readText()
            text.contains(": MainAPI()") || text.contains(":MainAPI()")
        } ?: return null

        val source = mainFile.readText()
        return parseProviderFromSource(source, dir.name, mainFile.absolutePath)
    }

    private fun parseProviderFromSource(source: String, dirName: String, sourceFile: String): ProviderConfig {
        val mainUrl = extractMainUrl(source) ?: "https://example.com"
        val name = extractName(source) ?: dirName
        val lang = Regex("""override\s+var\s+lang\s*=\s*"([^"]+)"""").find(source)?.groupValues?.get(1) ?: "en"

        val mainPageCategories = extractMainPageCategories(source, mainUrl)
        val cardSelectors = extractCardSelectors(source)
        val searchUrl = extractSearchUrlTemplate(source, mainUrl)
        val detailSelectors = extractDetailSelectors(source)
        val linkExtraction = extractLinkExtraction(source)
        val flags = extractFlags(source)

        return ProviderConfig(
            name = name,
            mainUrl = mainUrl,
            lang = lang,
            directory = dirName,
            sourceFile = sourceFile,
            mainPageCategories = mainPageCategories,
            cardSelectors = cardSelectors,
            searchUrlTemplate = searchUrl,
            detailSelectors = detailSelectors,
            linkExtraction = linkExtraction,
            flags = flags
        )
    }

    private fun extractMainUrl(source: String): String? {
        return Regex("""override\s+var\s+mainUrl\s*=\s*"([^"]+)"""").find(source)?.groupValues?.get(1)
    }

    private fun extractName(source: String): String? {
        return Regex("""override\s+var\s+name\s*=\s*"([^"]+)"""").find(source)?.groupValues?.get(1)
    }

    private fun extractMainPageCategories(source: String, mainUrl: String): Map<String, String> {
        val categories = mutableMapOf<String, String>()
        val mainPageOfMatch = Regex("""mainPageOf\s*\(([\s\S]*?)\)""").find(source)
        if (mainPageOfMatch != null) {
            val content = mainPageOfMatch.groupValues[1]
            val pairRegex = Regex(""""([^"]+)"\s+to\s+"([^"]+)"""")
            pairRegex.findAll(content).forEach { m ->
                var key = m.groupValues[1]
                val value = m.groupValues[2]
                key = key.replace("\${mainUrl}", mainUrl)
                key = key.replace("\$mainUrl", mainUrl)
                categories[key] = value
            }
        }
        if (categories.isEmpty()) {
            categories[""] = "Videos"
        }
        return categories
    }

    private fun extractCardSelectors(source: String): CardSelectors {
        val getMainPageBody = extractFunctionBody(source, "getMainPage")

        val container = if (getMainPageBody != null) {
            extractDocumentSelectSelector(getMainPageBody, "document.select")
                ?: extractDocumentSelectSelector(getMainPageBody, "document.selectFirst")
                ?: ""
        } else ""

        val cardFuncBody = extractFunctionBody(source, "toSearchResult")
            ?: extractFunctionBody(source, "toSearchResponse")
            ?: extractFunctionBody(source, "toSearchResult")

        val titleSelector: String
        val hrefSelector: String
        val imgSelector: String

        if (cardFuncBody != null) {
            titleSelector = extractAllSelectSelectors(cardFuncBody, "selectFirst", listOf("text()"))
                ?: extractAllSelectSelectors(cardFuncBody, "select", listOf("text()"))
                ?: ""
            hrefSelector = extractAllSelectSelectors(cardFuncBody, "selectFirst", listOf("href"))
                ?: extractAllSelectSelectors(cardFuncBody, "select", listOf("href"))
                ?: ""
            imgSelector = extractAllSelectSelectors(cardFuncBody, "selectFirst", listOf("img"))
                ?: extractAllSelectSelectors(cardFuncBody, "select", listOf("img"))
                ?: ""
        } else {
            titleSelector = ""
            hrefSelector = ""
            imgSelector = ""
        }

        val imgAttrs = mutableListOf<String>()
        val imgArea = cardFuncBody ?: source
        if (imgArea.contains("data-src")) imgAttrs.add("data-src")
        if (imgArea.contains("data-original")) imgAttrs.add("data-original")
        if (imgArea.contains(".attr(\"src\")") || imgArea.contains("attr(\"src\")")) {
            if (!imgAttrs.contains("src")) imgAttrs.add("src")
        }
        if (imgAttrs.isEmpty()) imgAttrs.addAll(listOf("data-src", "data-original", "src"))

        return CardSelectors(
            container = container,
            title = titleSelector,
            href = hrefSelector,
            img = imgSelector,
            imgAttrs = imgAttrs
        )
    }

    private fun extractDocumentSelectSelector(source: String, callPattern: String): String? {
        val regex = Regex("""$callPattern\s*\(\s*"([^"]+)"""")
        return regex.find(source)?.groupValues?.get(1)
    }

    private fun extractChainedSelectSelector(source: String, selectMethod: String, keywords: List<String>): String? {
        val regex = Regex("""\.$selectMethod\s*(?:First)?\s*\(\s*"([^"]+)"""")
        regex.findAll(source).forEach { match ->
            val sel = match.groupValues[1].lowercase()
            if (keywords.any { sel.contains(it.lowercase()) }) {
                return match.groupValues[1]
            }
        }
        return null
    }

    private fun extractAllSelectSelectors(source: String, selectMethod: String, contextHints: List<String>): String? {
        val regex = Regex("""\.$selectMethod\s*(?:First)?\s*\(\s*"([^"]+)"\s*\)""")
        regex.findAll(source).forEach { match ->
            val sel = match.groupValues[1]
            val afterMatch = source.substring(match.range.last + 1, minOf(match.range.last + 80, source.length))
            if (contextHints.any { hint ->
                if (hint == "text()") afterMatch.contains(".text()")
                else if (hint == "href") afterMatch.contains("attr(\"href\")") || afterMatch.contains(".attr(\"href\")")
                else sel.lowercase().contains(hint.lowercase())
            }) {
                return sel
            }
        }
        return null
    }

    private fun extractSearchUrlTemplate(source: String, mainUrl: String): String {
        val searchBody = extractFunctionBody(source, "search") ?: return ""

        var url: String? = null

        val appGetMatch = Regex("""app\.get\s*\(\s*["`]([^"`]+)["`]""").find(searchBody)
        if (appGetMatch != null) {
            url = appGetMatch.groupValues[1]
        }

        if (url == null) {
            val urlAssignment = Regex("""val\s+\w+\s*=\s*(?:if\s*\([^)]*\)\s*)?"([^"]*(?:search|query|\?q=)[^"]*)"""").find(searchBody)
            if (urlAssignment != null) {
                url = urlAssignment.groupValues[1]
            }
        }

        if (url == null) {
            val anySearchUrl = Regex(""""([^"]*(?:/search/|\?q=|\?s=)[^"]*)"""").find(searchBody)
            if (anySearchUrl != null) {
                url = anySearchUrl.groupValues[1]
            }
        }

        if (url == null) return "$mainUrl/?q={query}"

        url = url.replace("\$mainUrl", mainUrl)
        url = url.replace("\${mainUrl}", mainUrl)
        url = url.replace(Regex("""\$\{[^}]*(?:query|search|subquery|q)[^}]*\}"""), "{query}")
        url = url.replace(Regex("""\$\{[^}]*(?:page|pg|num|i)[^}]*\}"""), "{page}")
        url = url.replace(Regex("""\$[a-zA-Z_]*(?:query|Query|search|Search|subquery)[a-zA-Z_0-9]*"""), "{query}")
        url = url.replace(Regex("""\$[a-zA-Z_]*(?:page|Page|pg|Pg)[a-zA-Z_0-9]*"""), "{page}")
        url = url.replace(Regex("""\${'$'}i(?!\w)"""), "{page}")
        url = url.replace(Regex("""\$\{[^}]+\}"""), "{param}")
        return url
    }

    private fun extractDetailSelectors(source: String): DetailSelectors {
        val loadBody = extractFunctionBody(source, "load") ?: return DetailSelectors()

        val titleSelector = extractSelectorFromContext(loadBody, listOf("og:title", "title", "h1", "h2"))
        val posterSelector = extractSelectorFromContext(loadBody, listOf("og:image", "poster", "thumbnail"))
        val descSelector = extractSelectorFromContext(loadBody, listOf("og:description", "description", "plot"))
        val tagsSelector = extractSelectorFromContext(loadBody, listOf("tag", "keyword", "categor"))

        return DetailSelectors(
            title = titleSelector ?: "",
            poster = posterSelector ?: "",
            description = descSelector ?: "",
            tags = tagsSelector ?: ""
        )
    }

    private fun extractSelectorFromContext(source: String, keywords: List<String>): String? {
        val regex = Regex("""["']([^"']+)["']""")
        regex.findAll(source).forEach { match ->
            val candidate = match.groupValues[1]
            val lower = candidate.lowercase()
            if (keywords.any { lower.contains(it.lowercase()) } &&
                !lower.contains("http") && !lower.contains("{") &&
                candidate.length <= 80 &&
                isValidCssSelector(candidate)) {
                return candidate
            }
        }
        return null
    }

    private fun isValidCssSelector(selector: String): Boolean {
        if (selector.isBlank()) return false
        val invalidChars = Regex("""[()?!;{}\n\r\\]""")
        if (invalidChars.containsMatchIn(selector)) return false
        if (selector.contains("->") || selector.contains("=>") || selector.contains("::") && selector.length > 30) return false
        if (selector.count { it == ' ' } > 8) return false
        val validPattern = Regex("""^[a-zA-Z0-9\s.#\[\]=:\-_>+~*,/@'"^$|]+$""")
        return validPattern.matches(selector)
    }

    private fun extractLinkExtraction(source: String): LinkExtraction {
        val loadLinksBody = extractFunctionBody(source, "loadLinks") ?: return LinkExtraction()

        val regexPatterns = mutableListOf<String>()
        Regex("""Regex\s*\(\s*"((?:[^"\\]|\\.)*)"""").findAll(loadLinksBody).forEach { m ->
            regexPatterns.add(m.groupValues[1])
        }
        Regex("""Regex\s*\(\s*\"\"\"((?:[^"\\]|\\.)*?)\"\"\"""").findAll(loadLinksBody).forEach { m ->
            regexPatterns.add(m.groupValues[1])
        }

        val scriptSelectors = mutableListOf<String>()
        Regex("""select\s*\(\s*"([^"]*script[^"]*)"""").findAll(loadLinksBody).forEach { m ->
            scriptSelectors.add(m.groupValues[1])
        }
        Regex("""selectXpath\s*\(\s*"([^"]*script[^"]*)"""").findAll(loadLinksBody).forEach { m ->
            scriptSelectors.add(m.groupValues[1])
        }

        return LinkExtraction(
            videoUrlRegex = regexPatterns.firstOrNull() ?: "",
            scriptSelectors = scriptSelectors
        )
    }

    private fun extractFlags(source: String): Flags {
        return Flags(
            needsCloudflare = source.contains("CloudflareKiller"),
            needsJsExecution = source.contains("Rhino") || source.contains("evaluateJavascript"),
            usesJsonApi = source.contains("app.post") && extractFunctionBody(source, "search")?.contains("app.post") == true,
            usesPostSearch = extractFunctionBody(source, "search")?.contains("app.post") == true
        )
    }

    private fun extractFunctionBody(source: String, functionName: String): String? {
        val patterns = listOf(
            """(?:override\s+)?(?:suspend\s+)?fun\s+$functionName\s*\(""",
            """(?:override\s+)?(?:suspend\s+)?fun\s+\w+\.$functionName\s*\("""
        )
        for (pattern in patterns) {
            val startMatch = Regex(pattern).find(source) ?: continue
            var depth = 0
            var started = false
            val startIdx = startMatch.range.first
            for (i in startIdx until source.length) {
                when (source[i]) {
                    '{' -> { depth++; started = true }
                    '}' -> {
                        depth--
                        if (started && depth == 0) {
                            return source.substring(startIdx, i + 1)
                        }
                    }
                }
            }
        }
        return null
    }
}
