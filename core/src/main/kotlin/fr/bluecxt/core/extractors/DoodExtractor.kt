package fr.bluecxt.core.extractors

import android.util.Log
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import fr.bluecxt.core.model.ExtractedSource
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import kotlin.text.RegexOption

// writed using https://github.com/skoruppa/docchi-players/blob/main/dood.py

class DoodExtractor(private val client: OkHttpClient) {

    suspend fun videosFromUrl(url: String): List<ExtractedSource> {
        val parsedUrl = url.toHttpUrl()
        val videoId = parsedUrl.encodedPath.removeSuffix("/").substringAfterLast("/")
        var host = parsedUrl.host

        val allowedHosts = listOf("doodstream.com", "myvidplay.com", "playmogo.com")
        if (host !in allowedHosts) {
            host = "playmogo.com"
        }

        var webUrl = "https://$host/d/$videoId"
        val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        val headers = Headers.Builder()
            .add("User-Agent", userAgent)
            .add("Referer", "https://$host/")
            .build()

        var response = client.newCall(GET(webUrl, headers)).awaitSuccess()
        val actualUrl = response.request.url.toString()
        var html = response.body.string()

        if (actualUrl != webUrl) {
            host = response.request.url.host
            webUrl = "https://$host/d/$videoId"
        }

        val currentHeaders = headers.newBuilder()
            .set("Referer", webUrl)
            .build()

        if ("Video not found" in html || "Video not found | DoodStream" in html || html.contains("video you are looking for is not found", ignoreCase = true)) {
            throw fr.bluecxt.core.ContentUnavailableException("Doodstream: Video not found")
        }

        // Check for iframe
        val iframeMatch = Regex("""<iframe\s*src="([^"]+)""").find(html)
        if (iframeMatch != null) {
            val src = iframeMatch.groupValues[1]
            if (src == "/e/" || src == "/e") {
                throw fr.bluecxt.core.ContentUnavailableException("Doodstream: Video not found")
            }
            val iframeUrl = webUrl.toHttpUrl().resolve(src)?.toString() ?: throw Exception("Doodstream: Could not resolve iframe URL")
            response = client.newCall(GET(iframeUrl, currentHeaders)).awaitSuccess()
            html = response.body.string()
        } else {
            val embedUrl = webUrl.replace("/d/", "/e/")
            response = client.newCall(GET(embedUrl, currentHeaders)).awaitSuccess()
            html = response.body.string()
        }

        // Extract quality
        val qualityMatch = Regex("""\b(360|480|720|1080|1440|2160)[pP]""").find(html)
        val quality = qualityMatch?.let { "${it.groupValues[1]}p" }

        // Extract token and pass URL
        val mainRegex = Regex(
            """dsplayer\.hotkeys[^']+'([^']+).+?function\s*makePlay.+?return[^?]+([^"]+)""",
            RegexOption.DOT_MATCHES_ALL,
        )
        val match = mainRegex.find(html)
        if (match == null) {
            throw Exception("Doodstream: Could find token in script")
        }

        val passPath = match.groupValues[1]
        val token = match.groupValues[2]
        val passUrl = webUrl.toHttpUrl().resolve(passPath)?.toString() ?: throw Exception("Doodstream: Could not resolve pass URL")

        val baseResponse = client.newCall(GET(passUrl, currentHeaders)).awaitSuccess()
        val baseUrl = baseResponse.body.string().trim()

        val finalUrl = if (baseUrl.contains("cloudflarestorage.")) {
            baseUrl
        } else {
            baseUrl + createHashTable(10) + token + System.currentTimeMillis()
        }

        val videoHeaders = Headers.Builder()
            .set("User-Agent", userAgent)
            .set("Referer", "https://$host/")
            .build()

        return listOf(
            ExtractedSource(
                url = finalUrl,
                quality = quality,
                headers = videoHeaders,
            ),
        )
    }

    private fun createHashTable(length: Int = 10): String {
        val alphabet = ('A'..'Z') + ('a'..'z') + ('0'..'9')
        return buildString {
            repeat(length) {
                append(alphabet.random())
            }
        }
    }
}
