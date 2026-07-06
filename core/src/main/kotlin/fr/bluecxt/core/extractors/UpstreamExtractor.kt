package fr.bluecxt.core.extractors

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import fr.bluecxt.core.model.ExtractedSource
import fr.bluecxt.core.utils.PlaylistUtils
import fr.bluecxt.core.utils.unpacker.autoUnpacker
import okhttp3.OkHttpClient

class UpstreamExtractor(private val client: OkHttpClient) {
    private val playlistUtils by lazy { PlaylistUtils(client) }

    suspend fun videosFromUrl(url: String): List<ExtractedSource> = runCatching {
        val response = client.newCall(GET(url)).awaitSuccess()
        val jsE = response.asJsoup().selectFirst("script:containsData(eval)")!!.data()
        val unpacked = autoUnpacker(jsE) ?: return emptyList()
        val masterUrl = unpacked.substringAfter("{file:\"").substringBefore("\"}")
        playlistUtils.extractFromHls(masterUrl)
    }.getOrDefault(emptyList())
}
