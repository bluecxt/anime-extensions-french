package fr.bluecxt.core.extractors

import android.util.Log
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import fr.bluecxt.core.VIDMOLY_LOG
import fr.bluecxt.core.model.ExtractedSource
import fr.bluecxt.core.safeRelativePath
import fr.bluecxt.core.utils.PlaylistUtils
import keiyoushi.utils.commonEmptyHeaders
import keiyoushi.utils.parallelCatchingFlatMap
import keiyoushi.utils.useAsJsoup
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Response

class VidmolyExtractor(private val client: OkHttpClient, headers: Headers = Headers.EMPTY) {

    companion object {
        const val BASE_URL = "https://vidmoly.biz"

        private val sourcesRegex by lazy { Regex("""sources\s*:\s*(.+?]),""") }
        private val urlsRegex by lazy { Regex("""file\s*:\s*["'](.+?)["']""") }
    }

    private val playlistUtils by lazy { PlaylistUtils(client) }

    private val headers: Headers = headers.newBuilder()
        .set("Origin", BASE_URL)
        .set("Referer", "$BASE_URL/")
        .build()

    suspend fun videosFromUrl(iframeUrl: String, depth: Int = 0): List<ExtractedSource> = coroutineScope {
        val backupUrl = BASE_URL + iframeUrl.safeRelativePath(BASE_URL)

        val deferredOriginal = async {
            runCatching { client.newCall(GET(iframeUrl, headers)).await() }.getOrNull()
        }

        val deferredBackup = async {
            if (backupUrl != iframeUrl) {
                runCatching { client.newCall(GET(backupUrl, headers)).await() }.getOrNull()
            } else {
                null
            }
        }

        Log.d(VIDMOLY_LOG, "Step 1: Start request for $iframeUrl")
        val response = select<Response?> {
            deferredOriginal.onAwait { res ->
                if (res?.isSuccessful == true) {
                    deferredBackup.cancel()
                    res
                } else {
                    deferredBackup.await()
                }
            }
            deferredBackup.onAwait { res ->
                if (res?.isSuccessful == true) {
                    deferredOriginal.cancel()
                    res
                } else {
                    deferredOriginal.await()
                }
            }
        }

        if (response == null || !response.isSuccessful) {
            throw Exception("Vidmoly: Failed to get response for $iframeUrl")
        }

        Log.d(VIDMOLY_LOG, "Step 2: Response ok, parsing HTML for $iframeUrl")
        val document = response.use { it.asJsoup() }

        Log.d(VIDMOLY_LOG, "Step 3: HTML parsed, checking script for $iframeUrl")
        val script = document.selectFirst("script:containsData(sources)")?.data()
        if (script == null) {
            if (document.html().contains("Loading") && depth < 3) {
                Log.d(VIDMOLY_LOG, "Bait detected, retrying (depth $depth) for $iframeUrl")
                kotlinx.coroutines.delay(1000)
                return@coroutineScope videosFromUrl(iframeUrl, depth + 1)
            }
            val fullHtml = document.html()
            Log.d(VIDMOLY_LOG, "Could not find script for $iframeUrl. Full HTML: $fullHtml")
            throw Exception("Vidmoly: Could not find player script (Title: ${document.title()}, Length: ${fullHtml.length})")
        }
        val sources = sourcesRegex.find(script)?.groupValues[1] ?: throw Exception("Vidmoly: Could not find sources in script")
        val urls = urlsRegex.findAll(sources)
            .mapNotNull { match -> match.groupValues[1].takeIf { it.isNotBlank() } }.toList()

        if (urls.isEmpty()) throw Exception("Vidmoly: No video URLs found in sources")

        urls.parallelCatchingFlatMap { videoUrl ->
            Log.d(VIDMOLY_LOG, "Step 4: Script found, extracting HLS playlist for $iframeUrl")
            try {
                playlistUtils.extractFromHls(
                    videoUrl,
                    masterHeaders = headers,
                    videoHeaders = headers,
                )
            } catch (e: Exception) {
                Log.e(VIDMOLY_LOG, "Error extracting HLS for $iframeUrl: ${e.message}")
                throw e
            }
        }
    }
}
