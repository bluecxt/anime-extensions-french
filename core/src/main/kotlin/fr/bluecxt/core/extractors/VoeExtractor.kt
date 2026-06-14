package fr.bluecxt.core.extractors

import android.util.Base64
import android.util.Log
import eu.kanade.tachiyomi.network.GET
import fr.bluecxt.core.DEFAULT_USER_AGENT
import fr.bluecxt.core.VOE_LOG
import fr.bluecxt.core.model.ExtractedSource
import fr.bluecxt.core.utils.PlaylistUtils
import fr.bluecxt.core.utils.detectMp4Resolution
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

// writed using https://github.com/skoruppa/docchi-players/blob/main/voe.py

class VoeExtractor(private val client: OkHttpClient) {

    private val json = Json { ignoreUnknownKeys = true }
    private val playlistUtils by lazy { PlaylistUtils(client) }

    suspend fun videosFromUrl(url: String): List<ExtractedSource> {
        var currentUrl = url
        Log.d(VOE_LOG, "url = $currentUrl")
        val headers = Headers.Builder()
            .add("User-Agent", DEFAULT_USER_AGENT)
            .add("Referer", currentUrl)
            .build()

        var response = client.newCall(GET(url, headers)).execute()
        var html = response.body.string()

        // Check for redirect
        if (html.contains("const currentUrl") && html.contains("window.location.href")) {
            val redirectUrl = Regex("window\\.location\\.href\\s*=\\s*'([^']+)'")
                .find(html)?.groupValues?.get(1)

            if (redirectUrl != null) {
                currentUrl = redirectUrl
                response = client.newCall(GET(redirectUrl, headers)).execute()
                html = response.body.string()
            }
        }

        return newMethod(html, headers, currentUrl).ifEmpty {
            fallbackMethod(html, headers)
        }
    }

    private suspend fun newMethod(html: String, headers: Headers, url: String): List<ExtractedSource> {
        val match = Regex("""json">\["([^"]+)"]</script>\s*<script\s*src="([^"]+)""")
            .find(html)
            ?: return emptyList()

        val encryptedData = match.groupValues.get(1)
        val scriptUrl = url.toHttpUrl().resolve(match.groupValues.get(2))?.toString() ?: return emptyList()
        Log.d(VOE_LOG, "url un newMethod = $scriptUrl")

        val response = client.newCall(GET(scriptUrl, headers)).execute()
        if (!response.isSuccessful) {
            response.close()
            return emptyList()
        }
        val scriptContent = response.body.string()

        val replMatch = Regex("""(\[(?:'\W{2}'[,\]]){1,9})""")
            .find(scriptContent)
        if (replMatch == null) return emptyList()
        val decoded = voeDecode(encryptedData, replMatch.groupValues.get(1))

        val streamUrl = listOf("file", "source", "direct_access_url")
            .firstNotNullOfOrNull { key -> decoded?.get(key)?.jsonPrimitive?.content }
            ?: return emptyList()

        return if (streamUrl.endsWith(".m3u8")) {
            playlistUtils.extractFromHls(
                playlistUrl = streamUrl,
                masterHeaders = headers,
                videoHeaders = headers,
            )
        } else {
            val mp4Res = client.detectMp4Resolution(streamUrl, headers)
            val quality = if (mp4Res != null) "${mp4Res}p" else null
            listOf(
                ExtractedSource(
                    url = streamUrl,
                    headers = headers,
                    quality = quality,
                ),
            )
        }
    }

    private suspend fun fallbackMethod(html: String, headers: Headers): List<ExtractedSource> {
        val patterns = listOf(
            Regex("""mp4[\"']:\s*[\"'](?<url>[^\"']+)[\"'],\s*[\"']video_height[\"']:\s*(?<label>[^,]+)"""),
            Regex("""hls':\s*'(?<url>[^']+)'"""),
            Regex("""hls":\s*"(?<url>[^"]+)",\s*"video_height":\s*(?<label>[^,]+)"""),
        )

        for (pattern in patterns) {
            val match = pattern.find(html)
            if (match == null) continue
            val streamUrl = match.groups["url"]?.value ?: continue
            val label = match.groups["label"]?.value
            val quality = if (label != null) {
                "${label}p"
            } else {
                val mp4Res = client.detectMp4Resolution(streamUrl, headers)
                if (mp4Res != null) "${mp4Res}p" else null
            }

            return if (streamUrl.endsWith(".m3u8")) {
                playlistUtils.extractFromHls(
                    playlistUrl = streamUrl,
                    masterHeaders = headers,
                    videoHeaders = headers,
                )
            } else {
                listOf(
                    ExtractedSource(
                        url = streamUrl,
                        quality = quality,
                        headers = headers,
                    ),
                )
            }
        }
        return emptyList()
    }

    private fun voeDecode(encryptedData: String, luts: String): JsonObject? = runCatching {
        var txt = encryptedData.map { c ->
            when (c) {
                in 'A'..'Z' -> ((c - 'A' + ROT13_OFFSET) % ALPHABET_SIZE + 'A'.code).toChar()
                in 'a'..'z' -> ((c - 'a' + ROT13_OFFSET) % ALPHABET_SIZE + 'a'.code).toChar()
                else -> c
            }
        }.joinToString("")

        val patterns = luts.removeSurrounding("[", "]")
            .split(",")
            .map { it.trim().removeSurrounding("'").removeSurrounding("\"") }
            .map { Regex.escape(it) }

        if (patterns.isNotEmpty() && patterns.any { it.isNotEmpty() }) {
            val cleaningRegex = patterns.joinToString("|").toRegex()
            txt = txt.replace(cleaningRegex, "")
        }

        val decodedBytes1 = Base64.decode(txt, Base64.DEFAULT)
        val decodedString1 = String(decodedBytes1, Charsets.UTF_8)

        val shifted = decodedString1.map { (it.code - CAESAR_SHIFT).toChar() }.joinToString("")

        val reversed = shifted.reversed()
        val decodedBytes2 = Base64.decode(reversed, Base64.DEFAULT)
        val finalJsonString = String(decodedBytes2, Charsets.UTF_8)

        json.decodeFromString<JsonObject>(finalJsonString)
    }.getOrElse { e ->
        if (e is IllegalArgumentException || e is kotlinx.serialization.SerializationException) {
            Log.e("VoeExtractor", "Erreur attendue lors du décodage VOE: ${e.message}")
            null
        } else {
            throw e
        }
    }

    companion object {
        private const val ROT13_OFFSET = 13
        private const val ALPHABET_SIZE = 26
        private const val CAESAR_SHIFT = 3
    }
}
