package fr.bluecxt.core.extractors

import fr.bluecxt.core.DEFAULT_USER_AGENT
import fr.bluecxt.core.VIDARA_LOG
import fr.bluecxt.core.model.ExtractedSource
import fr.bluecxt.core.network.POST
import fr.bluecxt.core.network.awaitSuccess
import fr.bluecxt.core.utils.Log
import fr.bluecxt.core.utils.PlaylistUtils
import fr.bluecxt.core.utils.toJsonRequestBody
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

    suspend fun videosFromUrl(url: String): List<ExtractedSource> {
        val regex = Regex("""/(?:e|v)/([0-9a-zA-Z]+)""")
        val mediaId = regex.find(url)?.groupValues?.get(1) ?: throw Exception("Could not extract mediaId for Vidara/Streamix")
        Log.d(VIDARA_LOG, "Extracted mediaId: $mediaId")

        val httpUrl = url.toHttpUrlOrNull() ?: throw Exception("Invalid URL for Vidara/Streamix")
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

        val response = client.newCall(POST(apiUrl, headers, payload)).awaitSuccess()

        val responseBody = response.body.string()
        val data = runCatching { json.decodeFromString<VidaraResponse>(responseBody) }.getOrNull()

        val streamingUrl = data?.streaming_url
            ?: throw Exception("streaming_url not found in Vidara/Streamix response: $responseBody")

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
