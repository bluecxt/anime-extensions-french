package fr.bluecxt.core.extractors

import android.util.Log
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import fr.bluecxt.core.model.ExtractedSource
import fr.bluecxt.core.utils.PlaylistUtils
import okhttp3.OkHttpClient

class OkruExtractor(private val client: OkHttpClient) {
    private val playlistUtils by lazy { PlaylistUtils(client) }

    private fun fixQuality(quality: String): String {
        val qualities = listOf(
            Pair("ultra", "2160p"),
            Pair("quad", "1440p"),
            Pair("full", "1080p"),
            Pair("hd", "720p"),
            Pair("sd", "480p"),
            Pair("low", "360p"),
            Pair("lowest", "240p"),
            Pair("mobile", "144p"),
        )
        return qualities.find { it.first == quality }?.second ?: quality
    }

    suspend fun videosFromUrl(url: String): List<ExtractedSource> {
        val document = client.newCall(GET(url)).awaitSuccess().asJsoup()
        val videoString = document.selectFirst("div[data-options]")
            ?.attr("data-options")
            ?: throw Exception("Could not find video data in Okru")

        return when {
            "ondemandHls" in videoString -> {
                val playlistUrl = videoString.extractLink("ondemandHls")
                playlistUtils.extractFromHls(playlistUrl)
            }

            "ondemandDash" in videoString -> {
                val playlistUrl = videoString.extractLink("ondemandDash")
                playlistUtils.extractFromDash(playlistUrl)
            }

            else -> videosFromJson(videoString)
        }
    }

    private fun String.extractLink(attr: String) = substringAfter("$attr\\\":\\\"")
        .substringBefore("\\\"")
        .replace("\\\\u0026", "&")

    private fun videosFromJson(videoString: String): List<ExtractedSource> {
        val arrayData = videoString.substringAfter("\\\"videos\\\":[{\\\"name\\\":\\\"")
            .substringBefore("]")

        return arrayData.split("{\\\"name\\\":\\\"").reversed().mapNotNull {
            val videoUrl = it.extractLink("url")
            val quality = it.substringBefore("\\\"").let { fixQuality(it) }

            if (videoUrl.startsWith("https://")) {
                ExtractedSource(
                    url = videoUrl,
                    quality = quality,
                )
            } else {
                null
            }
        }
    }
}
