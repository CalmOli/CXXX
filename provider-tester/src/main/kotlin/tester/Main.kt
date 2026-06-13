package tester

import java.io.File

private object Colors {
    val RESET  = "\u001b[0m"
    val BOLD   = "\u001b[1m"
    val RED    = "\u001b[31m"
    val GREEN  = "\u001b[32m"
    val YELLOW = "\u001b[33m"
    val BLUE   = "\u001b[34m"
    val CYAN   = "\u001b[36m"
    val GRAY   = "\u001b[90m"
    val MAGENTA = "\u001b[35m"
    val WHITE  = "\u001b[37m"

    fun green(text: String)  = "$GREEN$text$RESET"
    fun red(text: String)    = "$RED$text$RESET"
    fun yellow(text: String) = "$YELLOW$text$RESET"
    fun blue(text: String)   = "$BLUE$text$RESET"
    fun cyan(text: String)   = "$CYAN$text$RESET"
    fun gray(text: String)   = "$GRAY$text$RESET"
    fun bold(text: String)   = "$BOLD$text$RESET"
    fun magenta(text: String)= "$MAGENTA$text$RESET"
}

fun main(args: Array<String>) {
    System.setProperty("java.net.preferIPv4Stack", "true")
    System.setProperty("java.net.preferIPv6Addresses", "false")

    if (args.isEmpty()) {
        printUsage()
        return
    }

    val repoRoot = findRepoRoot()
    val providers = ProviderParser.discoverProviders(repoRoot)

    when (args[0]) {
        "list" -> cmdList(providers)
        "debug" -> {
            if (args.size < 2) {
                println(Colors.red("Error: provider name required"))
                return
            }
            val name = args[1]
            val provider = providers.find { it.name.equals(name, ignoreCase = true) || it.directory.equals(name, ignoreCase = true) }
            if (provider == null) {
                println(Colors.red("Provider '$name' not found."))
                return
            }
            val engine = ScraperEngine(HttpClient())
            println(Colors.bold("Debug: ${provider.name}"))
            println(Colors.gray("─".repeat(60)))
            println(engine.debugMainPage(provider))
        }
        "test" -> {
            if (args.size < 2) {
                println(Colors.red("Error: provider name required"))
                println("Usage: provider-tester test <name> [mainPage|search <query>|load [url]|loadLinks [url]]")
                return
            }
            val name = args[1]
            val provider = providers.find { it.name.equals(name, ignoreCase = true) || it.directory.equals(name, ignoreCase = true) }
            if (provider == null) {
                println(Colors.red("Provider '$name' not found. Use 'list' to see available providers."))
                return
            }
            val operation = if (args.size > 2) args[2] else "all"
            val url = if (args.size > 3) args[3] else null
            cmdTest(provider, operation, url)
        }
        "search" -> {
            if (args.size < 3) {
                println(Colors.red("Error: provider name and query required"))
                println("Usage: provider-tester search <name> <query>")
                return
            }
            val name = args[1]
            val query = args.drop(2).joinToString(" ")
            val provider = providers.find { it.name.equals(name, ignoreCase = true) || it.directory.equals(name, ignoreCase = true) }
            if (provider == null) {
                println(Colors.red("Provider '$name' not found."))
                return
            }
            val engine = ScraperEngine(HttpClient())
            val result = engine.testSearch(provider, query)
            printSearchResult(provider, query, result)
        }
        "info" -> {
            if (args.size < 2) {
                println(Colors.red("Error: provider name required"))
                return
            }
            val name = args[1]
            val provider = providers.find { it.name.equals(name, ignoreCase = true) || it.directory.equals(name, ignoreCase = true) }
            if (provider == null) {
                println(Colors.red("Provider '$name' not found."))
                return
            }
            cmdInfo(provider)
        }
        "test-all" -> {
            val providersToTest = if (args.size > 1) {
                args.drop(1).mapNotNull { name ->
                    providers.find { it.name.equals(name, ignoreCase = true) || it.directory.equals(name, ignoreCase = true) }
                }
            } else {
                providers.take(10)
            }
            cmdTestAll(providersToTest)
        }
        else -> {
            println(Colors.red("Unknown command: ${args[0]}"))
            printUsage()
        }
    }
}

