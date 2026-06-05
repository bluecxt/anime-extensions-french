package fr.bluecxt.core.extractors

import eu.kanade.tachiyomi.animesource.model.Track
import fr.bluecxt.core.model.ExtractedSource
import okhttp3.Headers
import okhttp3.OkHttpClient

class WavePlayerExtractor(private val client: OkHttpClient, private val headers: Headers) {
    fun videosFromUrl(
        masterUrl: String,
        refererUrl: String,
        subtitleTracks: List<Track> = emptyList(),
    ): List<ExtractedSource> {
        val extHeaders = Headers.Builder().add("Referer", refererUrl).build()

        return listOf(
            ExtractedSource(
                url = masterUrl,
                quality = "", // DASH Auto
                headers = extHeaders,
                subtitleTracks = subtitleTracks,
            ),
        )
    }
}
