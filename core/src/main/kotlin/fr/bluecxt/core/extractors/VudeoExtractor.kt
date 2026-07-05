package fr.bluecxt.core.extractors

import eu.kanade.tachiyomi.util.asJsoup
import fr.bluecxt.core.model.ExtractedSource
import fr.bluecxt.core.network.GET
import fr.bluecxt.core.network.awaitSuccess
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

class VudeoExtractor(private val client: OkHttpClient) {
    suspend fun videosFromUrl(url: String): List<ExtractedSource> {
        val doc = client.newCall(GET(url)).awaitSuccess()
            .asJsoup()

        val sources = doc.selectFirst("script:containsData(sources: [)")?.data()
            ?: return emptyList()

        val referer = "https://" + url.toHttpUrl().host + "/"

        val headers = Headers.headersOf("referer", referer)

        return sources.substringAfter("sources: [").substringBefore("]")
            .replace("\"", "")
            .split(',')
            .filter { it.startsWith("https") } // remove invalid links
            .map { videoUrl ->
                ExtractedSource(
                    url = videoUrl,
                    headers = headers,
                )
            }
    }
}