private fun printUsage() {
    println(Colors.bold("Provider Scraper Tester"))
    println()
    println("Usage: provider-tester <command> [args...]")
    println()
    println(Colors.bold("Commands:"))
    println("  ${Colors.cyan("list")}                              List all discovered providers")
    println("  ${Colors.cyan("info")} <name>                       Show extracted config for a provider")
    println("  ${Colors.cyan("test")} <name> [op] [url]            Test a provider")
    println("       ops: ${Colors.gray("mainPage, search <q>, load [url], loadLinks [url], all (default)")}")
    println("  ${Colors.cyan("search")} <name> <query>             Search a provider")
    println("  ${Colors.cyan("test-all")} [names...]               Test mainPage for multiple providers")
    println()
    println(Colors.bold("Examples:"))
    println("  provider-tester list")
    println("  provider-tester info Eporner")
    println("  provider-tester test Eporner")
    println("  provider-tester test Eporner search milf")
    println("  provider-tester test Xvideos load https://...")
    println("  provider-tester search spankbang stepmom")
    println("  provider-tester test-all Eporner Porntrex spankbang")
}

private fun cmdList(providers: List<ProviderConfig>) {
    println(Colors.bold("Discovered Providers (${providers.size})"))
    println(Colors.gray("─".repeat(80)))
    printf("%-20s %-25s %-8s %-6s %s%n", Colors.cyan("Name"), Colors.cyan("Directory"), Colors.cyan("Lang"), Colors.cyan("Cats"), Colors.cyan("URL"))
    println(Colors.gray("─".repeat(80)))
    providers.forEach { p ->
        val flags = mutableListOf<String>()
        if (p.flags.needsCloudflare) flags.add("CF")
        if (p.flags.needsJsExecution) flags.add("JS")
        if (p.flags.usesPostSearch) flags.add("POST")
        val flagStr = if (flags.isNotEmpty()) " ${Colors.yellow(flags.joinToString(","))}" else ""
        printf("%-20s %-25s %-8s %-6d %s%s%n",
            Colors.bold(p.name), p.directory, p.lang,
            p.mainPageCategories.size, p.mainUrl, flagStr)
    }
}

private fun cmdInfo(provider: ProviderConfig) {
    println(Colors.bold("Provider: ${provider.name}"))
    println(Colors.gray("─".repeat(60)))
    println("  ${Colors.cyan("URL:")}         ${provider.mainUrl}")
    println("  ${Colors.cyan("Language:")}    ${provider.lang}")
    println("  ${Colors.cyan("Directory:")}   ${provider.directory}")
    println("  ${Colors.cyan("Source:")}      ${provider.sourceFile}")
    println()
    println("  ${Colors.bold("Main Page Categories")} (${provider.mainPageCategories.size}):")
    provider.mainPageCategories.forEach { (url, name) ->
        println("    ${Colors.green(name)}: ${Colors.gray(url.ifBlank { "/" })}")
    }
    println()
    println("  ${Colors.bold("Card Selectors:")}")
    println("    container: ${sel(provider.cardSelectors.container)}")
    println("    title:     ${sel(provider.cardSelectors.title)}")
    println("    href:      ${sel(provider.cardSelectors.href)}")
    println("    img:       ${sel(provider.cardSelectors.img)}")
    println("    imgAttrs:  ${provider.cardSelectors.imgAttrs.joinToString(", ")}")
    println()
    println("  ${Colors.bold("Search URL:")}")
    println("    ${if (provider.searchUrlTemplate.isNotBlank()) provider.searchUrlTemplate else Colors.gray("(not extracted)")}")
    println()
    println("  ${Colors.bold("Detail Selectors:")}")
    println("    title:  ${sel(provider.detailSelectors.title)}")
    println("    poster: ${sel(provider.detailSelectors.poster)}")
    println("    desc:   ${sel(provider.detailSelectors.description)}")
    println("    tags:   ${sel(provider.detailSelectors.tags)}")
    println()
    println("  ${Colors.bold("Link Extraction:")}")
    println("    regex:   ${if (provider.linkExtraction.videoUrlRegex.isNotBlank()) Colors.green(provider.linkExtraction.videoUrlRegex.take(80)) else Colors.gray("(not extracted)")}")
    println("    scripts: ${if (provider.linkExtraction.scriptSelectors.isNotEmpty()) provider.linkExtraction.scriptSelectors.joinToString(", ") else Colors.gray("(none)")}")
    println()
    println("  ${Colors.bold("Flags:")}")
    println("    cloudflare: ${flag(provider.flags.needsCloudflare)}  js: ${flag(provider.flags.needsJsExecution)}  postSearch: ${flag(provider.flags.usesPostSearch)}")
}

