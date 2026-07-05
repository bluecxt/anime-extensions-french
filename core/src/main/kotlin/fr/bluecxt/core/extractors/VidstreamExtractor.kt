package fr.bluecxt.core.extractors

import fr.bluecxt.core.DEFAULT_USER_AGENT
import fr.bluecxt.core.model.ExtractedSource
import fr.bluecxt.core.network.GET
import fr.bluecxt.core.network.awaitSuccess
import okhttp3.Headers
import okhttp3.OkHttpClient

class VidstreamExtractor(private val client: OkHttpClient) {
    suspend fun videosFromUrl(url: String): List<ExtractedSource> {
        val id = url.substringAfter("id=", "").substringBefore("&")
        if (id.isEmpty()) return emptyList()

        val headers = Headers.Builder()
            .add("User-Agent", DEFAULT_USER_AGENT)
            .add("Referer", "https://lb.daisukianime.xyz/")
            .add("Accept", "*/*")
            .build()

        return try {
            val response = client.newCall(GET(url, headers)).awaitSuccess()
            val body = response.body.string()
            val videoUrl = body.substringAfter("file:\"", "").substringBefore("\"")
            if (videoUrl.isEmpty()) return emptyList()

            listOf(
                ExtractedSource(
                    url = videoUrl,
                    headers = headers,
                ),
            )
        } catch (_: Exception) {
            emptyList()
        }
    }
}
