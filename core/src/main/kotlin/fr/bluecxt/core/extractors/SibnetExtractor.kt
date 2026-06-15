package fr.bluecxt.core.extractors

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import fr.bluecxt.core.defaultHeaders
import fr.bluecxt.core.model.ExtractedSource
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

class SibnetExtractor(private val client: OkHttpClient) {

    suspend fun videosFromUrl(url: String): List<ExtractedSource> {
        val videoList = mutableListOf<ExtractedSource>()

        val document = client.newCall(
            GET(url),
        ).awaitSuccess().asJsoup()
        val script = document.selectFirst("script:containsData(player.src)")?.data() ?: return emptyList()
        val slug = script.substringAfter("player.src").substringAfter("src:")
            .substringAfter("\"").substringBefore("\"")

        val videoUrl = if (slug.contains("http")) {
            slug
        } else {
            "https://${url.toHttpUrl().host}$slug"
        }

        val headers = defaultHeaders(url)

        return listOf(
            ExtractedSource(
                url = videoUrl,
                quality = null, // TODO ajouter la qualité
                headers = headers,
                subtitleTracks = emptyList(),
                audioTracks = emptyList(),
            ),
        )
    }
}
