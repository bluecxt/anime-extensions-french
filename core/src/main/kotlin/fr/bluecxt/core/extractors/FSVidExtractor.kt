// Copyright bluecxt
// SPDX-License-Identifier: Apache-2.0
package fr.bluecxt.core.extractors

import android.util.Base64
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import fr.bluecxt.core.ExtractionException
import fr.bluecxt.core.model.ExtractedSource
import fr.bluecxt.core.utils.PlaylistUtils
import fr.bluecxt.core.utils.defaultHeaders
import fr.bluecxt.core.utils.unpacker.autoUnpacker
import keiyoushi.utils.bodyString
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

class FSVidExtractor(private val client: OkHttpClient) {

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

        val unpacked = if (html.contains("eval(function(p,a,c,k,e")) {
            autoUnpacker(html) ?: throw ExtractionException("FSVid: Could not unpack script")
        } else {
            html
        }

        val m3u8Url = decryptM3u8Url(unpacked, url)
            ?: throw ExtractionException("FSVid: Could not decrypt m3u8 URL")

        return playlistUtils.extractFromHls(
            playlistUrl = m3u8Url,
            referer = url,
            masterHeaders = headers,
            videoHeaders = headers,
        )
    }

    private fun decryptM3u8Url(script: String, url: String): String? {
        val regex = Regex("""\(\s*function\s*\([^\)]*\)\s*\{.+?\}\s*\)\s*\(\s*["']([^"']+)["']\s*\)""")
        val match = regex.find(script) ?: return null
        val encodedStr = match.groupValues[1]

        val hostname = runCatching { url.toHttpUrl().host }.getOrDefault("fsvid.lol")
        var hostSum = 0
        for (char in hostname) {
            hostSum = (hostSum + char.code) and 255
        }

        val decodedBytes = Base64.decode(encodedStr, Base64.DEFAULT)
        val decodedString = String(decodedBytes, Charsets.ISO_8859_1)
        val reversed = decodedString.reversed()

        val decrypted = buildString {
            for (i in reversed.indices) {
                val kk = (0x3D + i * 89 + hostSum) and 255
                append((reversed[i].code xor kk).toChar())
            }
        }

        return decrypted.takeIf { it.startsWith("http") && !it.contains("troll/master.m3u8") }
    }
}
