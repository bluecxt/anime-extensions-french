package fr.bluecxt.core.extractors

import android.util.Log
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import fr.bluecxt.core.model.ExtractedSource
import fr.bluecxt.core.utils.PlaylistUtils
import fr.bluecxt.core.utils.defaultHeaders
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

class SendvidExtractor(private val client: OkHttpClient, private val headers: Headers) {
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    suspend fun videosFromUrl(url: String): List<ExtractedSource> {
        val document = client.newCall(GET(url, headers)).awaitSuccess().asJsoup()
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
