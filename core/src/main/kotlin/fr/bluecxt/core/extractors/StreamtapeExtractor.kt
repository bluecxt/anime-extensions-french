package fr.bluecxt.core.extractors

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import fr.bluecxt.core.model.ExtractedSource
import okhttp3.OkHttpClient
import org.jsoup.Jsoup

class StreamtapeExtractor(private val client: OkHttpClient) {
    suspend fun videosFromUrl(url: String): List<ExtractedSource> {
        val baseUrl = "https://streamtape.com/e/"
        val newUrl = if (!url.startsWith(baseUrl)) {
            val id = url.split("/").getOrNull(4) ?: return emptyList()
            baseUrl + id
        } else {
            url
        }

        val document = Jsoup.parse(client.newCall(GET(newUrl)).awaitSuccess().body.string())
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
