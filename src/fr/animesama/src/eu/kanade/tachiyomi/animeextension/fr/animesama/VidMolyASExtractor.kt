package eu.kanade.tachiyomi.animeextension.fr.animesama

import android.util.Log
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

class VidMolyASExtractor(private val client: OkHttpClient, private val baseHeaders: Headers) {

    private val playlistUtils by lazy { PlaylistUtils(client) }

    fun videosFromUrl(url: String, prefix: String = ""): List<Video> {
        Log.d("VidMolyAS", "Target URL: $url")

        // Try different VidMoly mirrors if one fails
        val mirrors = listOf(
            url,
            url.replace(".to/", ".biz/").replace(".to/", ".me/").replace(".to/", ".net/"),
        ).distinct()

        for (targetUrl in mirrors) {
            val videos = try {
                extractVideos(targetUrl, prefix)
            } catch (e: Exception) {
                emptyList()
            }
            if (videos.isNotEmpty()) return videos
        }

        return emptyList()
    }

    private fun extractVideos(url: String, prefix: String): List<Video> {
        val uri = url.toHttpUrl()
        val host = "${uri.scheme}://${uri.host}"

        val headers = baseHeaders.newBuilder()
            .set("Referer", "https://anime-sama.to/")
            .set("Origin", "https://anime-sama.to")
            .set("Sec-Fetch-Dest", "iframe")
            .set("Sec-Fetch-Mode", "navigate")
            .set("Sec-Fetch-Site", "cross-site")
            .build()

        var response = client.newCall(GET(url, headers)).execute()
        var html = response.body.string()

        // Handle Redirection Stage 1
        if (html.contains("window.location.replace")) {
            val nextPath = Regex("""window\.location\.replace\(['"](.*?)['"]\)""").find(html)?.groupValues?.get(1)
            if (nextPath != null) {
                val nextUrl = if (nextPath.startsWith("http")) nextPath else "$host$nextPath"
                Log.d("VidMolyAS", "Redirecting to: $nextUrl")
                Thread.sleep(300) // Small delay to mimic JS execution
                response = client.newCall(GET(nextUrl, headers.newBuilder().set("Referer", url).build())).execute()
                html = response.body.string()
            }
        }

        // Search for m3u8 with a very aggressive regex
        val m3u8Regex = Regex("""https?://[^\s"'<>]+?\.m3u8[^\s"'<>]*""")
        val m3u8Matches = m3u8Regex.findAll(html).map { it.value }.toList().distinct()

        if (m3u8Matches.isEmpty()) {
            Log.e("VidMolyAS", "No m3u8 in HTML (Length: ${html.length})")
            return emptyList()
        }

        return m3u8Matches.flatMap { m3u8Url ->
            try {
                playlistUtils.extractFromHls(
                    m3u8Url,
                    referer = "$host/",
                    masterHeaders = headers.newBuilder().set("Referer", "$host/").build(),
                    videoHeaders = headers.newBuilder().set("Referer", "$host/").build(),
                    videoNameGen = { quality: String -> "${prefix}VidMoly - $quality" },
                )
            } catch (e: Exception) {
                emptyList()
            }
        }
    }
}
