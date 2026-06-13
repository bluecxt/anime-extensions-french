package fr.bluecxt.core.extractors

import android.util.Log
import eu.kanade.tachiyomi.network.POST
import fr.bluecxt.core.DEFAULT_USER_AGENT
import fr.bluecxt.core.VIDARA_LOG
import fr.bluecxt.core.model.ExtractedSource
import fr.bluecxt.core.utils.PlaylistUtils
import keiyoushi.utils.toJsonRequestBody
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

// writed using https://github.com/skoruppa/docchi-players/blob/main/vidara.py

open class VidaraExtractor(private val client: OkHttpClient) {

    open val apiPath = "/api/stream"

    private val json = Json { ignoreUnknownKeys = true }

    fun videosFromUrl(url: String): List<ExtractedSource> {
        val regex = Regex("""/(?:e|v)/([0-9a-zA-Z]+)""")
        val mediaId = regex.find(url)?.groupValues?.get(1) ?: return emptyList()
        Log.d(VIDARA_LOG, "Extracted mediaId: $mediaId")

        val httpUrl = url.toHttpUrlOrNull() ?: return emptyList()
        val host = httpUrl.host

        val apiUrl = "https://$host$apiPath"
        Log.d(VIDARA_LOG, "API URL: $apiUrl")

        val headers = Headers.Builder()
            .add("User-Agent", DEFAULT_USER_AGENT)
            .add("Referer", url)
            .add("Origin", "https://$host")
            .add("Content-Type", "application/json")
            .build()

        val payload = mapOf(
            "filecode" to mediaId,
            "device" to "web",
        ).toJsonRequestBody()

        val response = client.newCall(POST(apiUrl, headers, payload)).execute()

        if (!response.isSuccessful) {
            Log.e(VIDARA_LOG, "API request failed with code: ${response.code}")
            response.close()
            return emptyList()
        }

        val responseBody = response.body.string()
        val data = runCatching { json.decodeFromString<VidaraResponse>(responseBody) }.getOrNull()

        val streamingUrl = data?.streaming_url ?: run {
            Log.e(VIDARA_LOG, "streaming_url not found in response: $responseBody")
            return emptyList()
        }
        Log.d(VIDARA_LOG, "Found streamingUrl: $streamingUrl")

        val videoHeaders = headers.newBuilder()
            .removeAll("Content-Type")
            .build()

        return try {
            PlaylistUtils(client).extractFromHls(
                playlistUrl = streamingUrl,
                referer = url,
                masterHeaders = videoHeaders,
                videoHeaders = videoHeaders,
            )
        } catch (e: Exception) {
            Log.e(VIDARA_LOG, "Error parsing HLS playlist", e)
            listOf(
                ExtractedSource(
                    url = streamingUrl,
                    headers = videoHeaders,
                ),
            )
        }
    }

    @Serializable
    data class VidaraResponse(
        val streaming_url: String? = null,
    )
}
