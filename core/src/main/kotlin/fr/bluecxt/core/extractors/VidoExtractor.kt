package fr.bluecxt.core.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import fr.bluecxt.core.model.ExtractedSource
import fr.bluecxt.core.network.GET
import fr.bluecxt.core.network.awaitSuccess
import fr.bluecxt.core.utils.PlaylistUtils
import okhttp3.OkHttpClient

class VidoExtractor(private val client: OkHttpClient) {
    companion object {
        private const val VIDO_URL = "https://pink.vido.lol"
        private val REGEX_ID = Regex("master\\|(.*?)\\|")
    }

    private val playlistUtils by lazy { PlaylistUtils(client) }

    suspend fun videosFromUrl(url: String): List<ExtractedSource> {
        val document = client.newCall(GET(url)).awaitSuccess().body.string()
        val id = REGEX_ID.find(document)?.groupValues?.get(1)
        val masterUrl = "$VIDO_URL/hls/$id/master.m3u8"
        return playlistUtils.extractFromHls(masterUrl)
    }
}