private fun sel(s: String): String = if (s.isNotBlank()) Colors.green(s) else Colors.gray("(auto)")
private fun flag(b: Boolean): String = if (b) Colors.yellow("YES") else Colors.gray("no")

private fun cmdTest(provider: ProviderConfig, operation: String, url: String?) {
    val engine = ScraperEngine(HttpClient())
    println(Colors.bold("Testing: ${provider.name}") + Colors.gray(" (${provider.mainUrl})"))
    println(Colors.gray("─".repeat(60)))

    when (operation) {
        "all" -> {
            printSection("1. Main Page")
            val mainResult = engine.testMainPage(provider)
            printVideoItemsResult(mainResult)

            printSection("2. Search")
            val searchResult = engine.testSearch(provider, "amateur")
            printVideoItemsResult(searchResult)

            printSection("3. Load (Detail)")
            val loadUrl = mainResult.items.firstOrNull()?.url ?: url
            val loadResult = engine.testLoad(provider, loadUrl)
            printDetailResult(loadResult)

            printSection("4. Load Links")
            val linkUrl = loadUrl
            val linkResult = engine.testLoadLinks(provider, linkUrl)
            printLinksResult(linkResult)

            printSummary(mapOf(
                "mainPage" to mainResult.success,
                "search" to searchResult.success,
                "load" to loadResult.success,
                "loadLinks" to linkResult.success
            ))
        }
        "mainPage", "main" -> {
            val result = engine.testMainPage(provider)
            printVideoItemsResult(result)
        }
        "search" -> {
            val query = url ?: "amateur"
            val result = engine.testSearch(provider, query)
            printSearchResult(provider, query, result)
        }
        "load" -> {
            val result = engine.testLoad(provider, url)
            printDetailResult(result)
        }
        "loadLinks", "links" -> {
            val result = engine.testLoadLinks(provider, url)
            printLinksResult(result)
        }
        else -> {
            println(Colors.red("Unknown operation: $operation"))
            println("Available: mainPage, search, load, loadLinks, all")
        }
    }
}

private fun cmdTestAll(providers: List<ProviderConfig>) {
    val engine = ScraperEngine(HttpClient())
    println(Colors.bold("Batch Testing ${providers.size} Providers (mainPage)"))
    println(Colors.gray("─".repeat(80)))
    printf("%-20s %-8s %-8s %-10s %s%n", Colors.cyan("Provider"), Colors.cyan("HTTP"), Colors.cyan("Items"), Colors.cyan("Time"), Colors.cyan("Status"))
    println(Colors.gray("─".repeat(80)))

    var passed = 0
    var failed = 0
    providers.forEach { p ->
        print("  %-20s ".format(p.name))
        val result = engine.testMainPage(provider = p)
        val status = if (result.success) { passed++; Colors.green("PASS") } else { failed++; Colors.red("FAIL") }
        val items = if (result.items.isNotEmpty()) Colors.green("${result.items.size}") else Colors.red("0")
        val time = "${result.durationMs}ms"
        val note = result.errorMessage?.take(40)?.let { Colors.gray(it) } ?: ""
        printf("%-8s %-8s %-10s %s%n", "HTTP ${if (result.rawHtmlLength > 0) "200" else "ERR"}", items, time, status)
        if (note.isNotBlank()) println("    $note")
    }
    println(Colors.gray("─".repeat(80)))
    println("Results: ${Colors.green("$passed passed")}, ${Colors.red("$failed failed")} of ${providers.size}")
}

