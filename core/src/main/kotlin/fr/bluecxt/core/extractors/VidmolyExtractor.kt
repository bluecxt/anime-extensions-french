package fr.bluecxt.core.extractors

import android.util.Log
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.util.asJsoup
import fr.bluecxt.core.ContentUnavailableException
import fr.bluecxt.core.DEFAULT_USER_AGENT
import fr.bluecxt.core.ExtractionException
import fr.bluecxt.core.VIDMOLY_LOG
import fr.bluecxt.core.model.ExtractedSource
import fr.bluecxt.core.safeRelativePath
import fr.bluecxt.core.utils.PlaylistUtils
import keiyoushi.utils.parallelCatchingFlatMap
import okhttp3.Headers
import okhttp3.OkHttpClient

class VidmolyExtractor(private val client: OkHttpClient, headers: Headers = Headers.EMPTY) {

    companion object {
        const val BASE_URL = "https://vidmoly.biz"

        private val sourcesRegex by lazy { Regex("""sources\s*:\s*(.+?]),""", RegexOption.DOT_MATCHES_ALL) }
        private val urlsRegex by lazy { Regex("""file\s*:\s*["'](.+?)["']""") }

        const val VIDEO_DELETED = "h2:contains(Sorry)"
    }

    private val playlistUtils by lazy { PlaylistUtils(client) }

    private val headers: Headers = headers.newBuilder()
        .set("User-Agent", DEFAULT_USER_AGENT)
        .set("Referer", "$BASE_URL/")
        .set("Connection", "close")
        .build()

    suspend fun videosFromUrl(iframeUrl: String): List<ExtractedSource> {
        val url = BASE_URL + iframeUrl.safeRelativePath(BASE_URL)
        val realUrl = if (url.contains(".to")) url.replace(".to", ".biz") else url

        Log.d(VIDMOLY_LOG, "Fetching Vidmoly page from: $url")

        val response = client.newCall(GET(url, headers)).await()

        val document = response.use { res ->
            if (res.code == 404) throw ContentUnavailableException("Video non available (404) $realUrl")
            if (!res.isSuccessful) throw ExtractionException("failed for $realUrl with ${res.code}: ${res.message}")
            res.asJsoup()
        }

        if (document.selectFirst(VIDEO_DELETED) != null || !document.location().contains(".html")) {
            Log.d(VIDMOLY_LOG, "$realUrl Video non available")
            throw ContentUnavailableException("Video non available (detected in DOM) $realUrl")
        }

        val script = document.selectFirst("script:containsData(sources)")?.data()
            ?: throw ExtractionException("Could not find player script for $realUrl")

        val sources = sourcesRegex.find(script)?.groupValues[1]
            ?: throw ExtractionException("Could not find sources in script for $realUrl")

        val urls = urlsRegex.findAll(sources)
            .mapNotNull { match -> match.groupValues[1].takeIf { it.isNotBlank() } }.toList()

        if (urls.isEmpty()) throw ExtractionException("No video URLs found in sources for $realUrl")

        Log.d(VIDMOLY_LOG, "Script found (${urls.size} video URLs), extracting HLS playlists...")

        return urls.parallelCatchingFlatMap { videoUrl ->
            Log.d(VIDMOLY_LOG, "Extracting HLS playlist for $videoUrl")
            playlistUtils.extractFromHls(
                videoUrl,
                masterHeaders = headers,
                videoHeaders = headers,
            )
        }
    }
}
