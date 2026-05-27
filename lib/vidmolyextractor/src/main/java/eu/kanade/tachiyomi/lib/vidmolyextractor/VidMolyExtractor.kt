package eu.kanade.tachiyomi.lib.vidmolyextractor

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

class VidMolyExtractor(private val client: OkHttpClient, headers: Headers = Headers.EMPTY) {

    private val baseUrl = "https://vidmoly.to"

    private val playlistUtils by lazy { PlaylistUtils(client) }

    private val headers: Headers = headers.newBuilder()
        .set("Origin", baseUrl)
        .set("Referer", "$baseUrl/")
        .build()

    private val sourcesRegex = Regex("sources:\\s*(\\[.*?])", RegexOption.DOT_MATCHES_ALL)
    private val urlsRegex = Regex("""file:\s*["'](.*?)["']""")

    fun videosFromUrl(url: String, prefix: String = ""): List<Video> {
        return try {
            android.util.Log.d("VidMoly", "Fetching URL: $url")
            var response = client.newCall(
                GET(url, headers.newBuilder().set("Sec-Fetch-Dest", "iframe").build())
            ).execute()
            var html = response.body.string()
            
            // Fallback: Follow JS redirection
            if (html.contains("window.location.replace")) {
                val nextPath = Regex("""window\.location\.replace\(['\"](.*?)['\"]\)""").find(html)?.groupValues?.get(1)
                if (nextPath != null) {
                    val uri = url.toHttpUrl()
                    val host = "${uri.scheme}://${uri.host}"
                    val nextUrl = if (nextPath.startsWith("http")) nextPath else "$host$nextPath"
                    android.util.Log.d("VidMoly", "Following redirection to: $nextUrl")
                    response = client.newCall(GET(nextUrl, headers.newBuilder().set("Referer", url).build())).execute()
                    html = response.body.string()
                }
            }
            val document = org.jsoup.Jsoup.parse(html, url)
            
            var script = document.selectFirst("script:containsData(sources)")?.data()
            if (script == null) {
                android.util.Log.d("VidMoly", "Script tag not found, searching full HTML")
                script = document.html()
            }
            
            val sources = sourcesRegex.find(script)?.groupValues?.get(1)
            if (sources == null) {
                // FALLBACK 1: Direct file pattern
                val directFile = Regex("file:\\s*[\"'](.*?\\.m3u8.*?)[\"']").find(script)?.groupValues?.get(1)
                if (directFile != null) {
                    android.util.Log.d("VidMoly", "Found direct m3u8 file")
                    return playlistUtils.extractFromHls(directFile,
                        videoNameGen = { quality -> "${prefix}VidMoly - $quality" },
                        masterHeaders = headers,
                        videoHeaders = headers,
                    )
                }

                // FALLBACK 2: Hyper-aggressive search for any m3u8 link
                val aggressiveM3u8 = Regex("""https?://[^\s"'<>]+?\.m3u8[^\s"'<>]*""").find(script)?.value
                if (aggressiveM3u8 != null) {
                    android.util.Log.d("VidMoly", "Found m3u8 via aggressive regex")
                    return playlistUtils.extractFromHls(aggressiveM3u8,
                        videoNameGen = { quality -> "${prefix}VidMoly - $quality" },
                        masterHeaders = headers,
                        videoHeaders = headers,
                    )
                }

                android.util.Log.d("VidMoly", "Sources regex failed to match")
                return emptyList()
            }
            
            val urls = urlsRegex.findAll(sources).map { it.groupValues[1] }.toList()
            if (urls.isEmpty()) {
                android.util.Log.d("VidMoly", "No URLs found in sources array")
            }
            
            urls.flatMap {
                playlistUtils.extractFromHls(it,
                    videoNameGen = { quality -> "${prefix}VidMoly - $quality" },
                    masterHeaders = headers,
                    videoHeaders = headers,
                )
            }
        } catch (e: Exception) {
            android.util.Log.d("VidMoly", "Exception: ${e.message}")
            emptyList()
        }
    }
}
