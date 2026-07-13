package fr.bluecxt.core.extractors

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import fr.bluecxt.core.defaultHeaders
import fr.bluecxt.core.model.ExtractedSource
import fr.bluecxt.core.utils.PlaylistUtils
import fr.bluecxt.core.utils.unpacker.autoUnpacker
import keiyoushi.utils.bodyString
import keiyoushi.utils.parallelCatchingFlatMap
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.util.regex.Pattern

// writed using https://github.com/skoruppa/docchi-stremio-addon/blob/main/app/players/lulustream.py

class LuluExtractor(private val client: OkHttpClient) {

    private val playlistUtils by lazy { PlaylistUtils(client) }

    private fun getHeaders(url: String): Headers {
        val parsedUrl = url.toHttpUrl()
        val baseHost = "${parsedUrl.scheme}://${parsedUrl.host}"
        return defaultHeaders(
            referer = "$baseHost/",
            origin = baseHost,
        )
    }

    suspend fun videosFromUrl(url: String): List<ExtractedSource> {
        val headers = getHeaders(url)
        val html = client.newCall(GET(url, headers)).awaitSuccess().bodyString()
        val m3u8Url = extractM3u8Url(html)
        val fixedUrl = fixM3u8Link(m3u8Url)

        return playlistUtils.extractFromHls(
            playlistUrl = fixedUrl,
            referer = url,
            masterHeaders = headers,
            videoHeaders = headers,
        )
    }

    private fun extractM3u8Url(html: String): String = when {
        html.contains("eval(function(p,a,c,k,e") -> {
            val unpacked = autoUnpacker(html) ?: throw Exception("LuluExtractor: Could not unpack script")
            Pattern.compile("sources:\\[\\{file:\"([^\"]+)\"")
                .matcher(unpacked)
                .takeIf { it.find() }
                ?.group(1) ?: throw Exception("LuluExtractor: Could not find m3u8 in unpacked script")
        }

        else -> {
            Pattern.compile("sources: \\[\\{file:\"(https?://[^\"]+)\"")
                .matcher(html)
                .takeIf { it.find() }
                ?.group(1) ?: throw Exception("LuluExtractor: Could not find m3u8 in HTML")
        }
    }

    private fun fixM3u8Link(link: String): String {
        val paramOrder = listOf("t", "s", "e", "f")
        val params = Pattern.compile("[?&]([^=]*)=([^&]*)").matcher(link).let { matcher ->
            generateSequence { if (matcher.find()) matcher.group(1) to matcher.group(2) else null }.toList()
        }

        val paramDict = mutableMapOf<String, String>()
        val extraParams = mutableMapOf<String, String>()

        params.forEachIndexed { index, (key, value) ->
            if (key.isNullOrEmpty()) {
                if (index < paramOrder.size) {
                    if (value != null) {
                        paramDict[paramOrder[index]] = value
                    }
                }
            } else {
                if (value != null) {
                    extraParams[key] = value
                }
            }
        }

        extraParams["i"] = "0.3"
        extraParams["sp"] = "0"

        val baseUrl = link.split("?")[0]

        val fixedLink = baseUrl.toHttpUrl().newBuilder()
        paramOrder.filter { paramDict.containsKey(it) }.forEach { key ->
            fixedLink.addQueryParameter(key, paramDict[key])
        }
        extraParams.forEach { (key, value) ->
            fixedLink.addQueryParameter(key, value)
        }

        return fixedLink.build().toString()
    }
}
