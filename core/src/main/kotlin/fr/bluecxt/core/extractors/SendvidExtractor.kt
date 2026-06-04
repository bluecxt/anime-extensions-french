package fr.bluecxt.core.extractors

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import fr.bluecxt.core.model.ExtractedSource
import fr.bluecxt.core.toExtractedSources
import fr.bluecxt.core.utils.PlaylistUtils
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

class SendvidExtractor(private val client: OkHttpClient, private val headers: Headers) {
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    fun videosFromUrl(url: String, prefix: String = ""): List<ExtractedSource> {
        val document = client.newCall(GET(url, headers)).execute().asJsoup()
        val masterUrl = document.selectFirst("source#video_source")?.attr("src") ?: return emptyList()

        return if (masterUrl.contains(".m3u8")) {
            playlistUtils.extractFromHls(masterUrl, url).toExtractedSources(url)
        } else {
            val httpUrl = "https://${url.toHttpUrl().host}".toHttpUrlOrNull()
            listOf(
                ExtractedSource(
                    url = masterUrl,
                    referer = httpUrl,
                ),
            )
        }
    }
}
