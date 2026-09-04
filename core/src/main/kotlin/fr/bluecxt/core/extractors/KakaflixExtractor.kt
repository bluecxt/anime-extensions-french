// Copyright bluecxt
// SPDX-License-Identifier: Apache-2.0
package fr.bluecxt.core.extractors

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import fr.bluecxt.core.model.ExtractedSource
import fr.bluecxt.core.utils.defaultHeaders
import okhttp3.OkHttpClient

class KakaflixExtractor(private val client: OkHttpClient) {

    private val doodExtractor by lazy { DoodExtractor(client) }

    suspend fun videosFromUrl(url: String): List<ExtractedSource> {
        val headers = defaultHeaders(referer = "https://french-stream.one/")
        val response = client.newCall(GET(url, headers)).awaitSuccess()
        val finalUrl = response.request.url.toString()

        return doodExtractor.videosFromUrl(finalUrl)
    }
}
