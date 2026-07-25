package fr.bluecxt.core.extractors

import android.util.Log
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import fr.bluecxt.core.defaultHeaders
import fr.bluecxt.core.model.ExtractedSource
import fr.bluecxt.core.utils.PlaylistUtils
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class SendvidExtractor(private val client: OkHttpClient, private val headers: Headers) {
    // TODO: TEMPORAIRE - Sendvid est instable / hors service.
    // Retirer ce client spécifique avec timeout de 10s dès que Sendvid fonctionnera à nouveau correctement.
    private val sendvidClient: OkHttpClient by lazy {
        client.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
    }
    private val playlistUtils by lazy { PlaylistUtils(sendvidClient, headers) }

    suspend fun videosFromUrl(url: String): List<ExtractedSource> {
        val document = sendvidClient.newCall(GET(url, headers)).awaitSuccess().asJsoup()
        val masterUrl = document.selectFirst("source#video_source")?.attr("src") ?: throw Exception("Could not find video source in Sendvid")
        val httpUrl = "https://${url.toHttpUrl().host}".toHttpUrlOrNull()

        val headers = defaultHeaders(httpUrl.toString())

        return if (masterUrl.contains(".m3u8")) {
            playlistUtils.extractFromHls(
                playlistUrl = masterUrl,
                masterHeaders = headers,
                videoHeaders = headers,
            )
        } else {
            listOf(
                ExtractedSource(
                    url = masterUrl,
                    headers = headers,
                ),
            )
        }
    }
}
