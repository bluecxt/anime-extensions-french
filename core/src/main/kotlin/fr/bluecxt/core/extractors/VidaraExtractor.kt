package fr.bluecxt.core.extractors

import android.util.Log
import eu.kanade.tachiyomi.network.POST
import fr.bluecxt.core.ContentUnavailableException
import fr.bluecxt.core.DEFAULT_USER_AGENT
import fr.bluecxt.core.ExtractionException
import fr.bluecxt.core.VIDARA_LOG
import fr.bluecxt.core.model.ExtractedSource
import fr.bluecxt.core.utils.PlaylistUtils
import fr.bluecxt.core.utils.awaitSuccessOrUnavailable
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

        val response = client.newCall(POST(apiUrl, headers, payload)).awaitSuccessOrUnavailable(apiUrl)
        val responseBody = response.use { it.body.string() }
        if (responseBody.contains("file_not_found", ignoreCase = true) || responseBody.contains("file is no longer available", ignoreCase = true) || responseBody.contains("\"error\":\"not_found\"", ignoreCase = true)) {
            throw ContentUnavailableException("Vidara: Video file deleted or not found")
        }

        val data = runCatching { json.decodeFromString<VidaraResponse>(responseBody) }.getOrNull()

        val streamingUrl = data?.streaming_url
            ?: throw ContentUnavailableException("Vidara: streaming_url not found in response: $responseBody")

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
