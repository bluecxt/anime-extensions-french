package fr.bluecxt.core.extractors

import android.util.Log
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.util.asJsoup
import fr.bluecxt.core.UQLOAD_LOG
import fr.bluecxt.core.defaultHeaders
import fr.bluecxt.core.model.ExtractedSource
import fr.bluecxt.core.utils.PlaylistUtils
import fr.bluecxt.core.utils.unpacker.autoUnpacker
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

// writed using https://github.com/skoruppa/docchi-players/blob/main/uqload.py

class UqloadExtractor(private val client: OkHttpClient) {

    suspend fun videosFromUrl(url: String): List<ExtractedSource> {
        val trueUrl = url.replace("embed-", "")
        val parsedUrl = trueUrl.toHttpUrl()

        val headers = defaultHeaders(
            referer = "${parsedUrl.scheme}://${parsedUrl.host}",
            accept = "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
        )

        val streamingHeaders = defaultHeaders(referer = "${parsedUrl.scheme}://${parsedUrl.host}")

        val response = client.newCall(GET(trueUrl, headers)).await()
        val finalUrl = response.request.url
        val path = finalUrl.encodedPath
        if (path == "/" || path.isEmpty()) {
            throw fr.bluecxt.core.ContentUnavailableException("Uqload: Video not found (redirected to host homepage: ${finalUrl.host})")
        }
        val soup = response.asJsoup()

        val script = soup.selectFirst("script:containsData(eval):containsData(m3u8)")?.data() ?: throw Exception("Could not find script with video data in Uqload")

        val unpackedJs = autoUnpacker(script) ?: throw Exception("Could not unpack script in Uqload")

        val m3u8Master = Regex("""sources:\s*\[\{\s*file:\s*"([^"]+)"""").find(unpackedJs)?.groupValues?.get(1)

        if (m3u8Master != null) {
            return try {
                PlaylistUtils(client).extractFromHls(
                    playlistUrl = m3u8Master,
                    masterHeaders = streamingHeaders,
                    videoHeaders = streamingHeaders,
                )
            } catch (e: Exception) {
                Log.e(UQLOAD_LOG, "Error parsing HLS playlist", e)
                listOf(
                    ExtractedSource(
                        url = m3u8Master,
                        headers = streamingHeaders,
                    ),
                )
            }
        }

        // mp4 fallback
        val mp4Regex = Regex("""sources:\s*\["(https?.*?\.mp4)"\]""")
        soup.select("script").forEach { scriptTag ->
            val content = scriptTag.data()
            if (content.contains("sources:")) {
                mp4Regex.find(content)?.groupValues?.get(1)?.let { mp4Url ->
                    return listOf(
                        ExtractedSource(
                            url = mp4Url,
                            headers = streamingHeaders,
                        ),
                    )
                }
            }
        }

        throw Exception("Uqload: No video sources found (HLS or MP4)")
    }
}
