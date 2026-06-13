package tester

import java.io.File
import java.nio.charset.StandardCharsets

class HttpClient {
    private val cookieFile = File.createTempFile("cookies", ".txt").apply { deleteOnExit() }

    private val defaultHeaders = listOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
        "DNT" to "1",
        "Connection" to "keep-alive",
        "Upgrade-Insecure-Requests" to "1"
    )

    fun get(url: String, referer: String? = null, extraHeaders: Map<String, String> = emptyMap()): HttpResponse {
        return curlExec(url, "GET", null, referer, extraHeaders)
    }

    fun post(url: String, formData: Map<String, String> = emptyMap(), jsonData: String? = null,
             referer: String? = null, extraHeaders: Map<String, String> = emptyMap()): HttpResponse {
        val body = if (jsonData != null) {
            jsonData
        } else {
            formData.entries.joinToString("&") { "${it.key}=${java.net.URLEncoder.encode(it.value, "UTF-8")}" }
        }
        val contentType = if (jsonData != null) "application/json" else "application/x-www-form-urlencoded"
        val headers = extraHeaders + ("Content-Type" to contentType)
        return curlExec(url, "POST", body, referer, headers)
    }

    private fun curlExec(url: String, method: String, body: String?,
                         referer: String?, extraHeaders: Map<String, String>): HttpResponse {
        val outputFile = File.createTempFile("curl_out", ".html").apply { deleteOnExit() }
        val headerFile = File.createTempFile("curl_hdr", ".txt").apply { deleteOnExit() }
        val stderrFile = File.createTempFile("curl_err", ".txt").apply { deleteOnExit() }

        val cmd = mutableListOf("curl", "-s", "-L", "--compressed", "--max-redirs", "10",
            "--connect-timeout", "15", "--max-time", "25",
            "-4",
            "-b", cookieFile.absolutePath,
            "-c", cookieFile.absolutePath,
            "-o", outputFile.absolutePath,
            "-D", headerFile.absolutePath)

        defaultHeaders.forEach { (k, v) -> cmd.addAll(listOf("-H", "$k: $v")) }
        if (referer != null) cmd.addAll(listOf("-H", "Referer: $referer"))
        extraHeaders.forEach { (k, v) -> cmd.addAll(listOf("-H", "$k: $v")) }

        cmd.addAll(listOf("-X", method))
        if (body != null) cmd.addAll(listOf("-d", body))
        cmd.add(url)

        val process = ProcessBuilder(cmd)
            .redirectError(stderrFile)
            .start()
        process.waitFor()

        val responseBody = if (outputFile.exists() && outputFile.length() > 0) {
            outputFile.readText(Charsets.UTF_8)
        } else ""

        var code = 200
        val responseHeaders = mutableMapOf<String, String>()
        if (headerFile.exists()) {
            headerFile.readLines().forEach { line ->
                when {
                    line.startsWith("HTTP/") -> {
                        code = line.split(" ")[1].toIntOrNull() ?: 200
                    }
                    line.contains(":") -> {
                        val parts = line.split(":", limit = 2)
                        if (parts.size == 2) responseHeaders[parts[0].trim()] = parts[1].trim()
                    }
                }
            }
        }

        outputFile.delete()
        headerFile.delete()
        stderrFile.delete()

        return HttpResponse(responseBody, code, responseHeaders)
    }
}

data class HttpResponse(
    val body: String,
    val code: Int,
    val headers: Map<String, String>
)
