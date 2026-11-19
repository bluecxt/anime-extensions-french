// Copyright bluecxt
// SPDX-License-Identifier: Apache-2.0
package fr.bluecxt.core.extractors

import eu.kanade.tachiyomi.network.GET
import fr.bluecxt.core.model.ContentUnavailableException
import fr.bluecxt.core.model.ExtractedSource
import fr.bluecxt.core.model.ExtractionException
import fr.bluecxt.core.utils.awaitSuccessOrUnavailable
import fr.bluecxt.core.utils.defaultHeaders
import fr.bluecxt.core.utils.video.PlaylistUtils
import fr.bluecxt.core.utils.video.detectMp4Resolution
import keiyoushi.utils.useAsJsoup
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

/**
 * Fabrique centralisée des échecs d'extraction, pour limiter le nombre de `throw` par fonction.
 */
private fun extractionFailure(message: String): Nothing = throw ExtractionException(message)

class SendvidExtractor(private val client: OkHttpClient, private val headers: Headers) {
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    suspend fun videosFromUrl(url: String): List<ExtractedSource> {
        val document = runCatching {
            client.newCall(GET(url, headers)).awaitSuccessOrUnavailable(url, unavailableCodes = setOf(404, 410, 502))
        }.getOrElse { e ->
            if (e is kotlinx.coroutines.CancellationException || e is ContentUnavailableException) throw e
            extractionFailure("Sendvid page fetch failed: ${e.message ?: e::class.java.simpleName}")
        }.useAsJsoup()
        val masterUrl = document.selectFirst("source#video_source")?.attr("src")?.takeIf { it.isNotBlank() }
            ?: extractionFailure("Could not find video source in Sendvid")
        val httpUrl = "https://${url.toHttpUrl().host}".toHttpUrlOrNull()

        val headers = defaultHeaders(httpUrl.toString())

        return if (masterUrl.contains(".m3u8")) {
            playlistUtils.extractFromHls(
                playlistUrl = masterUrl,
                masterHeaders = headers,
                videoHeaders = headers,
            )
        } else {
            val height = client.detectMp4Resolution(masterUrl, headers)
            listOf(
                ExtractedSource(
                    url = masterUrl,
                    quality = height?.let { "${it}p" },
                    headers = headers,
                ),
            )
        }
    }
}
