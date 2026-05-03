package eu.kanade.tachiyomi.animeextension.fr.animesultra.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.OkHttpClient

class VidstreamExtractor(private val client: OkHttpClient) {
    fun videosFromUrl(url: String, referer: String): List<Video> {
        val headers = Headers.Builder()
            .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
            .add("Referer", referer)
            .add("Accept", "*/*")
            .build()

        return try {
            val response = client.newCall(GET(url, headers)).execute()
            val html = response.use { it.body.string() }

            val videos = mutableListOf<Video>()

            // 1. Check for sources in script (standard Vidstream)
            val m3u8Regex = Regex("""["']?file["']?\s*:\s*["']([^"']+\.m3u8[^"']*)["']""")
            m3u8Regex.findAll(html).forEach { match ->
                val m3u8Url = match.groupValues[1].replace("\\/", "/")
                val quality = when {
                    m3u8Url.contains("master.m3u8") -> "UltraCDN (Auto)"
                    m3u8Url.contains("1080") -> "UltraCDN 1080p"
                    m3u8Url.contains("720") -> "UltraCDN 720p"
                    else -> "UltraCDN HD"
                }
                videos.add(Video(videoUrl = m3u8Url, videoTitle = quality, headers = headers))
            }

            // 2. Check for hidden iframes or other players in the unified player
            if (videos.isEmpty()) {
                val iframeRegex = Regex("""<iframe[^>]+src=["']([^"']+)["']""")
                iframeRegex.findAll(html).forEach { match ->
                    val iframeUrl = match.groupValues[1].replace("\\/", "/")
                    if (!iframeUrl.contains("animesultra") && !iframeUrl.contains("google")) {
                        // Recursively try to get videos from the iframe if it's a known host
                        // For now we just add it as a source
                        if (iframeUrl.contains("sibnet")) {
                            videos.add(Video(videoUrl = iframeUrl, videoTitle = "Sibnet (via Unified)", headers = headers))
                        } else if (iframeUrl.contains("vidmoly")) {
                            videos.add(Video(videoUrl = iframeUrl, videoTitle = "Vidmoly (via Unified)", headers = headers))
                        }
                    }
                }
            }

            videos
        } catch (e: Exception) {
            emptyList()
        }
    }
}
