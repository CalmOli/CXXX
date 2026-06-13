package tester

data class ProviderConfig(
    val name: String,
    val mainUrl: String,
    val lang: String = "en",
    val directory: String,
    val sourceFile: String,
    val mainPageCategories: Map<String, String> = emptyMap(),
    val cardSelectors: CardSelectors = CardSelectors(),
    val searchUrlTemplate: String = "",
    val detailSelectors: DetailSelectors = DetailSelectors(),
    val linkExtraction: LinkExtraction = LinkExtraction(),
    val flags: Flags = Flags()
)

data class CardSelectors(
    val container: String = "",
    val title: String = "",
    val href: String = "",
    val img: String = "",
    val imgAttrs: List<String> = listOf("data-src", "data-original", "src")
)

data class DetailSelectors(
    val title: String = "",
    val poster: String = "",
    val description: String = "",
    val tags: String = ""
)

data class LinkExtraction(
    val videoUrlRegex: String = "",
    val scriptSelectors: List<String> = emptyList(),
    val jsonApiUrl: String = ""
)

data class Flags(
    val needsCloudflare: Boolean = false,
    val needsJsExecution: Boolean = false,
    val usesJsonApi: Boolean = false,
    val usesPostSearch: Boolean = false
)

data class VideoItem(
    val title: String,
    val url: String,
    val posterUrl: String? = null
)

data class DetailResult(
    val title: String = "",
    val poster: String? = null,
    val description: String? = null,
    val tags: List<String> = emptyList(),
    val duration: String? = null
)

data class LinkResult(
    val url: String,
    val quality: String = "Unknown",
    val type: String = "MP4"
)

data class TestResult<T>(
    val success: Boolean,
    val items: List<T> = emptyList(),
    val errorMessage: String? = null,
    val durationMs: Long = 0,
    val rawHtmlLength: Int = 0
)
