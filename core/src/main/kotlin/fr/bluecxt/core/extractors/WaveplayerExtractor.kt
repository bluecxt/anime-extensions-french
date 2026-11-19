// Copyright bluecxt
// SPDX-License-Identifier: Apache-2.0
package fr.bluecxt.core.extractors

import eu.kanade.tachiyomi.animesource.model.Track
import fr.bluecxt.core.model.ExtractedSource
import fr.bluecxt.core.utils.video.DashToExtractedSource
import fr.bluecxt.core.utils.video.Lang
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

class WaveplayerExtractor(private val client: OkHttpClient, private val headers: Headers) {
    suspend fun videosFromUrl(
        masterUrl: String,
        refererUrl: String,
        subtitleTracks: List<Track> = emptyList(),
        onQualityError: ((String) -> Unit)? = null,
    ): List<ExtractedSource> {
        val extHeaders = headers.newBuilder().set("Referer", refererUrl).build()

        return try {
            val request = Request.Builder()
                .url(masterUrl)
                .headers(extHeaders)
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) throw IOException("Failed to load MPD: ${response.code}")

            val body = response.body.string()
            val sources = DashToExtractedSource().fromString(
                xmlString = body,
                baseUrl = masterUrl,
                userLanguage = Lang.FR,
                onQualityError = onQualityError,
            )

            if (sources.isNullOrEmpty()) {
                fallback(masterUrl, extHeaders, subtitleTracks)
            } else {
                sources.map { source ->
                    source.copy(
                        headers = (source.headers?.newBuilder() ?: extHeaders.newBuilder())
                            .addAll(extHeaders)
                            .build(),
                        subtitleTracks = source.subtitleTracks + subtitleTracks,
                    )
                }
            }
        } catch (e: Exception) {
            onQualityError?.invoke("Exception: ${e.message}")
            fallback(masterUrl, extHeaders, subtitleTracks)
        }
    }

    private fun fallback(
        url: String,
        headers: Headers,
        subtitleTracks: List<Track>,
    ): List<ExtractedSource> = listOf(
        ExtractedSource(
            url = url,
            headers = headers,
            subtitleTracks = subtitleTracks,
        ),
    )
}
