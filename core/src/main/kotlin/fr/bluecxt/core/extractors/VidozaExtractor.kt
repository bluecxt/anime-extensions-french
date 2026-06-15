package fr.bluecxt.core.extractors

import android.util.Log
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import fr.bluecxt.core.VIDOZA_LOG
import fr.bluecxt.core.defaultHeaders
import fr.bluecxt.core.model.ExtractedSource
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

// writed using https://github.com/skoruppa/docchi-players/blob/main/vidoza.py

class VidozaExtractor(private val client: OkHttpClient) {
    suspend fun videosFromUrl(url: String): List<ExtractedSource> {
        val parsedUrl = url.toHttpUrl()

        val host = parsedUrl.host

        val videoId = parsedUrl.pathSegments.filter { it.isNotBlank() }.last()
            .removePrefix("embed-")
            .removeSuffix(".html")

        val embedUrl = "https://$host/embed-$videoId.html"

        val headers = defaultHeaders()

        val response = client.newCall(GET(embedUrl, headers)).awaitSuccess()
        val html = response.body.string()

        val regex1 = Regex("""["']?\s*(?:file|src)\s*["']?\s*[:=,]?\s*["']([^"']+)(?:[^}>\]]+)["']?\s*res\s*["']?\s*[:=]\s*["']?([^"',]+)""")
        val regex2 = Regex("""(?:file|src)\s*[:=]\s*["']([^"']+)["'].*?(?:label|res)\s*[:=]\s*["']?(\d+)""")
        val regex3 = Regex("""(https?://[^"\']+\.mp4[^"\']*)""")

        val matches = when {
            regex1.containsMatchIn(html) -> regex1.findAll(html).toList()

            regex2.containsMatchIn(html) -> regex2.findAll(html).toList()

            regex3.containsMatchIn(html) -> {
                return listOf(
                    ExtractedSource(
                        url = regex3.find(html)!!.groupValues[1],
                        headers = headers,
                    ),
                )
            }

            else -> {
                Log.w(VIDOZA_LOG, "no link found")
                return emptyList()
            }
        }

        return matches.mapNotNull { match ->
            val url = match.groupValues.get(1)
            val quality = match.groupValues.get(2)
            ExtractedSource(
                url = url,
                quality = quality,
                headers = headers,
            )
        }
    }
}
