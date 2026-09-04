// Copyright bluecxt
// SPDX-License-Identifier: Apache-2.0
package fr.bluecxt.core.extractors

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import fr.bluecxt.core.Source
import fr.bluecxt.core.model.ExtractedSource
import fr.bluecxt.core.utils.defaultHeaders

class KokoflixExtractor(private val source: Source) {

    private val vidaraExtractor by lazy { VidaraExtractor(source.extractorClient) }
    private val doodExtractor by lazy { DoodExtractor(source.extractorClient) }
    private val voeExtractor by lazy { VoeExtractor(source.extractorClient) }

    suspend fun videosFromUrl(url: String): List<ExtractedSource> {
        val headers = defaultHeaders(referer = "https://french-stream.one/")
        val response = source.extractorClient.newCall(GET(url, headers)).awaitSuccess()
        val finalUrl = response.request.url.toString()

        return when {
            finalUrl.contains("kitchenstories.fit", ignoreCase = true) || finalUrl.contains("vidara", ignoreCase = true) -> {
                vidaraExtractor.videosFromUrl(finalUrl)
            }

            finalUrl.contains("playmogo", ignoreCase = true) || finalUrl.contains("dood", ignoreCase = true) -> {
                doodExtractor.videosFromUrl(finalUrl)
            }

            finalUrl.contains("voe", ignoreCase = true) -> {
                voeExtractor.videosFromUrl(finalUrl)
            }

            else -> {
                runCatching { vidaraExtractor.videosFromUrl(finalUrl) }
                    .getOrElse { doodExtractor.videosFromUrl(finalUrl) }
            }
        }
    }
}
