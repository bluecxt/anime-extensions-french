package fr.bluecxt.core.extractors

import android.util.Log
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import fr.bluecxt.core.model.ExtractedSource
import fr.bluecxt.core.utils.PlaylistUtils
import fr.bluecxt.core.utils.unpacker.autoUnpacker
import keiyoushi.utils.parallelCatchingFlatMap
import keiyoushi.utils.useAsJsoup
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

class MinochinosExtractor(private val client: OkHttpClient) {

    private val playlistUtils by lazy { PlaylistUtils(client) }

    suspend fun videosFromUrl(url: String): List<ExtractedSource> {
        val response = client.newCall(GET(url)).awaitSuccess()
        val document = response.useAsJsoup()

        val script = document.select("script").find {
            it.html().contains("eval(function(p,a,c,k,e,d)")
        }?.html() ?: return emptyList()

        val unpacked = autoUnpacker(script) ?: return emptyList()
        Log.d("MinoChinosExtractor", "Unpacked script: $unpacked")

        // Extract links from the unpacked script
        // var links={"hls3":"...","hls4":"...","hls2":"..."};
        val linksJson = unpacked.substringAfter("var links=", "").substringBefore(";", "")
        Log.d("MinoChinosExtractor", "Links JSON: $linksJson")
        if (linksJson.isEmpty()) return emptyList()

        val videoEntries = linkRegex.findAll(linksJson).map {
            it.groupValues[1] to it.groupValues[2]
        }.filter { (key, _) -> key == "hls3" }.toList()

        return videoEntries.parallelCatchingFlatMap { (key, videoUrl) ->
            val fixedUrl = if (videoUrl.startsWith("/")) {
                val urlObj = url.toHttpUrl()
                "${urlObj.scheme}://${urlObj.host}$videoUrl"
            } else {
                videoUrl
            }

            Log.d("MinoChinosExtractor", "Processing $key: $fixedUrl")

            playlistUtils.extractFromHls(
                fixedUrl,
                referer = url,
            )
        }
    }

    companion object {
        private val linkRegex = Regex(""""(hls\d?)"\s*:\s*"([^"]+)"""")
    }
}
