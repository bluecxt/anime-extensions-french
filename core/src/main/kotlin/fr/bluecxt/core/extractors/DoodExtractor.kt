// Copyright bluecxt
// SPDX-License-Identifier: Apache-2.0
package fr.bluecxt.core.extractors

import eu.kanade.tachiyomi.network.GET
import fr.bluecxt.core.model.ContentUnavailableException
import fr.bluecxt.core.model.ExtractedSource
import fr.bluecxt.core.model.ExtractionException
import fr.bluecxt.core.utils.awaitSuccessOrUnavailable
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import kotlin.text.RegexOption

/**
 * Fabrique centralisée des échecs d'extraction, pour limiter le nombre de `throw` par fonction.
 */
private fun extractionFailure(message: String): Nothing = throw ExtractionException(message)

/** Taille de la table de hachage Doodstream. */
private const val HASH_TABLE_SIZE = 10

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

        val response = client.newCall(GET(webUrl, headers)).awaitSuccessOrUnavailable(webUrl)
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
            throw ContentUnavailableException("Doodstream: Video not found")
        }

        html = fetchEmbedHtml(webUrl, html, currentHeaders)

        // Extract quality
        val quality = extractQuality(html)

        // Extract token and pass URL
        val (token, passUrl) = extractTokenAndPassUrl(html, webUrl)

        val baseUrl = fetchBaseUrl(passUrl, currentHeaders)

        val finalUrl = if (baseUrl.contains("cloudflarestorage.")) {
            baseUrl
        } else {
            baseUrl + createHashTable(HASH_TABLE_SIZE) + token + System.currentTimeMillis()
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

    /** Charge la page embed (iframe ou `/e/`) et renvoie son HTML. */
    private suspend fun fetchEmbedHtml(webUrl: String, html: String, currentHeaders: Headers): String {
        val iframeMatch = Regex("""<iframe\s*src="([^"]+)""").find(html)
        return if (iframeMatch != null) {
            val src = iframeMatch.groupValues[1]
            if (src == "/e/" || src == "/e") {
                throw ContentUnavailableException("Doodstream: Video not found")
            }
            val iframeUrl = webUrl.toHttpUrl().resolve(src)?.toString() ?: extractionFailure("Doodstream: Could not resolve iframe URL")
            val iframeResponse = client.newCall(GET(iframeUrl, currentHeaders)).awaitSuccessOrUnavailable(iframeUrl)
            iframeResponse.body.string()
        } else {
            val embedUrl = webUrl.replace("/d/", "/e/")
            val embedResponse = client.newCall(GET(embedUrl, currentHeaders)).awaitSuccessOrUnavailable(embedUrl)
            embedResponse.body.string()
        }
    }

    /** Qualité vidéo annoncée dans le HTML, ou `null` si elle est absente. */
    private fun extractQuality(html: String): String? {
        val qualityMatch = Regex("""\b(360|480|720|1080|1440|2160)[pP]""").find(html)
        return qualityMatch?.let { "${it.groupValues[1]}p" }
    }

    /** Extrait le jeton de lecture et l'URL `pass` depuis le script du player. */
    private fun extractTokenAndPassUrl(html: String, webUrl: String): Pair<String, String> {
        val mainRegex = Regex(
            """dsplayer\.hotkeys[^']+'([^']+).+?function\s*makePlay.+?return[^?]+([^"]+)""",
            RegexOption.DOT_MATCHES_ALL,
        )
        val match = mainRegex.find(html)
        if (match == null) {
            extractionFailure("Doodstream: Could find token in script")
        }

        val passPath = match.groupValues[1]
        val token = match.groupValues[2]
        val passUrl = webUrl.toHttpUrl().resolve(passPath)?.toString() ?: extractionFailure("Doodstream: Could not resolve pass URL")
        return token to passUrl
    }

    /** Récupère l'URL de base du CDN à partir de l'URL `pass`. */
    private suspend fun fetchBaseUrl(passUrl: String, currentHeaders: Headers): String {
        val baseResponse = client.newCall(GET(passUrl, currentHeaders)).awaitSuccessOrUnavailable(passUrl)
        return baseResponse.body.string().trim()
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
