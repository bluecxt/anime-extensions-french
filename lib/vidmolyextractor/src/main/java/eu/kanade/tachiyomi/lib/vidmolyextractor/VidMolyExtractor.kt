package eu.kanade.tachiyomi.lib.vidmolyextractor

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient

class VidMolyExtractor(private val client: OkHttpClient, headers: Headers = Headers.EMPTY) {

    private val baseUrl = "https://vidmoly.to"

    private val playlistUtils by lazy { PlaylistUtils(client) }

    private val headers: Headers = headers.newBuilder()
        .set("Origin", baseUrl)
        .set("Referer", "$baseUrl/")
        .build()

    private val sourcesRegex = Regex("sources:\\s*(\\[.*?])")
    private val urlsRegex = Regex("""file:\s*["'](.*?)["']""")

    fun videosFromUrl(url: String, prefix: String = ""): List<Video> {
        return try {
            android.util.Log.d("VidMoly", "Fetching URL: $url")
            val document = client.newCall(
                GET(url, headers.newBuilder().set("Sec-Fetch-Dest", "iframe").build())
            ).execute().asJsoup()
            
            val script = document.selectFirst("script:containsData(sources)")?.data()
            if (script == null) {
                android.util.Log.d("VidMoly", "Script containing sources not found")
                return emptyList()
            }
            
            val sources = sourcesRegex.find(script)?.groupValues?.get(1)
            if (sources == null) {
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
