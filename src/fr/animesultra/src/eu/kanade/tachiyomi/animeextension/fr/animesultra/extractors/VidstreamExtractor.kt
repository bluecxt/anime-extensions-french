package eu.kanade.tachiyomi.animeextension.fr.animesultra.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.OkHttpClient

class VidstreamExtractor(private val client: OkHttpClient) {
    fun videosFromUrl(url: String): List<Video> {
        val id = url.substringAfter("id=", "").substringBefore("&")
        if (id.isEmpty()) return emptyList()

        val headers = Headers.Builder()
            .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.3")
            .add("Referer", "https://lb.daisukianime.xyz/")
            .add("Accept", "*/*")
            .build()

        return try {
            val apiUrl = "https://cdn2.daisukianime.xyz/sib/$id"
            android.util.Log.d("VidstreamDebug", "Calling API: $apiUrl")

            val apiResponse = client.newCall(GET(apiUrl, headers)).execute().body.string()

            // Simple JSON parsing to get the "file" field
            val videoUrl = Regex("""["']file["']\s*:\s*["']([^"']+)["']""").find(apiResponse)?.groupValues?.get(1)

            if (videoUrl != null) {
                val absoluteVideoUrl = videoUrl.replace("\\/", "/")

                val resolution = try {
                    val rangeHeaders = headers.newBuilder().add("Range", "bytes=0-16383").build()
                    val response = client.newCall(GET(absoluteVideoUrl, rangeHeaders)).execute()
                    val bytes = response.body.bytes()
                    getResolutionFromMp4(bytes)
                } catch (e: Exception) {
                    null
                }

                val title = if (resolution != null) "UltraCDN - ${resolution}p" else "UltraCDN"
                listOf(Video(videoUrl = absoluteVideoUrl, videoTitle = title, headers = headers))
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            android.util.Log.e("VidstreamDebug", "API fetch failed for $id", e)
            emptyList()
        }
    }

    private fun getResolutionFromMp4(bytes: ByteArray): Int? {
        try {
            val hex = bytes.joinToString("") { "%02x".format(it) }
            val tkhdIndex = hex.indexOf("746b6864") // "tkhd"
            if (tkhdIndex != -1) {
                // In version 0 of tkhd atom:
                // From the start of "tkhd" string (4 bytes):
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