private fun printSection(title: String) {
    println()
    println(Colors.bold(Colors.blue("  $title")))
    println(Colors.gray("  ${"─".repeat(50)}"))
}

private fun printVideoItemsResult(result: TestResult<VideoItem>) {
    printResultMeta(result)
    if (result.items.isNotEmpty()) {
        result.items.take(10).forEachIndexed { i, item ->
            println("    ${Colors.cyan("${i + 1}.")} ${item.title}")
            println("       ${Colors.gray("url:")} ${item.url}")
            if (item.posterUrl != null) println("       ${Colors.gray("img:")} ${item.posterUrl.take(80)}")
        }
        if (result.items.size > 10) println(Colors.gray("    ... and ${result.items.size - 10} more"))
    }
}

private fun printSearchResult(provider: ProviderConfig, query: String, result: TestResult<VideoItem>) {
    println(Colors.gray("  Query: \"$query\""))
    printVideoItemsResult(result)
}

private fun printDetailResult(result: TestResult<DetailResult>) {
    printResultMeta(result)
    result.items.firstOrNull()?.let { d ->
        println("    ${Colors.cyan("Title:")}  ${d.title}")
        if (d.poster != null) println("    ${Colors.cyan("Poster:")} ${d.poster.take(80)}")
        if (d.description != null) println("    ${Colors.cyan("Desc:")}   ${d.description.take(120)}")
        if (d.tags.isNotEmpty()) println("    ${Colors.cyan("Tags:")}   ${d.tags.take(10).joinToString(", ")}")
    }
}

private fun printLinksResult(result: TestResult<LinkResult>) {
    printResultMeta(result)
    result.items.take(10).forEachIndexed { i, link ->
        println("    ${Colors.cyan("${i + 1}.")} [${Colors.yellow(link.quality)}] [${link.type}] ${link.url.take(100)}")
    }
}

private fun <T> printResultMeta(result: TestResult<T>) {
    val status = if (result.success) Colors.green("PASS") else Colors.red("FAIL")
    val count = "${result.items.size} items"
    val time = "${result.durationMs}ms"
    val html = if (result.rawHtmlLength > 0) "${result.rawHtmlLength / 1024}KB" else ""
    println("    $status | $count | $time | $html")
    if (result.errorMessage != null) {
        println("    ${Colors.red("Error: ${result.errorMessage}")}")
    }
}

private fun printSummary(results: Map<String, Boolean>) {
    println()
    println(Colors.gray("─".repeat(60)))
    println(Colors.bold("Summary:"))
    results.forEach { (op, passed) ->
        val status = if (passed) Colors.green("PASS") else Colors.red("FAIL")
        println("  $status  $op")
    }
    val total = results.size
    val passCount = results.values.count { it }
    val color = if (passCount == total) Colors::green else if (passCount > 0) Colors::yellow else Colors::red
    println("  ${color("$passCount/$total passed")}")
}

private fun findRepoRoot(): String {
    val testerDir = File(System.getProperty("user.dir"))
    val parent = testerDir.parentFile
    return if (parent != null && File(parent, "settings.gradle.kts").exists()) {
        parent.absolutePath
    } else {
        testerDir.absolutePath
    }
}

private fun printf(format: String, vararg args: Any?) {
    print(String.format(format, *args))
}
