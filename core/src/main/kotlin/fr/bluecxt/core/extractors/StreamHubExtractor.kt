package fr.bluecxt.core.extractors

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import fr.bluecxt.core.model.ExtractedSource
import fr.bluecxt.core.utils.PlaylistUtils
import okhttp3.OkHttpClient

class StreamHubExtractor(private val client: OkHttpClient) {
    private val playlistUtils by lazy { PlaylistUtils(client) }

    suspend fun videosFromUrl(url: String): List<ExtractedSource> = runCatching {
        val response = client.newCall(GET(url)).awaitSuccess()
        val document = response.body.string()
        val id = REGEX_ID.find(document)?.groupValues?.get(1)
        val sub = REGEX_SUB.find(document)?.groupValues?.get(1)
        val masterUrl = "https://$sub.streamhub.ink/hls/,$id,.urlset/master.m3u8"
        playlistUtils.extractFromHls(masterUrl)
    }.getOrElse { emptyList() }

    companion object {
        private val REGEX_ID = Regex("urlset\\|(.*?)\\|")
        private val REGEX_SUB = Regex("width\\|(.*?)\\|")
    }
}
