package eu.kanade.tachiyomi.animeextension.fr.animesultra.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import fr.bluecxt.core.DEFAULT_USER_AGENT
import okhttp3.Headers
import okhttp3.OkHttpClient

class VidstreamExtractor(private val client: OkHttpClient) {
    fun videosFromUrl(url: String): List<Video> {
        val id = url.substringAfter("id=", "").substringBefore("&")
        if (id.isEmpty()) return emptyList()

        val headers = Headers.Builder()
            .add("User-Agent", DEFAULT_USER_AGENT)
            .add("Referer", "https://lb.daisukianime.xyz/")
            .add("Accept", "*/*")
            .build()

        return try {
            val response = client.newCall(GET(url, headers)).execute()
            val body = response.body.string()
            val videoUrl = body.substringAfter("file:\"", "").substringBefore("\"")
            if (videoUrl.isEmpty()) return emptyList()

            listOf(
                Video(
                    videoUrl = videoUrl,
                    videoTitle = "Vidstream",
                    headers = headers,
                ),
            )
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun getResolutionFromMoov(hex: String): Int? {
        try {
            // Find "tkhd" box (track header)
            // It contains the track dimensions
            val tkhdIndex = hex.indexOf("746b6864")
            if (tkhdIndex != -1) {
                // Offset to Width is 80 bytes (160 hex chars)
                // Offset to Height is 84 bytes (168 hex chars)
                // These are 16.16 fixed-point numbers, we take the integer part (first 4 chars)
                val heightHex = hex.substring(tkhdIndex + 168, tkhdIndex + 172)
                val height = heightHex.toInt(16)

                if (height in 240..2160) {
                    return height
                }
            }
        } catch (_: Exception) {}
        return null
    }
}
