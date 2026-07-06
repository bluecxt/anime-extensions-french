package fr.bluecxt.core.extractors

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import fr.bluecxt.core.model.ExtractedSource
import okhttp3.OkHttpClient

class StreamDavExtractor(private val client: OkHttpClient) {
    suspend fun videosFromUrl(url: String): List<ExtractedSource> = runCatching {
        val response = client.newCall(GET(url)).awaitSuccess()
        val document = response.asJsoup()
        document.select("source").map {
            val videoUrl = it.attr("src")
            val quality = it.attr("label")
            ExtractedSource(url = videoUrl, quality = quality)
        }
    }.getOrElse { emptyList() }
}
