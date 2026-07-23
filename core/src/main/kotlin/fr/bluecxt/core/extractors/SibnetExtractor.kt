package fr.bluecxt.core.extractors

import android.util.Log
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import fr.bluecxt.core.ContentUnavailableException
import fr.bluecxt.core.SIBNET_LOG
import fr.bluecxt.core.model.ExtractedSource
import fr.bluecxt.core.utils.defaultHeaders
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

class SibnetExtractor(private val client: OkHttpClient) {

    suspend fun videosFromUrl(url: String): List<ExtractedSource> {
        var document = client.newCall(GET(url)).awaitSuccess().asJsoup()

        var script = document.selectFirst("script:containsData(player.src)")?.data()

        if (script == null) {
            val html = document.html()
            if (html.contains("Видео недоступно") || html.contains("Video not available")) {
                throw ContentUnavailableException("html contains Video not available")
            }

            Log.d(SIBNET_LOG, "Player script not found, retrying in 1s...")
            kotlinx.coroutines.delay(1000)
            document = client.newCall(GET(url)).awaitSuccess().asJsoup()
            script = document.selectFirst("script:containsData(player.src)")?.data()

            if (script == null) {
                val retryHtml = document.html()
                if (retryHtml.contains("Видео недоступно") || retryHtml.contains("Video not available")) {
                    throw fr.bluecxt.core.ContentUnavailableException("Sibnet: Video explicitly marked as unavailable")
                }
                throw Exception("Could not find player script in Sibnet (Title: ${document.title()})")
            }
        }

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
