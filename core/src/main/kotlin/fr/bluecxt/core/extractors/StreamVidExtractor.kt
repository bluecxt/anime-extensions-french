package fr.bluecxt.core.extractors

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import fr.bluecxt.core.model.ExtractedSource
import fr.bluecxt.core.utils.PlaylistUtils
import fr.bluecxt.core.utils.unpacker.autoUnpacker
import okhttp3.OkHttpClient

class StreamVidExtractor(private val client: OkHttpClient) {
    private val playlistUtils by lazy { PlaylistUtils(client) }

    suspend fun videosFromUrl(url: String, sourceChange: Boolean = false): List<ExtractedSource> = runCatching {
        val response = client.newCall(GET(url)).awaitSuccess()
        val doc = response.asJsoup()
        val script = doc.selectFirst("script:containsData(eval):containsData(p,a,c,k,e,d)")?.data()
            ?.let { autoUnpacker(it) }
            ?: return emptyList()
        val masterUrl = if (!sourceChange) {
            script.substringAfter("sources:[{src:\"").substringBefore("\",")
        } else {
            script.substringAfter("sources:[{file:\"").substringBefore("\"")
        }
        playlistUtils.extractFromHls(masterUrl)
    }.getOrElse { emptyList() }
}
