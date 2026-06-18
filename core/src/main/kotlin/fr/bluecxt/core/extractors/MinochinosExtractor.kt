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
        }?.html() ?: throw Exception("MinoChinos: Could not find packed script")

        val unpacked = autoUnpacker(script) ?: throw Exception("MinoChinos: Could not unpack script")

        // Extract links from the unpacked script
        // var links={"hls3":"...","hls4":"...","hls2":"..."};
        val linksJson = unpacked.substringAfter("var links=", "").substringBefore(";", "")
        if (linksJson.isEmpty()) throw Exception("MinoChinos: Could not find links in unpacked script")

        val videoEntries = linkRegex.findAll(linksJson).map {
            it.groupValues[1] to it.groupValues[2]
        }.filter { (key, _) -> key == "hls3" }.toList()

        if (videoEntries.isEmpty()) throw Exception("MinoChinos: No hls3 links found in script")

        val result = videoEntries.parallelCatchingFlatMap { (key, videoUrl) ->
            val fixedUrl = if (videoUrl.startsWith("/")) {
                val urlObj = url.toHttpUrl()
                "${urlObj.scheme}://${urlObj.host}$videoUrl"
            } else {
                videoUrl
            }

            playlistUtils.extractFromHls(
                fixedUrl,
                referer = url,
            )
        }

        if (result.isEmpty()) throw Exception("MinoChinos: Failed to extract any videos from links")

        return result
    }

    companion object {
        private val linkRegex = Regex(""""(hls\d?)"\s*:\s*"([^"]+)"""")
    }
}
