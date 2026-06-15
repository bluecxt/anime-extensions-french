package fr.bluecxt.core.extractors

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
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

    suspend fun videosFromUrl(iframeUrl: String): List<ExtractedSource> = coroutineScope {
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

        val document = response?.use { it.asJsoup() } ?: return@coroutineScope emptyList()

        val script = document.selectFirst("script:containsData(sources)")?.data() ?: return@coroutineScope emptyList()
        val sources = sourcesRegex.find(script)?.groupValues[1] ?: return@coroutineScope emptyList()
        val urls = urlsRegex.findAll(sources)
            .mapNotNull { match -> match.groupValues[1].takeIf { it.isNotBlank() } }.toList()
        urls.parallelCatchingFlatMap { videoUrl ->
            playlistUtils.extractFromHls(
                videoUrl,
                masterHeaders = headers,
                videoHeaders = headers,
            )
        }
    }
}
