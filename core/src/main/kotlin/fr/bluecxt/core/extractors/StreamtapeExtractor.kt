// Copyright bluecxt
// SPDX-License-Identifier: Apache-2.0
package fr.bluecxt.core.extractors

import eu.kanade.tachiyomi.network.GET
import fr.bluecxt.core.model.ExtractedSource
import fr.bluecxt.core.utils.awaitSuccessOrUnavailable
import okhttp3.OkHttpClient
import org.jsoup.Jsoup

/** Position du segment d'identifiant dans l'URL Streamtape. */
private const val URL_ID_SEGMENT_INDEX = 4

class StreamtapeExtractor(private val client: OkHttpClient) {
    suspend fun videosFromUrl(url: String): List<ExtractedSource> {
        val baseUrl = "https://streamtape.com/e/"
        val newUrl = if (!url.startsWith(baseUrl)) {
            val id = url.split("/").getOrNull(URL_ID_SEGMENT_INDEX) ?: return emptyList()
            baseUrl + id
        } else {
            url
        }

        val document = Jsoup.parse(client.newCall(GET(newUrl)).awaitSuccessOrUnavailable(newUrl).use { it.body.string() })
        val targetLine = "document.getElementById('robotlink')"
        val script = document.selectFirst("script:containsData($targetLine)")
            ?.data()
            ?.substringAfter("$targetLine.innerHTML = '")
            ?: return emptyList()
        val videoUrl = "https:" + script.substringBefore("'") +
            script.substringAfter("+ ('xcd").substringBefore("'")

        return listOf(
            ExtractedSource(
                url = videoUrl,
                quality = "Streamtape",
            ),
        )
    }
}
