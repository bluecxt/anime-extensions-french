package fr.bluecxt.core.extractors

import android.util.Log
import eu.kanade.tachiyomi.network.GET
import fr.bluecxt.core.DOOD_LOG
import fr.bluecxt.core.model.ExtractedSource
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import kotlin.text.RegexOption

class DoodExtractor(private val client: OkHttpClient) {

    fun videosFromUrl(url: String): List<ExtractedSource> {
        val parsedUrl = url.toHttpUrl()
        val videoId = parsedUrl.encodedPath.removeSuffix("/").substringAfterLast("/")
        var host = parsedUrl.host

        val allowedHosts = listOf("doodstream.com", "myvidplay.com", "playmogo.com")
        if (host !in allowedHosts) {
            host = "playmogo.com"
        }

        var webUrl = "https://$host/d/$videoId"
        val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        try {
            Log.d(DOOD_LOG, "Processing URL: $url")
            val headers = Headers.Builder()
                .add("User-Agent", userAgent)
                .add("Referer", "https://$host/")
                .build()

            var response = client.newCall(GET(webUrl, headers)).execute()
            val actualUrl = response.request.url.toString()
            var html = response.body.string()

            if (actualUrl != webUrl) {
                host = response.request.url.host
                webUrl = "https://$host/d/$videoId"
                Log.d(DOOD_LOG, "Redirected to: $webUrl")
            }

            val currentHeaders = headers.newBuilder()
                .set("Referer", webUrl)
                .build()

            if ("Video not found" in html) {
                Log.d(DOOD_LOG, "Video not found in HTML")
                return emptyList()
            }

            // Check for iframe
            val iframeMatch = Regex("""<iframe\s*src="([^"]+)""").find(html)
            if (iframeMatch != null) {
                val iframeUrl = webUrl.toHttpUrl().resolve(iframeMatch.groupValues[1])?.toString() ?: return emptyList()
                Log.d(DOOD_LOG, "Found iframe: $iframeUrl")
                response = client.newCall(GET(iframeUrl, currentHeaders)).execute()
                html = response.body.string()
            } else {
                val embedUrl = webUrl.replace("/d/", "/e/")
                Log.d(DOOD_LOG, "Using embed URL: $embedUrl")
                response = client.newCall(GET(embedUrl, currentHeaders)).execute()
                html = response.body.string()
            }

            // Extract quality
            val qualityMatch = Regex("""\b(360|480|720|1080|1440|2160)[pP]""").find(html)
            val quality = qualityMatch?.let { "${it.groupValues[1]}p" } ?: "unknown"
            Log.d(DOOD_LOG, "Extracted quality: $quality")

            // Extract token and pass URL
            val mainRegex = Regex(
                """dsplayer\.hotkeys[^']+'([^']+).+?function\s*makePlay.+?return[^?]+([^"]+)""",
                RegexOption.DOT_MATCHES_ALL,
            )
            val match = mainRegex.find(html)
            if (match == null) {
                Log.d(DOOD_LOG, "Regex match failed. HTML length: ${html.length}")
                return emptyList()
            }

            val passPath = match.groupValues[1]
            val token = match.groupValues[2]
            Log.d(DOOD_LOG, "Extracted passPath: $passPath, token: $token")
            val passUrl = webUrl.toHttpUrl().resolve(passPath)?.toString() ?: return emptyList()

            val baseResponse = client.newCall(GET(passUrl, currentHeaders)).execute()
            val baseUrl = baseResponse.body.string().trim()
            Log.d(DOOD_LOG, "Base URL response: $baseUrl")

            val finalUrl = if (baseUrl.contains("cloudflarestorage.")) {
                baseUrl
            } else {
                baseUrl + createHashTable(10) + token + System.currentTimeMillis()
            }
            Log.d(DOOD_LOG, "Final URL: $finalUrl")

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
        } catch (e: Exception) {
            Log.e(DOOD_LOG, "Extraction failed", e)
            return emptyList()
        }
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
